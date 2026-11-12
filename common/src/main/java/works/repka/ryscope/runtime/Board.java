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

    /** @return true, если цель закреплена; false, если снята. */
    public static boolean toggle(Stack stack, long amount) {
        ensureLoaded();
        for (int i = 0; i < GOALS.size(); i++) {
            if (GOALS.get(i).stack.same(stack)) {
                GOALS.remove(i);
                save();
                return false;
            }
        }
        if (stack.item().isEmpty()) return false;
        GOALS.add(new Goal(stack.withAmount(1), Math.max(1, amount)));
        save();
        return true;
    }

    public static void reset() {
        GOALS.clear();
        loaded = false;
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
        for (Goal g : GOALS) {
            try {
                boolean due = g.stale || periodic || (g.tree == null && !g.failed);
                if (g.building == null && due) {
                    CraftTree next = new CraftTree(g.stack, TICK_NANOS);
                    // рецепты прежнего дерева годятся, только пока не сменился снимок
                    if (g.tree != null && !g.stale) next.inherit(g.tree);
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
                ItemStack.CODEC.parse(ops, o.get("item")).result().ifPresent(s -> {
                    if (!s.isEmpty()) GOALS.add(new Goal(new ItemRes(s), amount));
                });
            }
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
