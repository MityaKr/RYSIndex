package works.repka.ryscope.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.Stack;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;

/**
 * Доска целей: закреплённые предметы, которые игрок собирается сделать.
 * Поверх мира показывается, чего ещё не хватает; список обновляется по мере сбора и переживает перезаход.
 */
public final class Board {
    public static final class Goal {
        public final Stack stack;
        public long amount;
        /** Маршрут из дерева крафта, по которому цель закреплена; null, если выбор по умолчанию. */
        public CraftTree.Route route;
        /** Последнее готовое дерево: его и показываем, пока считается новое. */
        CraftTree tree;
        /** Дерево, которое сейчас строится по частям в {@link #tick()}. */
        CraftTree building;
        /** Снимок индекса сменился: показанное дерево устарело, нужно строить заново без перенятых рецептов. */
        boolean stale;
        /** Последняя попытка построить дерево упала: следующая не раньше чем через секунду. */
        boolean failed;

        Goal(Stack stack, long amount) {
            this.stack = stack;
            this.amount = amount;
        }

        /**
         * Последнее готовое дерево или null, пока первое ещё считается. Само ничего не строит: дерево большой цели
         * считается сотни миллисекунд и строится по частям в тике, а не при отрисовке.
         */
        @org.jetbrains.annotations.Nullable
        public CraftTree tree() {
            return tree;
        }
    }

    /** Сколько главного потока за тик отдаётся построению деревьев целей. */
    private static final long TICK_NANOS = 3_000_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Goal> GOALS = new ArrayList<>();
    private static boolean loaded;
    private static int age;
    /** Признак входных данных деревьев ({@link CraftTree#inputsVersion}), по которому построены нынешние деревья. */
    private static long inputs = Long.MIN_VALUE;

    /** Общая сумма по всем целям: последняя готовая и та, что строится. */
    @org.jetbrains.annotations.Nullable
    private static CraftTree combined;
    @org.jetbrains.annotations.Nullable
    private static CraftTree combinedBuilding;
    /** Список целей, их количества или маршруты поменялись: сумма строится заново. */
    private static boolean combinedDirty = true;
    /** Снимок индекса сменился: перенимать рецепты прежней суммы нельзя. */
    private static boolean combinedStale;
    private static boolean combinedFailed;
    /** Когда экран просил сумму последний раз, мс: без стикера-суммы она строится, только пока её смотрят. */
    private static long combinedWanted;

    private Board() {}

    public static List<Goal> goals() {
        ensureLoaded();
        return GOALS;
    }

    public static boolean contains(Stack stack) {
        ensureLoaded();
        for (Goal g : GOALS) if (g.stack.same(stack)) return true;
        return false;
    }

    public static Goal goal(Stack stack) {
        ensureLoaded();
        for (Goal g : GOALS) if (g.stack.same(stack)) return g;
        return null;
    }

    /** Закрепляет цель или обновляет количество и маршрут уже закреплённой. @return true, если цель новая. */
    public static boolean set(Stack stack, long amount, CraftTree.Route route) {
        ensureLoaded();
        Goal goal = goal(stack);
        boolean added = goal == null;
        if (added) {
            if (stack.item().isEmpty()) return false;
            goal = new Goal(stack.withAmount(1), 1);
            GOALS.add(goal);
        }
        goal.amount = Math.max(1, amount);
        goal.route = route == null || route.isEmpty() ? null : route;
        // дерево по прежнему маршруту или количеству больше не годится: строится заново в тике
        goal.tree = null;
        goal.building = null;
        goalsChanged();
        save();
        return added;
    }

    public static void remove(Stack stack) {
        ensureLoaded();
        if (GOALS.removeIf(g -> g.stack.same(stack))) {
            goalsChanged();
            save();
        }
    }

    /** Состав доски поменялся: общая сумма строится заново, прежняя видна, пока новая не готова. */
    private static void goalsChanged() {
        combinedDirty = true;
        combinedBuilding = null;
        if (GOALS.isEmpty()) combined = null;
    }

    /**
     * Общая сумма по всем целям ({@link CraftTree#CraftTree(List, long)}) или null, пока первая не построена или целей
     * нет. Строится в {@link #tick()} по тем же признакам устаревания, что деревья целей. Экран, который её
     * показывает, зовёт {@link #wantCombined()} каждый кадр.
     */
    @org.jetbrains.annotations.Nullable
    public static CraftTree combined() {
        return GOALS.isEmpty() ? null : combined;
    }

    /** Сколько ресурсов уже просмотрено для строящейся суммы: для индикатора загрузки. */
    public static int combinedExamined() {
        CraftTree b = combinedBuilding;
        return b != null ? b.examined() : combined != null ? combined.examined() : 0;
    }

    /** Экран показывает общую сумму: строить её, даже если стикер показывает цели по отдельности. */
    public static void wantCombined() {
        combinedWanted = System.currentTimeMillis();
    }

    private static boolean combinedNeeded() {
        return !GOALS.isEmpty() && ("sum".equals(Config.get().stickerMode) || System.currentTimeMillis() - combinedWanted < 3000);
    }

    /** @return true, если цель закреплена; false, если снята. */
    public static boolean toggle(Stack stack, long amount) {
        ensureLoaded();
        for (int i = 0; i < GOALS.size(); i++) {
            if (GOALS.get(i).stack.same(stack)) {
                GOALS.remove(i);
                goalsChanged();
                save();
                return false;
            }
        }
        if (stack.item().isEmpty()) return false;
        GOALS.add(new Goal(stack.withAmount(1), Math.max(1, amount)));
        goalsChanged();
        save();
        return true;
    }

    /**
     * Рантайм JEI перезапущен: маршруты целей не должны держать прежние объекты рецептов, а с ними старый рантайм.
     * Выбор сохраняется по id рецепта.
     */
    public static void releaseRecipes() {
        for (Goal g : GOALS) {
            if (g.route == null) continue;
            CraftTree.Route r = g.route.withoutRecipes();
            g.route = r.isEmpty() ? null : r;
        }
        // сумма держит рецепты и маршруты прежнего рантайма: строится заново
        combined = null;
        combinedBuilding = null;
        combinedDirty = true;
    }

    public static void reset() {
        GOALS.clear();
        loaded = false;
        combined = null;
        combinedBuilding = null;
        combinedDirty = true;
        combinedStale = false;
        combinedFailed = false;
    }

    /**
     * Деревья целей строятся по частям, не дольше {@link #TICK_NANOS} за тик на все цели. Раз в секунду готовое дерево
     * пересчитывается под инвентарь: новое дерево перенимает найденные рецепты у прежнего и обычно готово сразу,
     * а прежнее остаётся на экране, пока новое не достроено.
     */
    public static void tick() {
        if (GOALS.isEmpty()) return;
        boolean periodic = ++age % 20 == 0;
        if (Indexer.state() != Indexer.State.READY) return;
        long deadline = System.nanoTime() + TICK_NANOS;
        // сменились настройка автокрафта или данные «сеть сделает»: деревья строятся заново сразу, а не через секунду.
        // Найденные рецепты от этого не устаревают, поэтому перенимаются
        long now = CraftTree.inputsVersion();
        boolean inputsChanged = now != inputs;
        inputs = now;
        for (Goal g : GOALS) {
            try {
                if (inputsChanged) g.building = null;
                boolean due = g.stale || periodic || inputsChanged || (g.tree == null && !g.failed);
                if (g.building == null && due) {
                    CraftTree next = new CraftTree(g.stack, TICK_NANOS);
                    // рецепты прежнего дерева годятся, только пока не сменился снимок
                    if (g.tree != null && !g.stale) next.inherit(g.tree);
                    next.apply(g.route);
                    next.amount = Math.max(1, Math.min(9999, g.amount));
                    g.building = next;
                    g.stale = false;
                }
                if (g.building == null || System.nanoTime() > deadline) continue;
                g.building.recompute();
                if (g.building.ready()) {
                    g.tree = g.building;
                    g.building = null;
                    g.failed = false;
                }
            } catch (Throwable t) {
                g.building = null;
                g.failed = true;
                RYScope.LOG.debug("Цель {} не пересчитана: {}", g.stack.id(), t.toString());
            }
        }
        tickCombined(periodic, inputsChanged, deadline);
    }

    /**
     * Общая сумма строится после деревьев целей тем же способом: по частям, раз в секунду под инвентарь, сразу при
     * смене целей, снимка индекса, настройки автокрафта или данных «сеть сделает».
     */
    private static void tickCombined(boolean periodic, boolean inputsChanged, long deadline) {
        if (!combinedNeeded()) {
            combinedBuilding = null;
            return;
        }
        try {
            if (inputsChanged) combinedBuilding = null;
            boolean due = combinedDirty || combinedStale || periodic || inputsChanged || (combined == null && !combinedFailed);
            if (combinedBuilding == null && due) {
                CraftTree next = new CraftTree(GOALS, TICK_NANOS);
                if (!combinedStale) {
                    // найденные рецепты годятся, пока не сменился снимок: у прежней суммы и у деревьев целей
                    if (combined != null) next.inherit(combined);
                    for (Goal g : GOALS) if (g.tree != null && !g.stale) next.inherit(g.tree);
                }
                combinedBuilding = next;
                combinedDirty = false;
                combinedStale = false;
            }
            if (combinedBuilding == null || System.nanoTime() > deadline) return;
            combinedBuilding.recompute();
            if (combinedBuilding.ready()) {
                combined = combinedBuilding;
                combinedBuilding = null;
                combinedFailed = false;
            }
        } catch (Throwable t) {
            combinedBuilding = null;
            combinedFailed = true;
            RYScope.LOG.debug("Общая сумма целей не пересчитана: {}", t.toString());
        }
    }

    /**
     * Снимок индекса сменился: старые деревья ссылаются на устаревшие рецепты. Вызывается из потока индексации,
     * поэтому сам список целей трогается только в главном потоке.
     */
    public static void invalidate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            markStale();
        } else {
            mc.execute(Board::markStale);
        }
    }

    private static void markStale() {
        for (Goal g : GOALS) {
            g.stale = true;
            g.building = null;
        }
        combinedStale = true;
        combinedBuilding = null;
    }

    public static void renderHud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.get().boardHud || mc.options.hideGui || mc.screen != null) return;
        ensureLoaded();
        if (GOALS.isEmpty() || Indexer.state() != Indexer.State.READY) return;
        Theme theme = ThemeManager.get();
        Font font = mc.font;
        // внизу слева поверх мира чат, поэтому здесь столбец; под линзой остаётся место
        works.repka.ryscope.ui.Stickers.drawAnchored(g, theme, 3);
    }

    private static Path file() {
        return Config.dir().resolve(Profiles.file("board"));
    }

    private static void ensureLoaded() {
        Minecraft mc = Minecraft.getInstance();
        if (loaded || mc.level == null) return;
        loaded = true;
        GOALS.clear();
        Path file = file();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonArray array = GSON.fromJson(r, JsonArray.class);
            DynamicOps<JsonElement> ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                long amount = o.has("amount") ? o.get("amount").getAsLong() : 1;
                CraftTree.Route route = o.has("route") ? CraftTree.Route.fromJson(o.getAsJsonObject("route")) : null;
                ItemStack.CODEC.parse(ops, o.get("item")).result().ifPresent(s -> {
                    if (s.isEmpty()) return;
                    Goal goal = new Goal(new ItemRes(s), amount);
                    goal.route = route;
                    GOALS.add(goal);
                });
            }
            goalsChanged();
        } catch (Exception e) {
            RYScope.LOG.warn("Не удалось прочитать доску целей: {}", e.toString());
        }
    }

    public static void save() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            DynamicOps<JsonElement> ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            JsonArray array = new JsonArray();
            for (Goal g : GOALS) {
                ItemStack item = g.stack.item();
                if (item.isEmpty()) continue;
                JsonObject o = new JsonObject();
                o.addProperty("amount", g.amount);
                ItemStack.CODEC.encodeStart(ops, item).result().ifPresent(j -> o.add("item", j));
                if (g.route != null && !g.route.isEmpty()) o.add("route", g.route.toJson());
                if (o.has("item")) array.add(o);
            }
            Files.createDirectories(Config.dir());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(array, w);
            }
        } catch (Exception e) {
            RYScope.LOG.warn("Не удалось сохранить доску целей: {}", e.toString());
        }
    }
}
