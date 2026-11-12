package works.repka.ryscope.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.CraftTree.Node;
import works.repka.ryscope.runtime.CraftTree.Option;

/**
 * Планировщик производства: цель «N штук в час» раскладывается по машинам.
 * Для каждого шага считается число машин, их загрузка и расход энергии; находится узкое место.
 * Время и энергия берутся из рецептов; у рецептов без этих данных (ручной крафт, чужие плагины) машины не считаются.
 */
public final class Planner {
    private static final double TICKS_PER_HOUR = 72000.0;

    public enum Mode { TREE, FAST, CHEAP }

    public static final class Line {
        public Stack output;
        public Category category;
        public RecipeView recipe;
        public double craftsPerHour;
        public int ticks;
        public int machines;
        public double load;
        public double energyPerTick;

        public boolean timed() {
            return ticks > 0;
        }
    }

    private final Stack target;
    public CraftTree tree;
    public long rate = 64;
    public Mode mode = Mode.TREE;
    public final List<Line> lines = new ArrayList<>();
    private boolean ready;
    private int pass;
    public int totalMachines;
    public double totalEnergy;
    public Line bottleneck;

    /** Маршрут дерева, из которого открыт план: режим «Как в дереве» идёт по нему. */
    private final CraftTree.Route route;

    public Planner(Stack target) {
        this(target, null);
    }

    public Planner(Stack target, CraftTree.Route route) {
        this.target = target;
        this.route = route;
        rebuild();
    }

    public void setRate(long perHour) {
        rate = Math.max(1, Math.min(1_000_000, perHour));
        rebuild();
    }

    /** План посчитан и его можно показывать. */
    public boolean ready() {
        return ready;
    }

    public int examined() {
        return tree == null ? 0 : tree.examined();
    }

    /** Следующая порция расчёта: не больше нескольких миллисекунд, вызывается каждый кадр. */
    public void advance() {
        if (ready) return;
        if (!tree.ready()) {
            tree.recompute();
            if (!tree.ready()) return;
        }
        if (mode != Mode.TREE && pass < 6) {
            pass = applyPass() ? pass + 1 : 6;
            tree.recompute();
            return;
        }
        collect();
        ready = true;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        rebuild();
    }

    private void rebuild() {
        CraftTree previous = tree;
        tree = new CraftTree(target, true);
        if (previous != null) tree.inherit(previous);
        tree.apply(route);
        // планируем производство с нуля: то, что лежит в инвентаре, поток не обеспечит
        tree.useInventory = false;
        // и сеть AE2 поток не обеспечит: план считает все шаги
        tree.autocraft = false;
        // режимы плана сами переставляют рецепты в узлах: смена станций их не сбрасывает
        tree.followStations = false;
        tree.setAmount(rate);
        pass = 0;
        ready = false;
    }

    /** Один проход выбора рецептов под режим. @return true, если что-то поменялось. */
    private boolean applyPass() {
        boolean changed = false;
        List<Node> nodes = new ArrayList<>();
        flatten(tree.root, nodes);
        for (Node node : nodes) {
            if (node.options == null || node.options.isEmpty()) continue;
            int best = pick(node);
            if (best >= 0 && best != node.choice) {
                node.choice = best;
                node.children.clear();
                changed = true;
            }
        }
        return changed;
    }

    private static void flatten(Node node, List<Node> out) {
        out.add(node);
        for (Node c : node.children) flatten(c, out);
    }

    private static long perCraft(RecipeView recipe, Stack stack) {
        for (Stack out : recipe.outputs()) if (out.key().equals(stack.key())) return Math.max(1, out.amount);
        return 1;
    }

    /** Рецепт узла, лучший для режима: быстрее всего на единицу продукта или дешевле всего по энергии. */
    private int pick(Node node) {
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < node.options.size(); i++) {
            Option o = node.options.get(i);
            if (o.reversible) continue;
            long per = perCraft(o.recipe, node.stack);
            double score;
            if (o.recipe.ticks() <= 0) {
                // без данных о времени: годится, только если машинного рецепта нет
                score = 1e12 + i;
            } else if (mode == Mode.FAST) {
                score = o.recipe.ticks() / (double) per;
            } else {
                score = o.recipe.energy() / (double) per + o.recipe.ticks() / (double) per * 1e-6;
            }
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private void collect() {
        lines.clear();
        totalMachines = 0;
        totalEnergy = 0;
        bottleneck = null;
        Map<RecipeView, Line> byRecipe = new LinkedHashMap<>();
        List<Node> nodes = new ArrayList<>();
        flatten(tree.root, nodes);
        for (Node node : nodes) {
            Option option = node.chosen();
            if (option == null || node.crafts <= 0) continue;
            Line line = byRecipe.computeIfAbsent(option.recipe, r -> {
                Line l = new Line();
                l.output = node.stack;
                l.category = option.category;
                l.recipe = option.recipe;
                l.ticks = option.recipe.ticks();
                return l;
            });
            line.craftsPerHour += node.crafts;
        }
        for (Line line : byRecipe.values()) {
            if (line.timed()) {
                double need = line.craftsPerHour * line.ticks / TICKS_PER_HOUR;
                line.machines = Math.max(1, (int) Math.ceil(need - 1e-9));
                line.load = need / line.machines;
                line.energyPerTick = line.recipe.energy() / (double) line.ticks * need;
                totalMachines += line.machines;
                totalEnergy += line.energyPerTick;
                if (bottleneck == null || line.machines > bottleneck.machines
                    || line.machines == bottleneck.machines && line.load > bottleneck.load) {
                    bottleneck = line;
                }
            }
            lines.add(line);
        }
    }
}
