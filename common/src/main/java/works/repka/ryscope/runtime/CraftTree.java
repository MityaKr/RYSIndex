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
        /** Часть {@link #fromInventory}, взятая из запомненных хранилищ, а не из инвентаря. */
        public long fromStorage;
        /** Сколько раз выполнить выбранный рецепт. */
        public long crafts;
        public List<Option> options;
        /** Номер выбранного рецепта или -1: добывается как сырьё. */
        public int choice = -2;
        public final List<Node> children = new ArrayList<>();
        public boolean collapsed;
        /** Откуда добывается сырьё: названия мобов, блоков или сундуков. */
        public String sources = "";
        /** Рецепт выбран, но ветка не раскрыта: дерево упёрлось в предел глубины или числа узлов. */
        public boolean truncated;
        /** Остаток не крафтится вручную: его закажет сеть AE2 (на предмет есть шаблон), ветка не раскрывается. */
        public boolean ordered;
        /**
         * В общей сумме целей: рецепт этого ресурса взят из маршрута цели выше на доске, хотя у другой цели выбран
         * другой. null, если спора не было.
         */
        @org.jetbrains.annotations.Nullable
        public Stack routeFrom;

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

    /** Выбор игрока в узле: рецепт (сам объект и его id, если он есть) или сырьё, когда оба пусты. */
    private record Pick(RecipeView recipe, String recipeId) {
        static final Pick RAW = new Pick(null, null);

        static Pick of(RecipeView recipe) {
            return new Pick(recipe, recipe.id() == null ? null : recipe.id().toString());
        }

        boolean raw() {
            return recipe == null && recipeId == null;
        }

        boolean matches(RecipeView r) {
            return r == recipe || (recipeId != null && r.id() != null && recipeId.equals(r.id().toString()));
        }

        boolean same(Pick o) {
            if (raw() || o.raw()) return raw() && o.raw();
            return recipeId != null ? recipeId.equals(o.recipeId) : recipe == o.recipe;
        }
    }

    /**
     * Маршрут: выбор рецептов по id ресурса узла и флаг «кратчайший». Переносится в план, в цель на доске
     * и в новое дерево того же предмета, чтобы они считали тем же путём, что показало дерево.
     */
    public static final class Route {
        final Map<String, Pick> picks = new HashMap<>();
        boolean shortest;

        /** Маршрут, в котором в корне выбран этот рецепт. */
        public static Route root(Stack stack, RecipeView recipe) {
            Route r = new Route();
            r.picks.put(stack.id().toString(), Pick.of(recipe));
            return r;
        }

        public boolean isEmpty() {
            return picks.isEmpty() && !shortest;
        }

        public boolean sameAs(Route o) {
            if (o == null) return isEmpty();
            if (shortest != o.shortest || picks.size() != o.picks.size()) return false;
            for (Map.Entry<String, Pick> e : picks.entrySet()) {
                Pick other = o.picks.get(e.getKey());
                if (other == null || !e.getValue().same(other)) return false;
            }
            return true;
        }

        /**
         * Копия без самих объектов рецептов, только с их id (как в файле): её можно долго хранить, не удерживая
         * рецепты и рантайм JEI прошлого мира. Рецепты без id отбрасываются.
         */
        public Route withoutRecipes() {
            Route r = new Route();
            r.shortest = shortest;
            for (Map.Entry<String, Pick> e : picks.entrySet()) {
                Pick pick = e.getValue();
                if (pick.raw()) r.picks.put(e.getKey(), Pick.RAW);
                else if (pick.recipeId != null) r.picks.put(e.getKey(), new Pick(null, pick.recipeId));
            }
            return r;
        }

        /** Для файла: рецепты без id не сохраняются, сырьё записывается пустой строкой. */
        public com.google.gson.JsonObject toJson() {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            if (shortest) o.addProperty("shortest", true);
            com.google.gson.JsonObject p = new com.google.gson.JsonObject();
            for (Map.Entry<String, Pick> e : picks.entrySet()) {
                Pick pick = e.getValue();
                if (pick.raw()) p.addProperty(e.getKey(), "");
                else if (pick.recipeId != null) p.addProperty(e.getKey(), pick.recipeId);
            }
            if (p.size() > 0) o.add("picks", p);
            return o;
        }

        public static Route fromJson(com.google.gson.JsonObject o) {
            Route r = new Route();
            r.shortest = o.has("shortest") && o.get("shortest").getAsBoolean();
            if (o.has("picks")) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("picks").entrySet()) {
                    String id = e.getValue().getAsString();
                    r.picks.put(e.getKey(), id.isEmpty() ? Pick.RAW : new Pick(null, id));
                }
            }
            return r;
        }
    }

    /** Сырьё, которое осталось добыть: то, что уже есть в инвентаре, сюда не попадает. */
    public static final class Raw {
        public final Stack stack;
        public long need;
        /** Хотя бы часть пришла из нераскрытой ветки: у предмета есть рецепт, это не настоящее сырьё. */
        public boolean truncated;

        Raw(Stack stack) {
            this.stack = stack;
        }
    }

    /**
     * Строка итога: всё, что дерево берёт как есть (сырьё, заказ в сети, готовое из запасов), с тем, откуда оно.
     * {@code need = inventory + storage + ordered + left}.
     */
    public static final class Leaf {
        public final Stack stack;
        public long need, inventory, storage, ordered, left;
        public boolean truncated;

        Leaf(Stack stack) {
            this.stack = stack;
        }
    }

    public final Node root;
    public long amount = 1;
    public boolean useInventory = true;
    public final Map<Object, Raw> raw = new LinkedHashMap<>();
    /** Итог по всему, что дерево берёт как есть: для таблицы общей суммы целей. */
    public final Map<Object, Leaf> leaves = new LinkedHashMap<>();
    /** Дерево упёрлось в предел числа узлов: итог неполный. */
    public boolean incomplete;
    /** Что закажет сеть AE2: остаток после инвентаря и хранилищ, по предметам. */
    public final Map<Object, Raw> ordered = new LinkedHashMap<>();
    /**
     * Учитывать автокрафт AE2, если он включён в настройках ({@link Config#treeAutocraft}). План выключает:
     * он считает производство с нуля.
     */
    public boolean autocraft = true;
    /** Когда прочитаны данные «сеть сделает», по которым посчитан {@link #ordered}, мс; 0, если не учитывались. */
    public long orderedAt;
    public final Set<Category> stations = new LinkedHashSet<>();
    public int steps;
    public int maxDepth;
    /** Узлы вне раскрытых игроком веток: их общий предел MAX_NODES. */
    private int nodeCount;
    /** Узлы, раскрытые игроком: глубина под ними считается заново, а узлы считаются отдельно от остального дерева. */
    private final Set<Node> expanded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Сколько узлов уже обойдено под каждым раскрытым узлом: у каждой раскрытой ветки свой предел MAX_NODES / 2. */
    private final Map<Node, Integer> expandedCount = new java.util.IdentityHashMap<>();
    private Map<Object, Long> pool;
    /** Сколько из {@link #pool} лежит в инвентаре: оттуда берётся в первую очередь, остальное из хранилищ. */
    private Map<Object, Long> inventoryPool;
    /** Что сеть умеет делать, на время одного прохода: весь проход считает по одним данным. */
    private Set<Item> craftSet = Set.of();

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
    private final long slice;
    private boolean ready = true;
    private long deadline;
    private final Map<Object, Loaded> loaded = new HashMap<>();
    /** Выбор игрока по id ресурса: действует во всех узлах с этим ресурсом и переживает пересборку ветки. */
    private final Map<String, Pick> picks = new HashMap<>();

    /** Цель общей суммы: что, сколько и по кратчайшему ли пути. */
    private record Top(Stack stack, long amount, boolean shortest) {}

    /** Цели общей суммы по порядку доски; пусто у обычного дерева. */
    private final List<Top> tops = new ArrayList<>();
    /** Ресурсы, о маршруте которых цели спорят: id ресурса и номер цели, чей выбор взят. */
    private final Map<String, Integer> conflicts = new HashMap<>();
    /** Предел узлов: у общей суммы больше, по числу целей. */
    private final int nodeLimit;

    /** Корень общей суммы: не ресурс, а «все цели». */
    private static final class AllGoals extends Stack {
        private static final net.minecraft.resources.ResourceLocation ID = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ryscope", "all_goals");

        @Override
        public Object key() {
            return AllGoals.class;
        }

        @Override
        public String type() {
            return "ryscope:all_goals";
        }

        @Override
        public net.minecraft.resources.ResourceLocation id() {
            return ID;
        }

        @Override
        public net.minecraft.network.chat.Component name() {
            return net.minecraft.network.chat.Component.translatable("ryscope.tree.all_goals");
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics g, int x, int y) {}

        @Override
        public List<net.minecraft.network.chat.Component> tooltipLines() {
            return List.of(name());
        }

        @Override
        public List<String> tags() {
            return List.of();
        }

        @Override
        public boolean same(Stack other) {
            return other instanceof AllGoals;
        }

        @Override
        public Stack copy() {
            AllGoals s = new AllGoals();
            s.amount = amount;
            return s;
        }
    }

    public CraftTree(Stack target) {
        this(target, false);
    }

    /**
     * @param deferred строить по частям: каждый вызов {@link #recompute()} тратит на поиск рецептов не больше
     *                 нескольких миллисекунд, и пока {@link #ready()} возвращает false, дерево показывать нельзя.
     *                 Нужно экрану, чтобы он открывался сразу, а не после поиска по всем рецептам сборки.
     */
    public CraftTree(Stack target, boolean deferred) {
        this(target, deferred, SLICE_NANOS);
        recompute();
    }

    /**
     * Дерево по частям со своим бюджетом на один вызов {@link #recompute()}. Сам конструктор ничего не считает:
     * сначала можно перенять найденное ({@link #inherit}) и задать количество, первый шаг делает вызывающий.
     */
    public CraftTree(Stack target, long sliceNanos) {
        this(target, true, sliceNanos);
        ready = false;
    }

    private CraftTree(Stack target, boolean deferred, long sliceNanos) {
        this.root = new Node(target.withAmount(1), null);
        this.deferred = deferred;
        this.slice = sliceNanos;
        this.nodeLimit = MAX_NODES;
    }

    /**
     * Общая сумма по целям доски: синтетический корень, его дети это цели со своими количествами. Общий запас
     * инвентаря и хранилищ делится между ветками, общие промежуточные предметы считаются один раз. Маршруты целей
     * сливаются; если две цели выбрали для одного ресурса разные рецепты, берётся выбор цели выше на доске, а у узла
     * ставится {@link Node#routeFrom}. Строится по частям, как дерево цели: первый шаг делает вызывающий.
     */
    public CraftTree(List<Board.Goal> goals, long sliceNanos) {
        this.root = new Node(new AllGoals(), null);
        this.deferred = true;
        this.slice = sliceNanos;
        this.ready = false;
        this.nodeLimit = MAX_NODES * Math.max(1, Math.min(goals.size(), 4));
        Map<String, Integer> owner = new HashMap<>();
        for (int i = 0; i < goals.size(); i++) {
            Board.Goal goal = goals.get(i);
            Route route = goal.route;
            tops.add(new Top(goal.stack.withAmount(1), Math.max(1, Math.min(9999, goal.amount)), route != null && route.shortest));
            if (route == null) continue;
            for (Map.Entry<String, Pick> e : route.picks.entrySet()) {
                Pick known = picks.get(e.getKey());
                if (known == null) {
                    picks.put(e.getKey(), e.getValue());
                    owner.put(e.getKey(), i);
                } else if (!known.same(e.getValue())) {
                    // побеждает цель выше на доске
                    conflicts.putIfAbsent(e.getKey(), owner.get(e.getKey()));
                }
            }
        }
        root.options = List.of();
        root.choice = -1;
    }

    /** Это общая сумма целей, а не дерево одного предмета. */
    public boolean combined() {
        return !tops.isEmpty();
    }

    /** Сколько целей в общей сумме. */
    public int goalCount() {
        return tops.size();
    }

    /** Узел, который считается корнем своего дерева: сам корень или цель общей суммы. */
    private boolean isTop(Node node) {
        return node.parent == null || (node.parent == root && !tops.isEmpty());
    }

    /** Кратчайший ли путь для узла: у общей суммы флаг маршрута той цели, в чьей ветке узел. */
    private boolean shortestFor(Node node) {
        if (tops.isEmpty()) return shortest;
        Node n = node;
        while (n.parent != null && n.parent != root) n = n.parent;
        int index = root.children.indexOf(n);
        return index >= 0 && index < tops.size() ? tops.get(index).shortest() : shortest;
    }

    /** Перенимает уже найденные рецепты у другого дерева того же предмета: пересчёт идёт почти мгновенно. */
    public void inherit(CraftTree other) {
        loaded.putAll(other.loaded);
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
        deadline = deferred ? System.nanoTime() + slice : 0;
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
        ordered.clear();
        leaves.clear();
        incomplete = false;
        craftSet = autocraft && Config.get().treeAutocraft ? Ae2Terminal.craftableNow() : Set.of();
        orderedAt = craftSet.isEmpty() ? 0 : Ae2Terminal.craftableAt();
        stations.clear();
        steps = 0;
        maxDepth = 0;
        nodeCount = 0;
        expandedCount.clear();
        pool = new HashMap<>();
        inventoryPool = new HashMap<>();
        if (useInventory && InventoryView.tracking()) {
            for (Map.Entry<Item, Integer> e : InventoryView.snapshot().entrySet()) pool.put(e.getKey(), (long) e.getValue());
            inventoryPool.putAll(pool);
            // то, что игрок видел в хранилищах рядом, тоже считается имеющимся
            for (Map.Entry<Item, Long> e : Storages.counts().entrySet()) pool.merge(e.getKey(), e.getValue(), Long::sum);
        }
        root.needed = amount;
        if (!tops.isEmpty()) {
            visitGoals();
            return;
        }
        // сам целевой предмет из инвентаря не вычитаем: игрок спрашивает, как сделать ещё
        visit(root, false);
    }

    /** Корень общей суммы: цели по порядку доски делят один запас. */
    private void visitGoals() {
        ++nodeCount;
        root.crafts = 0;
        root.fromInventory = 0;
        List<Node> old = new ArrayList<>(root.children);
        root.children.clear();
        try {
            for (Top top : tops) {
                Node child = null;
                for (Node o : old) {
                    if (o.stack.same(top.stack())) {
                        child = o;
                        break;
                    }
                }
                if (child == null) child = new Node(top.stack(), root);
                child.needed = top.amount();
                root.children.add(child);
                // как в дереве одного предмета: саму цель из запасов не вычитаем
                visit(child, false);
            }
        } catch (Pending pending) {
            for (Node o : old) if (!root.children.contains(o)) root.children.add(o);
            throw pending;
        }
    }

    private Leaf leaf(Node node) {
        Leaf leaf = leaves.computeIfAbsent(node.stack.key(), k -> new Leaf(node.stack));
        leaf.need += node.needed;
        leaf.inventory += node.fromInventory - node.fromStorage;
        leaf.storage += node.fromStorage;
        return leaf;
    }

    private void visit(Node node, boolean takeFromInventory) {
        // узлы раскрытой ветки считаются по её собственному пределу: иначе добавка ушла бы веткам выше по обходу,
        // а кликнутая осталась бы обрезанной
        Node owner = expandedOwner(node);
        boolean overBudget = owner != null ? expandedCount.merge(owner, 1, Integer::sum) >= MAX_NODES / 2 : ++nodeCount >= nodeLimit;
        maxDepth = Math.max(maxDepth, node.depth);
        Object key = node.stack.key();
        node.fromInventory = 0;
        node.fromStorage = 0;
        node.ordered = false;
        node.routeFrom = null;
        if (takeFromInventory) {
            long have = pool.getOrDefault(key, 0L);
            long use = Math.min(have, node.needed);
            if (use > 0) {
                pool.put(key, have - use);
                node.fromInventory = use;
                // сначала то, что в инвентаре, потом запомненные хранилища
                long inInventory = inventoryPool.getOrDefault(key, 0L);
                long fromInventory = Math.min(inInventory, use);
                if (fromInventory > 0) inventoryPool.put(key, inInventory - fromInventory);
                node.fromStorage = use - fromInventory;
            }
        }
        long remaining = node.remaining();
        if (remaining == 0) {
            node.children.clear();
            node.crafts = 0;
            leaf(node);
            return;
        }
        // сеть делает этот предмет сама: остаток заказывается, ветка не раскрывается. Сам целевой предмет
        // раскрывается всегда: игрок спрашивает, из чего он делается
        if (!isTop(node) && key instanceof Item item && craftSet.contains(item)) {
            node.ordered = true;
            node.truncated = false;
            node.children.clear();
            node.crafts = 0;
            ordered.computeIfAbsent(key, k -> new Raw(node.stack)).need += remaining;
            leaf(node).ordered += remaining;
            return;
        }
        if (node.options == null) loadOptions(node);
        if (node.choice == -2) node.choice = initialChoice(node);
        if (!conflicts.isEmpty()) {
            Integer from = conflicts.get(node.stack.id().toString());
            if (from != null) node.routeFrom = tops.get(from).stack();
        }
        Option option = node.chosen();
        // у целей общей суммы глубина считается от них самих, а не от синтетического корня
        int base = Math.max(expandedBase(node), tops.isEmpty() ? 0 : 1);
        boolean cut = option != null && (node.depth - base >= MAX_DEPTH || overBudget);
        node.truncated = cut;
        if (cut && overBudget) incomplete = true;
        if (option == null || cut) {
            node.children.clear();
            node.crafts = 0;
            Raw r = raw.computeIfAbsent(key, k -> new Raw(node.stack));
            r.need += remaining;
            r.truncated |= cut;
            Leaf leaf = leaf(node);
            leaf.left += remaining;
            leaf.truncated |= cut;
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

    /** Выбор игрока для этого ресурса, если он есть среди рецептов узла, иначе обычные правила. */
    private int initialChoice(Node node) {
        Pick pick = picks.get(node.stack.id().toString());
        if (pick != null) {
            if (pick.raw()) return -1;
            for (int i = 0; i < node.options.size(); i++) if (pick.matches(node.options.get(i).recipe)) return i;
        }
        return shortestFor(node) ? shortestChoice(node) : defaultChoice(node);
    }

    /**
     * Маршрут дерева. {@code everyNode}: записать выбор каждого узла, а не только сделанный игроком;
     * так план повторит дерево, даже если сам выбрал бы иначе (он не учитывает инвентарь).
     */
    public Route route(boolean everyNode) {
        Route r = new Route();
        r.shortest = shortest;
        if (everyNode) collectPicks(root, r.picks);
        r.picks.putAll(picks);
        return r;
    }

    private static void collectPicks(Node node, Map<String, Pick> out) {
        if (node.options != null && node.choice >= -1) {
            Option o = node.chosen();
            out.put(node.stack.id().toString(), o == null ? Pick.RAW : Pick.of(o.recipe));
        }
        for (Node c : node.children) collectPicks(c, out);
    }

    /** Применяет маршрут: выбор пересчитается при следующем {@link #recompute()}. */
    public void apply(Route route) {
        if (route == null) return;
        picks.putAll(route.picks);
        shortest = route.shortest;
        root.choice = -2;
        root.children.clear();
        // дерево могло быть уже готово с выбором по умолчанию: пересчитать в ближайшем кадре
        ready = false;
    }

    /** Выбрать в узле этот рецепт, как будто игрок долистал до него стрелками. */
    public void choose(Node node, RecipeView recipe) {
        if (node.options == null) return;
        Pick pick = Pick.of(recipe);
        for (int i = 0; i < node.options.size(); i++) {
            if (!pick.matches(node.options.get(i).recipe)) continue;
            node.choice = i;
            picks.put(node.stack.id().toString(), Pick.of(node.options.get(i).recipe));
            node.children.clear();
            resetSameResource(node);
            recompute();
            return;
        }
    }

    /** Глубина ближайшего раскрытого игроком узла над этим (или его самого), 0, если такого нет. */
    private int expandedBase(Node node) {
        if (expanded.isEmpty()) return 0;
        for (Node n = node; n != null; n = n.parent) if (expanded.contains(n)) return n.depth;
        return 0;
    }

    /** Ближайший раскрытый игроком узел над этим (или он сам), null, если такого нет. */
    @org.jetbrains.annotations.Nullable
    private Node expandedOwner(Node node) {
        if (expanded.isEmpty()) return null;
        for (Node n = node; n != null; n = n.parent) if (expanded.contains(n)) return n;
        return null;
    }

    /** Раскрыть обрезанную ветку: под узлом снова доступна полная глубина и своя порция узлов. */
    public void expand(Node node) {
        if (!node.truncated) return;
        expanded.add(node);
        recompute();
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
     * Результат кэшируется на время жизни дерева и в общем кэше снимка; при построении по частям здесь же
     * проверяется бюджет времени.
     */
    private Loaded loaded(Stack stack) {
        Object key = stack.key();
        Loaded cached = loaded.get(key);
        if (cached != null) return cached;
        cached = Shared.get(stack);
        if (cached != null) {
            loaded.put(key, cached);
            return cached;
        }
        if (deadline != 0 && System.nanoTime() > deadline) throw Pending.INSTANCE;
        boolean complete = false;
        Set<Object> madeFromThis = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        // снимок может смениться посреди расчёта (его публикует поток индексации): в общий кеш результат
        // кладётся, только если снимок и источник остались теми, по которым он собран
        Snapshot snapAt = Indexer.snapshot();
        Foreign.Source srcAt = Foreign.get();
        try {
            // что делается из этого предмета
            for (Snapshot.Entry e : snapAt.consuming(stack)) {
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
                    // входы и выходы уже прочитаны; раскладка JEI нужна только для показа, а держать её
                    // у каждого кандидата каждого дерева дорого по памяти
                    r.release();
                }
            }
            complete = true;
        } catch (Throwable ignored) {
            // без рецептов узел считается сырьём
        }
        Loaded result = new Loaded(candidates, madeFromThis, String.join(", ", names));
        loaded.put(key, result);
        if (complete) Shared.put(stack, result, snapAt, srcAt);
        return result;
    }

    /**
     * Рецепты ресурсов, общие для всех деревьев: доски целей, дерева, планировщика, сравнения маршрутов. Без него
     * каждое дерево заново ищет рецепты и строит раскладки JEI тех же узлов (около 6 мс на узел в большой сборке).
     * Живёт, пока не сменились снимок индекса и источник рецептов. Ключ это ресурс целиком, с подтипом:
     * дерево книги с одними чарами не берёт рецепты книги с другими.
     */
    /** Забыть общий кеш рецептов: он держит рецепты JEI и снимок, а сам чистится только при следующем дереве. */
    public static void clearShared() {
        Shared.clear();
    }

    private static final class Shared {
        private static final int LIMIT = 4096;
        private static final Map<Stack, Loaded> MAP = new HashMap<>();
        private static Snapshot snapshot;
        private static Foreign.Source source;

        static synchronized Loaded get(Stack stack) {
            check();
            return MAP.get(stack);
        }

        static synchronized void put(Stack stack, Loaded value, Snapshot snapAt, Foreign.Source srcAt) {
            check();
            // собран по прежнему снимку: остаётся только в дереве, которое Board перестроит после переиндексации
            if (snapAt != snapshot || srcAt != source) return;
            if (MAP.size() >= LIMIT) MAP.clear();
            MAP.put(stack.withAmount(1), value);
        }

        static synchronized void clear() {
            MAP.clear();
            snapshot = null;
            source = null;
        }

        private static void check() {
            Snapshot snap = Indexer.snapshot();
            Foreign.Source foreign = Foreign.get();
            if (snap != snapshot || foreign != source) {
                MAP.clear();
                snapshot = snap;
                source = foreign;
            }
        }
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
        // рецепт по умолчанию, который задал игрок или сборка, главнее любых догадок
        RecipeRef preferred = RecipeDefaults.get(node.stack);
        if (preferred != null) {
            for (int i = 0; i < node.options.size(); i++) {
                if (preferred.matches(node.options.get(i).category, node.options.get(i).recipe)) return i;
            }
        }
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
        if (!isTop(node) && node.sources != null && !node.sources.isEmpty()) return -1;
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
        picks.clear();
        root.choice = -2;
        root.children.clear();
        recompute();
    }

    private int shortestChoice(Node node) {
        Set<Object> path = new HashSet<>();
        for (Node n = node.parent; n != null; n = n.parent) path.add(n.stack.key());
        boolean top = isTop(node);
        steps(node.stack, top ? 0 : 1, path, top);
        RecipeView best = bestMemo.get(node.stack.key());
        if (best == null) return !top && costMemo.containsKey(node.stack.key()) ? -1 : defaultChoice(node);
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
        // то, что закажет сеть, шагов игрока не требует
        if (!target && craftSet.contains(key)) return 0;
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

    /**
     * Выбор пишется по id ресурса и действует во всех его узлах (план, цель и новое дерево берут его отовсюду):
     * остальные узлы этого ресурса берут выбор заново, чтобы дерево сразу показывало то же, что они повторят.
     */
    private void resetSameResource(Node changed) {
        String id = changed.stack.id().toString();
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n != changed && n.options != null && n.stack.id().toString().equals(id)) {
                n.choice = -2;
                n.children.clear();
                continue;
            }
            queue.addAll(n.children);
        }
    }

    /** Следующий рецепт узла; после последнего идёт «сырьё». */
    public void cycleChoice(Node node, int dir) {
        if (node.options == null || node.options.isEmpty()) return;
        int n = node.options.size();
        int next = node.choice + dir;
        if (next >= n) next = -1;
        if (next < -1) next = n - 1;
        node.choice = next;
        picks.put(node.stack.id().toString(), next < 0 ? Pick.RAW : Pick.of(node.options.get(next).recipe));
        node.children.clear();
        resetSameResource(node);
        // выбор в дереве запоминается: следующее дерево и быстрый крафт возьмут тот же рецепт
        Option chosen = node.chosen();
        RecipeDefaults.remember(node.stack, chosen == null ? null : chosen.category, chosen == null ? null : chosen.recipe);
        recompute();
    }

    public void setAmount(long value) {
        amount = Math.max(1, Math.min(9999, value));
        recompute();
    }

    public int missingKinds() {
        return raw.size();
    }

    /**
     * Всё, от чего зависит выбор и раскрытие узлов помимо рецептов и инвентаря: настройка автокрафта и данные
     * «сеть сделает». Деревья целей и общая сумма пересобираются, когда это число меняется.
     */
    public static long inputsVersion() {
        long h = Config.get().treeAutocraft ? 1 : 0;
        h = h * 1_000_003L + Ae2Terminal.craftableVersion();
        return h;
    }
}
