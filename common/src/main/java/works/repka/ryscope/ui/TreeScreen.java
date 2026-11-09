package works.repka.ryscope.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscope.client.Keys;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Board;
import works.repka.ryscope.runtime.Bookmarks;
import works.repka.ryscope.runtime.CraftTree;
import works.repka.ryscope.runtime.CraftTree.Node;
import works.repka.ryscope.runtime.InventoryView;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.ui.theme.Theme;
import works.repka.ryscope.ui.theme.ThemeManager;

/** Дерево крафта: раскрывающийся список «что из чего», с учётом инвентаря и итогом по сырью. */
public final class TreeScreen extends Screen {
    private static final int ROW = 20;
    private static final int PAD = 10;
    private static final int HEADER = 30;
    private static final int FOOTER = 46;

    private record Row(Node node, int y) {}

    @Nullable
    private final Screen parent;
    private final CraftTree tree;
    private int px, py, pw, ph;
    private float scroll, target;
    private final List<Row> rows = new ArrayList<>();
    private int refresh;
    private final int[] goalRect = new int[3];

    private TreeScreen(@Nullable Screen parent, Stack stack) {
        super(Component.translatable("ryscope.tree.title"));
        this.parent = parent;
        this.tree = new CraftTree(stack, true);
    }

    public static void open(Stack stack) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen;
        if (parent instanceof TreeScreen ts) parent = ts.parent;
        mc.setScreen(new TreeScreen(parent, stack));
    }

    private void flatten(Node node, List<Node> out) {
        out.add(node);
        if (node.collapsed) return;
        for (Node c : node.children) flatten(c, out);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, ThemeManager.get().c("overlay"));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme theme = ThemeManager.get();
        Font font = Minecraft.getInstance().font;
        renderBackground(g, mx, my, partialTick);
        pw = Math.min(width - 12, 400);
        ph = height - 12;
        px = (width - pw) / 2;
        py = 6;
        theme.panel(g, px, py, pw, ph);

        // дерево строится по частям, по несколько миллисекунд за кадр: окно открывается сразу
        if (!tree.ready()) tree.recompute();
        if (!tree.ready()) {
            rows.clear();
            tree.root.stack.render(g, px + PAD, py + PAD - 1);
            Draw.text(g, Draw.ellipsize(tree.root.stack.name().getString(), pw - PAD * 2 - 28), px + PAD + 22, py + PAD + 3, theme.c("text.primary"));
            String loading = Component.translatable("ryscope.tree.loading").getString();
            String progress = Component.translatable("ryscope.tree.loading.progress", tree.examined()).getString();
            int cy = py + ph / 2 - 12;
            Draw.textCentered(g, loading, px + pw / 2, cy, theme.c("text.body"));
            int bw = Math.min(160, pw - PAD * 4), bx = px + (pw - bw) / 2;
            Draw.round(g, bx, cy + 14, bw, 2, 1f, theme.c("slot.bg"));
            // бегущий отрезок: сколько всего ресурсов придётся просмотреть, заранее неизвестно
            float phase = (System.currentTimeMillis() % 1200L) / 1200f;
            int seg = bw / 4, sx = bx + Math.round((bw - seg) * (phase < 0.5f ? phase * 2 : 2 - phase * 2));
            Draw.round(g, sx, cy + 14, seg, 2, 1f, theme.c("accent"));
            Draw.textScaled(g, progress, px + pw / 2f - font.width(progress) * 0.375f, cy + 21, 0.75f, theme.c("text.muted"));
            return;
        }

        // --- шапка ---
        tree.root.stack.render(g, px + PAD, py + PAD - 1);
        String title = tree.root.stack.name().getString();
        Draw.text(g, Draw.ellipsize(title, toolRects(font)[0] - (px + PAD + 22) - 6), px + PAD + 22, py + PAD - 1, theme.c("text.primary"));
        String sub = Component.translatable("ryscope.tree.summary", tree.steps, tree.maxDepth).getString();
        Draw.textScaled(g, sub, px + PAD + 22, py + PAD + 9, 0.75f, theme.c("text.muted"));
        // планировщик и сравнение маршрутов
        int[] tools = toolRects(font);
        String planLabel = Component.translatable("ryscope.tree.plan").getString();
        String cmpLabel = Component.translatable("ryscope.tree.compare").getString();
        theme.button(g, tools[0], py + PAD - 2, tools[1], 14, over(mx, my, tools[0], py + PAD - 2, tools[1], 14), false);
        Draw.text(g, planLabel, tools[0] + 5, py + PAD + 1, theme.c("text.body"));
        theme.button(g, tools[2], py + PAD - 2, tools[3], 14, over(mx, my, tools[2], py + PAD - 2, tools[3], 14), false);
        Draw.text(g, cmpLabel, tools[2] + 5, py + PAD + 1, theme.c("text.body"));
        // количество
        int ax = px + pw - PAD - 64, ay = py + PAD - 2;
        theme.button(g, ax, ay, 14, 14, over(mx, my, ax, ay, 14, 14), false);
        Draw.rect(g, ax + 4, ay + 6, 6, 2, theme.c("text.body"));
        String amount = Long.toString(tree.amount);
        Draw.textCentered(g, amount, ax + 32, ay + 3, theme.c("text.primary"));
        theme.button(g, ax + 50, ay, 14, 14, over(mx, my, ax + 50, ay, 14, 14), false);
        Draw.rect(g, ax + 54, ay + 6, 6, 2, theme.c("text.body"));
        Draw.rect(g, ax + 56, ay + 4, 2, 6, theme.c("text.body"));

        // --- строки ---
        List<Node> nodes = new ArrayList<>();
        flatten(tree.root, nodes);
        int bodyY = py + HEADER, bodyH = ph - HEADER - FOOTER;
        float max = Math.max(0, nodes.size() * ROW - bodyH);
        target = Mth.clamp(target, 0, max);
        scroll += (target - scroll) * 0.35f;
        if (Math.abs(target - scroll) < 0.5f) scroll = target;
        rows.clear();
        g.enableScissor(px + 2, bodyY, px + pw - 2, bodyY + bodyH);
        Node hovered = null;
        for (int i = 0; i < nodes.size(); i++) {
            int y = bodyY + i * ROW - Math.round(scroll);
            if (y + ROW < bodyY || y > bodyY + bodyH) continue;
            Node n = nodes.get(i);
            rows.add(new Row(n, y));
            boolean hov = my >= bodyY && my < bodyY + bodyH && over(mx, my, px + 4, y, pw - 8, ROW);
            if (hov) {
                hovered = n;
                Draw.round(g, px + 5, y, pw - 10, ROW - 1, theme.r("slot.radius"), Draw.alpha(theme.c("slot.hover"), 0.35f));
            }
            int x = px + PAD + n.depth * 12;
            // направляющие уровней
            for (int d = 1; d <= n.depth; d++) {
                Draw.rect(g, px + PAD + d * 12 - 7, y, 1, ROW, Draw.alpha(theme.c("text.dim"), 0.35f));
            }
            if (!n.children.isEmpty()) {
                int c = theme.c("text.muted");
                if (n.collapsed) Draw.chevron(g, x, y + 6, true, c);
                else for (int k = 0; k < 4; k++) Draw.rect(g, x - 1 + k, y + 7 + k, 7 - k * 2, 1, c);
            }
            n.stack.render(g, x + 9, y + 1);
            String label = n.needed + "× " + n.stack.name().getString();
            int right = px + pw - PAD;
            // правая часть: состояние и выбор рецепта
            String state;
            int stateColor;
            if (n.parent != null && n.satisfied()) {
                state = Component.translatable("ryscope.tree.have").getString();
                stateColor = theme.c("state.ok");
            } else if (n.chosen() == null) {
                state = Component.translatable(n.parent == null ? "ryscope.tree.no_recipe" : "ryscope.tree.raw").getString();
                if (!n.sources.isEmpty()) state += " · " + Draw.ellipsize(n.sources, 110);
                stateColor = theme.c("state.warn");
            } else if (n.fromInventory > 0) {
                state = Component.translatable("ryscope.tree.have_part", n.fromInventory).getString();
                stateColor = theme.c("state.ok");
            } else {
                state = "";
                stateColor = 0;
            }
            if (n.options != null && n.options.size() > 0 && !(n.parent != null && n.satisfied())) {
                String sw = (n.choice + 1) + "/" + n.options.size();
                int sww = font.width(sw);
                int sx = right - sww - 22;
                boolean h1 = over(mx, my, sx - 2, y + 3, 10, 13), h2 = over(mx, my, sx + sww + 12, y + 3, 10, 13);
                Draw.chevron(g, sx, y + 6, false, h1 ? theme.c("accent") : theme.c("text.dim"));
                Draw.text(g, sw, sx + 9, y + 6, theme.c("text.muted"));
                Draw.chevron(g, sx + sww + 14, y + 6, true, h2 ? theme.c("accent") : theme.c("text.dim"));
                right = sx - 6;
                CraftTree.Option opt = n.chosen();
                if (opt != null) {
                    try {
                        g.pose().pushPose();
                        g.pose().translate(right - 13, y + 4, 0);
                        g.pose().scale(0.75f, 0.75f, 1f);
                        opt.category.renderIcon(g, 0, 0);
                        g.pose().popPose();
                    } catch (Throwable ignored) {
                        g.pose().popPose();
                    }
                    right -= 17;
                }
            }
            if (!state.isEmpty()) {
                int sw = Math.round(font.width(state) * 0.75f);
                Draw.textScaled(g, state, right - sw, y + 7, 0.75f, stateColor);
                right -= sw + 6;
            }
            int textColor = n.parent != null && n.satisfied() ? theme.c("text.muted") : theme.c("text.body");
            Draw.text(g, Draw.ellipsize(label, right - (x + 29)), x + 29, y + 6, textColor);
        }
        g.disableScissor();

        // --- итог по сырью ---
        int fy = py + ph - FOOTER;
        Draw.rect(g, px + PAD, fy, pw - PAD * 2, 1, Draw.alpha(theme.c("text.dim"), 0.4f));
        Stack rawHover = null;
        if (tree.raw.isEmpty()) {
            Draw.text(g, Component.translatable("ryscope.tree.all_have").getString(), px + PAD, fy + 8, theme.c("state.ok"));
        } else {
            Draw.textScaled(g, Component.translatable("ryscope.tree.to_gather").getString(), px + PAD, fy + 5, 0.75f, theme.c("text.muted"));
            int rx = px + PAD;
            for (CraftTree.Raw r : tree.raw.values()) {
                if (rx + 20 > px + pw - PAD - 110) {
                    Draw.text(g, "…", rx + 2, fy + 20, theme.c("text.dim"));
                    break;
                }
                theme.slot(g, rx, fy + 14, 20, over(mx, my, rx, fy + 14, 20, 20));
                r.stack.render(g, rx + 2, fy + 16);
                String count = r.need >= 1000 ? (r.need / 1000) + "k" : Long.toString(r.need);
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                Draw.textScaled(g, count, rx + 19 - font.width(count) * 0.75f, fy + 28, 0.75f, 0xFFFFFFFF);
                g.pose().popPose();
                if (over(mx, my, rx, fy + 14, 20, 20)) rawHover = r.stack;
                rx += 23;
            }
            // кнопка «в закладки»
            String pin = Component.translatable("ryscope.tree.pin").getString();
            int bw = font.width(pin) + 12, bx = px + pw - PAD - bw, bby = fy + 4;
            theme.button(g, bx, bby, bw, 13, over(mx, my, bx, bby, bw, 13), false);
            Draw.text(g, pin, bx + 6, bby + 3, theme.c("text.body"));
        }
        if (!InventoryView.tracking()) {
            Draw.textScaled(g, Component.translatable("ryscope.tree.creative").getString(), px + PAD, py + ph - 9, 0.75f, theme.c("text.dim"));
        } else if (works.repka.ryscope.runtime.Storages.inRange() > 0) {
            Draw.textScaled(g, Component.translatable("ryscope.tree.storages", works.repka.ryscope.runtime.Storages.inRange()).getString(),
                px + PAD, py + ph - 9, 0.75f, theme.c("text.dim"));
        }
        boolean pinned = Board.contains(tree.root.stack);
        String goal = Component.translatable(pinned ? "ryscope.tree.unpin_goal" : "ryscope.tree.pin_goal").getString();
        int gw = font.width(goal) + 12, gx = px + pw - PAD - gw, gy = py + ph - 13 - 2;
        if (tree.raw.isEmpty()) gy = fy + 17;
        goalRect[0] = gx;
        goalRect[1] = gy;
        goalRect[2] = gw;
        theme.button(g, gx, gy, gw, 13, over(mx, my, gx, gy, gw, 13), pinned);
        Draw.text(g, goal, gx + 6, gy + 3, pinned ? theme.c("accent.text") : theme.c("text.body"));

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        if (rawHover != null) rawHover.renderTooltip(g, mx, my);
        else if (hovered != null && over(mx, my, px + PAD + hovered.depth * 12 + 9, 0, 18, height)) hovered.stack.renderTooltip(g, mx, my);
        g.pose().popPose();
        Overlay.renderToast(this, g);
    }

    /** Кнопки «План» и «Сравнить» слева от счётчика количества: x и ширина каждой. */
    private int[] toolRects(Font font) {
        int planW = font.width(Component.translatable("ryscope.tree.plan").getString()) + 10;
        int cmpW = font.width(Component.translatable("ryscope.tree.compare").getString()) + 10;
        int cmpX = px + pw - PAD - 64 - 8 - cmpW;
        return new int[] {cmpX - 4 - planW, planW, cmpX, cmpW};
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Nullable
    private Node nodeAt(double mx, double my) {
        int bodyY = py + HEADER, bodyH = ph - HEADER - FOOTER;
        if (my < bodyY || my >= bodyY + bodyH) return null;
        for (Row r : rows) if (my >= r.y && my < r.y + ROW && mx >= px + 4 && mx < px + pw - 4) return r.node;
        return null;
    }

    @Override
    public void tick() {
        // инвентарь меняется, пока игрок собирает: пересчитываем раз в секунду
        if (tree.ready() && ++refresh % 20 == 0) tree.recompute();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!tree.ready()) return true;
        Font font = Minecraft.getInstance().font;
        int ax = px + pw - PAD - 64, ay = py + PAD - 2;
        int[] tools = toolRects(font);
        if (over(mx, my, tools[0], ay, tools[1], 14)) {
            PlannerScreen.open(tree.root.stack);
            return true;
        }
        if (over(mx, my, tools[2], ay, tools[3], 14)) {
            CompareScreen.open(tree.root.stack);
            return true;
        }
        int step = hasShiftDown() ? 8 : 1;
        if (over(mx, my, ax, ay, 14, 14)) {
            tree.setAmount(tree.amount - step);
            return true;
        }
        if (over(mx, my, ax + 50, ay, 14, 14)) {
            tree.setAmount(tree.amount + step);
            return true;
        }
        int fy = py + ph - FOOTER;
        if (over(mx, my, goalRect[0], goalRect[1], goalRect[2], 13)) {
            boolean added = Board.toggle(tree.root.stack, tree.amount);
            Overlay.toast(Component.translatable(added ? "ryscope.board.added" : "ryscope.board.removed").getString());
            return true;
        }
        if (!tree.raw.isEmpty()) {
            String pin = Component.translatable("ryscope.tree.pin").getString();
            int bw = font.width(pin) + 12, bx = px + pw - PAD - bw, bby = fy + 4;
            if (over(mx, my, bx, bby, bw, 13)) {
                int added = 0;
                for (CraftTree.Raw r : tree.raw.values()) {
                    if (!Bookmarks.contains(r.stack) && Bookmarks.toggle(r.stack)) added++;
                }
                Overlay.toast(Component.translatable("ryscope.tree.pinned", added).getString());
                return true;
            }
            int rx = px + PAD;
            for (CraftTree.Raw r : tree.raw.values()) {
                if (over(mx, my, rx, fy + 14, 20, 20)) {
                    RecipeScreen.open(r.stack, button == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
                    return true;
                }
                rx += 23;
            }
        }
        Node n = nodeAt(mx, my);
        if (n != null) {
            int right = px + pw - PAD;
            if (n.options != null && !n.options.isEmpty() && !(n.parent != null && n.satisfied())) {
                String sw = (n.choice + 1) + "/" + n.options.size();
                int sww = font.width(sw);
                int sx = right - sww - 22;
                if (over(mx, my, sx - 2, 0, 10, height)) {
                    tree.cycleChoice(n, -1);
                    return true;
                }
                if (over(mx, my, sx + sww + 12, 0, 10, height)) {
                    tree.cycleChoice(n, 1);
                    return true;
                }
            }
            int x = px + PAD + n.depth * 12;
            if (over(mx, my, x + 9, 0, 18, height)) {
                RecipeScreen.open(n.stack, button == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
                return true;
            }
            if (!n.children.isEmpty()) n.collapsed = !n.collapsed;
            return true;
        }
        if (!over(mx, my, px, py, pw, ph)) {
            onClose();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (!tree.ready()) return true;
        int ax = px + pw - PAD - 64, ay = py + PAD - 2;
        if (over(mx, my, ax, ay - 2, 64, 18)) {
            tree.setAmount(tree.amount + (scrollY > 0 ? 1 : -1) * (hasShiftDown() ? 8 : 1));
            return true;
        }
        target -= (float) scrollY * ROW * 2;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE || Keys.matches(Keys.BACK, key, scan) || Minecraft.getInstance().options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        if (!tree.ready()) return true;
        Node n = nodeAt(Overlay.mouseX(), Overlay.mouseY());
        if (n != null) {
            if (Keys.matches(Keys.RECIPES, key, scan)) return RecipeScreen.open(n.stack, Lookup.Mode.RECIPES);
            if (Keys.matches(Keys.USES, key, scan)) return RecipeScreen.open(n.stack, Lookup.Mode.USES);
            if (Keys.matches(Keys.BOOKMARK, key, scan)) {
                Bookmarks.toggle(n.stack);
                return true;
            }
            if (key == GLFW.GLFW_KEY_LEFT) {
                tree.cycleChoice(n, -1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_RIGHT) {
                tree.cycleChoice(n, 1);
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) {
            tree.setAmount(tree.amount + 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
            tree.setAmount(tree.amount - 1);
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Категория выбранного рецепта узла под курсором: для подсказок. */
    @Nullable
    Category hoveredCategory() {
        Node n = nodeAt(Overlay.mouseX(), Overlay.mouseY());
        return n == null || n.chosen() == null ? null : n.chosen().category;
    }
}
