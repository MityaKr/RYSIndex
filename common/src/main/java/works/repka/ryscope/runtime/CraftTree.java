package works.repka.ryscope.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;

/**
 * Дерево крафта: что нужно сделать и добыть, чтобы получить N штук предмета.
 * Учитывает то, что уже лежит в инвентаре, и позволяет выбрать рецепт в каждом узле.
 */
public final class CraftTree {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 400;

    public static final class Option {
        public final Category category;
        public final RecipeView recipe;
        /** Рецепт «обратимый» (слиток из блока, блок из слитков): по умолчанию такие не выбираются. */
        public final boolean reversible;

        Option(Category category, RecipeView recipe, boolean reversible) {
            this.category = category;
            this.recipe = recipe;
            this.reversible = reversible;
        }
    }

    public static final class Node {
        public Stack stack;
        public final Node parent;
        public final int depth;
        /** Ингредиент родительского рецепта, который закрывает этот узел. */
        public Ing ing;
        public long needed;
        public long fromInventory;
        /** Сколько раз выполнить выбранный рецепт. */
        public long crafts;
        public List<Option> options;
        /** Номер выбранного рецепта или -1: добывается как сырьё. */
        public int choice = -2;
        public final List<Node> children = new ArrayList<>();
        public boolean collapsed;
        /** Откуда добывается сырьё: названия мобов, блоков или сундуков. */
        public String sources = "";

        Node(Stack stack, Node parent) {
            this.stack = stack;
            this.parent = parent;
            this.depth = parent == null ? 0 : parent.depth + 1;
        }

        public long remaining() {
            return Math.max(0, needed - fromInventory);
        }

        public boolean satisfied() {
            return fromInventory >= needed;
        }

        public Option chosen() {
            return options == null || choice < 0 || choice >= options.size() ? null : options.get(choice);
        }

        boolean hasAncestor(Object key) {
            for (Node n = this; n != null; n = n.parent) if (n.stack.key().equals(key)) return true;
            return false;
        }
    }

    /** Сырьё, которое осталось добыть: то, что уже есть в инвентаре, сюда не попадает. */
    public static final class Raw {
        public final Stack stack;
        public long need;

        Raw(Stack stack) {
            this.stack = stack;
        }
    }

    public final Node root;
    public long amount = 1;
    public boolean useInventory = true;
    public final Map<Object, Raw> raw = new LinkedHashMap<>();
    public final Set<Category> stations = new LinkedHashSet<>();
    public int steps;
    public int maxDepth;
    private int nodeCount;
    private Map<Object, Long> pool;

    public CraftTree(Stack target) {
        this.root = new Node(target.withAmount(1), null);
        recompute();
    }

    /** Пересчитывает количества, распределение инвентаря и итоги. Выбор рецептов в узлах сохраняется. */
    public void recompute() {
        raw.clear();
        stations.clear();
        steps = 0;
        maxDepth = 0;
        nodeCount = 0;
        pool = new HashMap<>();
        if (useInventory && InventoryView.tracking()) {
            for (Map.Entry<Item, Integer> e : InventoryView.snapshot().entrySet()) pool.put(e.getKey(), (long) e.getValue());
        }
        root.needed = amount;
        // сам целевой предмет из инвентаря не вычитаем: игрок спрашивает, как сделать ещё
        visit(root, false);
    }

    private void visit(Node node, boolean takeFromInventory) {
        nodeCount++;
        maxDepth = Math.max(maxDepth, node.depth);
        Object key = node.stack.key();
        node.fromInventory = 0;
        if (takeFromInventory) {
            long have = pool.getOrDefault(key, 0L);
            long use = Math.min(have, node.needed);
            if (use > 0) {
                pool.put(key, have - use);
                node.fromInventory = use;
            }
        }
        long remaining = node.remaining();
        if (remaining == 0) {
            node.children.clear();
            node.crafts = 0;
            return;
        }
        if (node.options == null) loadOptions(node);
        if (node.choice == -2) node.choice = defaultChoice(node);
        Option option = node.chosen();
        if (option == null || node.depth >= MAX_DEPTH || nodeCount >= MAX_NODES) {
            node.children.clear();
            node.crafts = 0;
            Raw r = raw.computeIfAbsent(key, k -> new Raw(node.stack));
            r.need += remaining;
            return;
        }
        long perCraft = 1;
        for (Stack out : option.recipe.outputs()) {
            if (out.key().equals(key)) {
                perCraft = Math.max(1, out.amount);
                break;
            }
        }
        node.crafts = (remaining + perCraft - 1) / perCraft;
        steps++;
        stations.add(option.category);

        // одинаковые ингредиенты рецепта объединяются в один дочерний узел
        Map<Object, long[]> totals = new LinkedHashMap<>();
        Map<Object, Ing> ings = new HashMap<>();
        Map<Object, Stack> picks = new HashMap<>();
        for (Ing ing : option.recipe.inputs()) {
            if (ing.isEmpty()) continue;
            Stack pick = pick(ing);
            long per = Math.max(1, pick.amount);
            totals.computeIfAbsent(pick.key(), k -> new long[1])[0] += per * node.crafts;
            ings.putIfAbsent(pick.key(), ing);
            picks.putIfAbsent(pick.key(), pick);
        }
        List<Node> old = new ArrayList<>(node.children);
        node.children.clear();
        for (Map.Entry<Object, long[]> e : totals.entrySet()) {
            Node child = null;
            for (Node o : old) {
                if (o.stack.key().equals(e.getKey())) {
                    child = o;
                    break;
                }
            }
            if (child == null) child = new Node(picks.get(e.getKey()).withAmount(1), node);
            child.ing = ings.get(e.getKey());
            child.needed = e.getValue()[0];
            node.children.add(child);
            visit(child, true);
        }
    }

    /** Из взаимозаменяемых вариантов берём тот, что уже есть у игрока, иначе первый. */
    private Stack pick(Ing ing) {
        for (Stack o : ing.options) {
            if (pool.getOrDefault(o.key(), 0L) > 0) return o;
        }
        return ing.options.get(0);
    }

    private void loadOptions(Node node) {
        List<Option> out = new ArrayList<>();
        Snapshot snap = Indexer.snapshot();
        // что делается из этого предмета: если рецепт требует именно такой продукт, пара обратима
        Set<Object> madeFromThis = new HashSet<>();
        for (Snapshot.Entry e : snap.consuming(node.stack)) {
            for (Stack o : e.recipe().outputs()) madeFromThis.add(o.key());
        }
        try {
            List<String> names = new ArrayList<>();
            for (Lookup.Group g : Lookup.find(node.stack, Lookup.Mode.RECIPES)) {
                for (RecipeView r : g.recipes()) {
                    if (!r.isCrafting()) {
                        // дроп и лут не выполняются игроком как рецепт: это подсказка, где добыть
                        if (names.size() < 3 && r instanceof works.repka.ryscope.content.SourceView sv) names.add(sv.name().getString());
                        continue;
                    }
                    if (out.size() >= 64) break;
                    boolean cyclic = false, reversible = false, any = false;
                    for (Ing ing : r.inputs()) {
                        if (ing.isEmpty()) continue;
                        any = true;
                        boolean allAncestors = true, allReverse = true;
                        for (Stack o : ing.options) {
                            if (!node.hasAncestor(o.key())) allAncestors = false;
                            if (!madeFromThis.contains(o.key())) allReverse = false;
                        }
                        if (allAncestors) cyclic = true;
                        if (allReverse) reversible = true;
                    }
                    if (!any || cyclic) continue;
                    // «разборка» (один вид входа даёт четыре и больше штук, как блок в слитки) тоже не путь добычи
                    int kinds = 0;
                    for (Ing ing : r.inputs()) if (!ing.isEmpty()) kinds++;
                    if (kinds == 1 && r.ticks() == 0) {
                        for (Stack o : r.outputs()) {
                            if (o.key().equals(node.stack.key()) && o.amount >= 4) reversible = true;
                        }
                    }
                    out.add(new Option(g.category(), r, reversible));
                }
            }
            node.sources = String.join(", ", names);
        } catch (Throwable ignored) {
            // без рецептов узел считается сырьём
        }
        node.options = out;
    }

    /** По умолчанию берём необратимый рецепт, для которого у игрока есть больше всего ингредиентов. */
    private int defaultChoice(Node node) {
        int best = -1;
        float bestScore = -1f;
        for (int i = 0; i < node.options.size(); i++) {
            Option o = node.options.get(i);
            if (o.reversible) continue;
            int total = 0, have = 0;
            for (Ing ing : o.recipe.inputs()) {
                if (ing.isEmpty()) continue;
                total++;
                for (Stack s : ing.options) {
                    if (pool.getOrDefault(s.key(), 0L) > 0) {
                        have++;
                        break;
                    }
                }
            }
            float score = total == 0 ? 0f : have / (float) total;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Следующий рецепт узла; после последнего идёт «сырьё». */
    public void cycleChoice(Node node, int dir) {
        if (node.options == null || node.options.isEmpty()) return;
        int n = node.options.size();
        int next = node.choice + dir;
        if (next >= n) next = -1;
        if (next < -1) next = n - 1;
        node.choice = next;
        node.children.clear();
        recompute();
    }

    public void setAmount(long value) {
        amount = Math.max(1, Math.min(9999, value));
        recompute();
    }

    public int missingKinds() {
        return raw.size();
    }
}
