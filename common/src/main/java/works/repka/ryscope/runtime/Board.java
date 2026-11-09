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
import works.repka.ryscope.ui.Draw;
import works.repka.ryscope.ui.theme.Theme;
import works.repka.ryscope.ui.theme.ThemeManager;

/**
 * Доска целей: закреплённые предметы, которые игрок собирается сделать.
 * Поверх мира показывается, чего ещё не хватает; список обновляется по мере сбора и переживает перезаход.
 */
public final class Board {
    public static final class Goal {
        public final Stack stack;
        public long amount;
        CraftTree tree;

        Goal(Stack stack, long amount) {
            this.stack = stack;
            this.amount = amount;
        }

        CraftTree tree() {
            if (tree == null) {
                tree = new CraftTree(stack);
                tree.setAmount(amount);
            }
            return tree;
        }
    }

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

    public static void tick() {
        if (GOALS.isEmpty() || ++age % 20 != 0) return;
        if (Indexer.state() != Indexer.State.READY) return;
        for (Goal g : GOALS) {
            try {
                g.tree().recompute();
            } catch (Throwable t) {
                RYScope.LOG.debug("Цель {} не пересчитана: {}", g.stack.id(), t.toString());
            }
        }
    }

    /** Снимок индекса сменился: старые деревья ссылаются на устаревшие рецепты. */
    public static void invalidate() {
        for (Goal g : GOALS) g.tree = null;
    }

    public static void renderHud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.get().boardHud || mc.options.hideGui || mc.screen != null) return;
        ensureLoaded();
        if (GOALS.isEmpty() || Indexer.state() != Indexer.State.READY) return;
        Theme theme = ThemeManager.get();
        Font font = mc.font;
        int x = 4, y = 4, w = 132;
        int shown = 0;
        for (Goal goal : GOALS) {
            if (shown++ >= 3) break;
            CraftTree tree;
            try {
                tree = goal.tree();
            } catch (Throwable t) {
                continue;
            }
            int lines = Math.min(4, tree.raw.size());
            int h = 22 + (tree.raw.isEmpty() ? 10 : lines * 10 + (tree.raw.size() > lines ? 8 : 0)) + 4;
            Draw.roundBox(g, x, y, w, h, theme.beveled() ? 0 : 5f, Draw.alpha(theme.c("hud.bg"), 0.86f), theme.c("hud.border"));
            goal.stack.render(g, x + 4, y + 3);
            String title = goal.amount + "× " + goal.stack.name().getString();
            int hudText = theme.beveled() ? 0xFFFFFFFF : theme.c("text.primary");
            int hudMuted = theme.beveled() ? 0xFFC0C0C0 : theme.c("text.muted");
            Draw.text(g, Draw.ellipsize(title, w - 28), x + 23, y + 7, hudText);
            int ly = y + 22;
            if (tree.raw.isEmpty()) {
                Draw.textScaled(g, Component.translatable("ryscope.board.ready").getString(), x + 5, ly, 0.75f, theme.c("state.ok"));
            } else {
                int i = 0;
                for (CraftTree.Raw r : tree.raw.values()) {
                    if (i++ >= lines) break;
                    g.pose().pushPose();
                    g.pose().translate(x + 5, ly - 1, 0);
                    g.pose().scale(0.5f, 0.5f, 1f);
                    r.stack.render(g, 0, 0);
                    g.pose().popPose();
                    String line = r.need + "× " + r.stack.name().getString();
                    Draw.textScaled(g, Draw.ellipsize(line, (int) ((w - 22) / 0.75f)), x + 16, ly, 0.75f, hudMuted);
                    ly += 10;
                }
                if (tree.raw.size() > lines) {
                    Draw.textScaled(g, Component.translatable("ryscope.board.more", tree.raw.size() - lines).getString(), x + 16, ly, 0.6f, hudMuted);
                }
            }
            y += h + 3;
        }
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
