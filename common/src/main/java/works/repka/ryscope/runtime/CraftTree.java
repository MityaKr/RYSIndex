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

    /** Сколько времени за один вызов можно тратить на поиск рецептов, когда дерево строится по частям. */
    private static final long SLICE_NANOS = 10_000_000L;

    /** Сигнал «время вышло, продолжим в следующем кадре». Без стека вызовов: это не ошибка. */
    private static final class Pending extends RuntimeException {
        static final Pending INSTANCE = new Pending();

        private Pending() {
            super(null, null, false, false);
        }
    }

    /** Всё дорогое про один ресурс: его рецепты и что из него делается. От места узла в дереве не зависит. */
    private record Loaded(List<Candidate> candidates, Set<Object> madeFromThis, String sources) {}

    private record Candidate(Category category, RecipeView recipe, List<Ing> inputs, boolean unpacking, boolean singleKind) {}

    private final boolean deferred;
    private boolean ready = true;
    private long deadline;
    private final Map<Object, Loaded> loaded = new HashMap<>();

    public CraftTree(Stack target) {
        this(target, false);
    }

    /**
     * @param deferred строить по частям: каждый вызов {@link #recompute()} тратит на поиск рецептов не больше
     *                 нескольких миллисекунд, и пока {@link #ready()} возвращает false, дерево показывать нельзя.
     *                 Нужно экрану, чтобы он открывался сразу, а не после поиска по всем рецептам сборки.
     */
    public CraftTree(Stack target, boolean deferred) {
        this.root = new Node(target.withAmount(1), null);
        this.deferred = deferred;
        recompute();
    }

    /** Дерево построено целиком и его можно показывать. */
    public boolean ready() {
        return ready;
    }

    /** Сколько разных ресурсов уже просмотрено: для индикатора загрузки. */
    public int examined() {
        return loaded.size();
    }

    /** Пересчитывает количества, распределение инвентаря и итоги. Выбор рецептов в узлах сохраняется. */
    public void recompute() {
        deadline = deferred ? System.nanoTime() + SLICE_NANOS : 0;
        try {
            compute();
            ready = true;
        } catch (Pending pending) {
            // уже найденное лежит в кэше: следующий вызов пройдёт дальше
            ready = false;
        }
    }

    private void compute() {
        raw.clear();
        stations.clear();
        steps = 0;
        maxDepth = 0;
        nodeCount = 0;
        pool = new HashMap<>();
        if (useInventory && InventoryView.tracking()) {
            for (Map.Entry<Item, Integer> e : InventoryView.snapshot().entrySet()) pool.put(e.getKey(), (long) e.getValue());
            // то, что игрок видел в хранилищах рядом, тоже считается имеющимся
            for (Map.Entry<Item, Long> e : Storages.counts().entrySet()) pool.merge(e.getKey(), e.getValue(), Long::sum);
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
        if (node.choice == -2) node.choice = shortest ? shortestChoice(node) : defaultChoice(node);
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
        try {
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
        } catch (Pending pending) {
            // обход прерван: возвращаем узлы, до которых не дошли, иначе пропадёт выбор рецептов в них
            for (Node o : old) if (!node.children.contains(o)) node.children.add(o);
            throw pending;
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
        Loaded info = loaded(node.stack);
        Object self = node.stack.key();
        List<Option> out = new ArrayList<>();
        for (Candidate c : info.candidates()) {
            if (out.size() >= 64) break;
            boolean cyclic = false, reversible = c.unpacking();
            for (Ing ing : c.inputs()) {
                if (ing.isEmpty()) continue;
                boolean allAncestors = true, allReverse = true;
                for (Stack o : ing.options) {
                    if (!node.hasAncestor(o.key())) allAncestors = false;
                    if (!info.madeFromThis().contains(o.key())) allReverse = false;
                    // починка и улучшение берут на вход сам предмет: получить его так нельзя
                    // (на сборке RepYS «починка духами» из Malum выбиралась как рецепт лука)
                    if (o.key().equals(self)) cyclic = true;
                }
                if (allAncestors) cyclic = true;
                // обратимая пара это «слиток из блока, блок из слитков»: у обоих рецептов один вид входа.
                // Рецепт из нескольких видов (лук из палок и нити) обратимым не считается, даже если мод умеет
                // разбирать лук обратно на палки и нить: иначе в больших сборках дерево уходит в экзотику.
                if (allReverse && c.singleKind()) reversible = true;
            }
            if (cyclic) continue;
            out.add(new Option(c.category(), c.recipe(), reversible));
        }
        node.sources = info.sources();
        node.options = out;
    }

    /** Сколько разных видов ресурсов на входе рецепта: девять слитков в сетке это один вид. */
    private static int distinctKinds(List<Ing> inputs) {
        Set<Object> kinds = new HashSet<>();
        for (Ing ing : inputs) if (!ing.isEmpty() && !ing.options.isEmpty()) kinds.add(ing.options.get(0).key());
        return kinds.size();
    }

    /**
     * Рецепты ресурса. Это самая долгая часть построения: поиск по всем рецептам сборки и разбор их входов.
     * Результат кэшируется на время жизни дерева; при построении по частям здесь же проверяется бюджет времени.
     */
    private Loaded loaded(Stack stack) {
        Object key = stack.key();
        Loaded cached = loaded.get(key);
        if (cached != null) return cached;
        if (deadline != 0 && System.nanoTime() > deadline) throw Pending.INSTANCE;
        Set<Object> madeFromThis = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            // что делается из этого предмета
            for (Snapshot.Entry e : Indexer.snapshot().consuming(stack)) {
                if (distinctKinds(e.recipe().inputs()) != 1) continue;
                for (Stack o : e.recipe().outputs()) madeFromThis.add(o.key());
            }
            for (Lookup.Group g : Lookup.find(stack, Lookup.Mode.RECIPES)) {
                for (RecipeView r : g.recipes()) {
                    if (!r.isCrafting()) {
                        // дроп и лут не выполняются игроком как рецепт: это подсказка, где добыть
                        if (names.size() < 3 && r instanceof works.repka.ryscope.content.SourceView sv) names.add(sv.name().getString());
                        continue;
                    }
                    if (candidates.size() >= 96) continue;
                    List<Ing> inputs = r.inputs();
                    int kinds = 0;
                    for (Ing ing : inputs) if (!ing.isEmpty()) kinds++;
                    if (kinds == 0) continue;
                    boolean singleKind = distinctKinds(inputs) == 1;
                    // «разборка» (один вид входа даёт четыре и больше штук, как блок в слитки) не путь добычи
                    boolean unpacking = false;
                    if (kinds == 1 && r.ticks() == 0) {
                        for (Stack o : r.outputs()) {
                            if (o.key().equals(key) && o.amount >= 4) unpacking = true;
                        }
                    }
                    candidates.add(new Candidate(g.category(), r, inputs, unpacking, singleKind));
                }
            }
        } catch (Throwable ignored) {
            // без рецептов узел считается сырьём
        }
        Loaded result = new Loaded(candidates, madeFromThis, String.join(", ", names));
        loaded.put(key, result);
        return result;
    }

    /**
     * Какой рецепт взять по умолчанию. По порядку:
     * <ol>
     *   <li>рецепт, для которого у игрока есть все ингредиенты;</li>
     *   <li>«родной» рецепт: из того же мода, что и сам предмет (для ванильного предмета это ванильный рецепт);</li>
     *   <li>если родного нет, а предмет добывается в мире (дроп, лут), он считается сырьём: в больших сборках
     *       у нити или кости десятки рецептов из чужих модов, и дерево уходило в них вместо «убить паука»;</li>
     *   <li>иначе первый необратимый: варианты идут в порядке категорий, верстак первым.</li>
     * </ol>
     * Частичное наличие ингредиентов не учитывается: одна общая доска в рецепте ритуала перевешивала верстак.
     */
    private int defaultChoice(Node node) {
        int first = -1, own = -1;
        String namespace = node.stack.namespace();
        for (int i = 0; i < node.options.size(); i++) {
            Option o = node.options.get(i);
            if (o.reversible) continue;
            if (first < 0) first = i;
            if (own < 0 && o.recipe.id() != null && o.recipe.id().getNamespace().equals(namespace)) own = i;
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
            if (total > 0 && have == total) return i;
        }
        if (own >= 0) return own;
        if (node.parent != null && node.sources != null && !node.sources.isEmpty()) return -1;
        return first;
    }

    // --- кратчайший путь ---

    /** Насколько глубоко и широко искать: без границ перебор всех рецептов большой сборки не закончится. */
    private static final int SHORT_DEPTH = 4;
    private static final int SHORT_OPTIONS = 12;
    private static final int SHORT_ITEMS = 1500;
    private static final int FAR = 1000;

    private boolean shortest;
    private final Map<Object, Integer> costMemo = new HashMap<>();
    private final Map<Object, RecipeView> bestMemo = new HashMap<>();

    public boolean shortest() {
        return shortest;
    }

    /**
     * Включает выбор рецептов по наименьшему числу шагов вместо обычных правил. Выбор во всех узлах
     * сбрасывается и считается заново; после этого игрок по-прежнему может менять рецепт в любом узле.
     */
    public void setShortest(boolean value) {
        shortest = value;
        root.choice = -2;
        root.children.clear();
        recompute();
    }

    private int shortestChoice(Node node) {
        Set<Object> path = new HashSet<>();
        for (Node n = node.parent; n != null; n = n.parent) path.add(n.stack.key());
        steps(node.stack, node.parent == null ? 0 : 1, path, node.parent == null);
        RecipeView best = bestMemo.get(node.stack.key());
        if (best == null) return node.parent != null && costMemo.containsKey(node.stack.key()) ? -1 : defaultChoice(node);
        for (int i = 0; i < node.options.size(); i++) if (node.options.get(i).recipe == best) return i;
        return defaultChoice(node);
    }

    /**
     * Наименьшее число шагов крафта, чтобы получить ресурс. Сырьё стоит ноль. Результат запоминается на время
     * жизни дерева, поэтому при построении по частям прерванный поиск продолжается с найденного.
     */
    private int steps(Stack stack, int depth, Set<Object> path, boolean target) {
        Object key = stack.key();
        if (!target && pool != null && pool.getOrDefault(key, 0L) > 0) return 0;
        Integer known = costMemo.get(key);
        if (known != null) return known;
        if (depth >= SHORT_DEPTH || path.contains(key)) return FAR;
        if (loaded.size() >= SHORT_ITEMS && !loaded.containsKey(key)) return FAR;
        Loaded info = loaded(stack);
        String namespace = stack.namespace();

        // первый проход без рекурсии: какие рецепты вообще годятся и есть ли среди них «родной»
        List<Candidate> usable = new ArrayList<>();
        boolean own = false;
        for (Candidate c : info.candidates()) {
            if (usable.size() >= SHORT_OPTIONS) break;
            boolean skip = c.unpacking();
            for (Ing ing : c.inputs()) {
                if (ing.isEmpty() || ing.options.isEmpty()) continue;
                boolean allOnPath = true, allReverse = true;
                for (Stack o : ing.options) {
                    if (o.key().equals(key)) skip = true;
                    if (!path.contains(o.key())) allOnPath = false;
                    if (!info.madeFromThis().contains(o.key())) allReverse = false;
                }
                if (allOnPath || (allReverse && c.singleKind())) skip = true;
            }
            if (skip) continue;
            usable.add(c);
            if (c.recipe().id() != null && c.recipe().id().getNamespace().equals(namespace)) own = true;
        }
        // те же правила сырья, что и в обычном выборе: рецептов нет или предмет добывается в мире
        if (usable.isEmpty() || (!target && !own && !info.sources().isEmpty())) {
            costMemo.put(key, 0);
            return 0;
        }

        int best = Integer.MAX_VALUE;
        RecipeView bestRecipe = null;
        path.add(key);
        try {
            for (Candidate c : usable) {
                int total = 1;
                Set<Object> seen = new HashSet<>();
                for (Ing ing : c.inputs()) {
                    if (ing.isEmpty() || ing.options.isEmpty()) continue;
                    Stack pick = pick(ing);
                    if (!seen.add(pick.key())) continue;
                    total += steps(pick, depth + 1, path, false);
                    if (total >= best) break;
                }
                if (total < best) {
                    best = total;
                    bestRecipe = c.recipe();
                }
            }
        } finally {
            path.remove(key);
        }
        costMemo.put(key, best);
        if (bestRecipe != null) bestMemo.put(key, bestRecipe);
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
