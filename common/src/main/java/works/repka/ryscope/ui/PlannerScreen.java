package works.repka.ryscope.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscope.client.Keys;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Board;
import works.repka.ryscope.runtime.CraftTree;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.Planner;
import works.repka.ryscope.runtime.Units;
import works.repka.ryscope.ui.theme.Theme;
import works.repka.ryscope.ui.theme.ThemeManager;

/** Планировщик производства: сколько машин нужно, чтобы делать N штук в час, и где узкое место. */
public final class PlannerScreen extends Screen {
    private static final int ROW = 20;
    private static final int PAD = 10;
    private static final int HEADER = 62;
    private static final int FOOTER = 50;

    @Nullable
    private final Screen parent;
    private final Planner planner;
    private int px, py, pw, ph;
    private float scroll;

    private PlannerScreen(@Nullable Screen parent, Stack stack) {
        super(Component.translatable("ryscope.planner.title"));
        this.parent = parent;
        this.planner = new Planner(stack);
    }

    public static void open(Stack stack) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new PlannerScreen(mc.screen, stack));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, ThemeManager.get().c("overlay"));
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String modeLabel(Planner.Mode mode) {
        return Component.translatable("ryscope.planner.mode." + mode.name().toLowerCase(java.util.Locale.ROOT)).getString();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme theme = ThemeManager.get();
        Font font = Minecraft.getInstance().font;
        renderBackground(g, mx, my, partialTick);
        pw = Math.min(width - 12, 430);
        ph = height - 12;
        px = (width - pw) / 2;
        py = 6;
        theme.panel(g, px, py, pw, ph);

        // --- шапка: цель и темп ---
        Stack target = planner.tree.root.stack;
        target.render(g, px + PAD, py + PAD - 1);
        Draw.text(g, Draw.ellipsize(target.name().getString(), pw - 170), px + PAD + 22, py + PAD - 1, theme.c("text.primary"));
        Draw.textScaled(g, title.getString(), px + PAD + 22, py + PAD + 9, 0.75f, theme.c("text.muted"));
        int ax = px + pw - PAD - 108, ay = py + PAD - 2;
        theme.button(g, ax, ay, 14, 14, over(mx, my, ax, ay, 14, 14), false);
        Draw.rect(g, ax + 4, ay + 6, 6, 2, theme.c("text.body"));
        String rate = Component.translatable("ryscope.planner.rate", planner.rate).getString();
        Draw.textCentered(g, rate, ax + 54, ay + 3, theme.c("text.primary"));
        theme.button(g, ax + 94, ay, 14, 14, over(mx, my, ax + 94, ay, 14, 14), false);
        Draw.rect(g, ax + 98, ay + 6, 6, 2, theme.c("text.body"));
        Draw.rect(g, ax + 100, ay + 4, 2, 6, theme.c("text.body"));

        // --- режимы ---
        int mxp = px + PAD, myp = py + PAD + 22;
        for (Planner.Mode mode : Planner.Mode.values()) {
            String label = modeLabel(mode);
            int w = font.width(label) + 12;
            boolean active = planner.mode == mode;
            theme.tab(g, mxp, myp, w, 14, active, over(mx, my, mxp, myp, w, 14));
            Draw.text(g, label, mxp + 6, myp + 3, active ? theme.c("tab.active.fg") : theme.c("tab.inactive.fg"));
            mxp += w + 3;
        }

        // --- заголовки столбцов ---
        int c4 = px + pw - PAD, c3 = c4 - 70, c2 = c3 - 48, c1 = c2 - 44;
        int hy = py + HEADER - 12;
        rightScaled(g, tr("ryscope.planner.col.energy"), c4, hy, theme.c("text.muted"));
        rightScaled(g, tr("ryscope.planner.col.load"), c3, hy, theme.c("text.muted"));
        rightScaled(g, tr("ryscope.planner.col.machines"), c2, hy, theme.c("text.muted"));
        rightScaled(g, tr("ryscope.planner.col.crafts"), c1, hy, theme.c("text.muted"));
        Draw.textScaled(g, tr("ryscope.planner.col.step"), px + PAD, hy, 0.75f, theme.c("text.muted"));

        // --- строки ---
        int bodyY = py + HEADER, bodyH = ph - HEADER - FOOTER;
        float max = Math.max(0, planner.lines.size() * ROW - bodyH);
        scroll = Mth.clamp(scroll, 0, max);
        g.enableScissor(px + 2, bodyY, px + pw - 2, bodyY + bodyH);
        Stack hovered = null;
        for (int i = 0; i < planner.lines.size(); i++) {
            int y = bodyY + i * ROW - Math.round(scroll);
            if (y + ROW < bodyY || y > bodyY + bodyH) continue;
            Planner.Line line = planner.lines.get(i);
            boolean hov = my >= bodyY && my < bodyY + bodyH && over(mx, my, px + 4, y, pw - 8, ROW);
            if (hov) {
                Draw.round(g, px + 5, y, pw - 10, ROW - 1, theme.r("slot.radius"), Draw.alpha(theme.c("slot.hover"), 0.35f));
                hovered = line.output;
            }
            if (line == planner.bottleneck && planner.lines.size() > 1) {
                Draw.rect(g, px + 5, y + 2, 2, ROW - 5, theme.c("state.warn"));
            }
            try {
                g.pose().pushPose();
                g.pose().translate(px + PAD, y + 4, 0);
                g.pose().scale(0.75f, 0.75f, 1f);
                line.category.renderIcon(g, 0, 0);
                g.pose().popPose();
            } catch (Throwable ignored) {
                g.pose().popPose();
            }
            line.output.render(g, px + PAD + 16, y + 1);
            Draw.text(g, Draw.ellipsize(line.output.name().getString(), c1 - 44 - (px + PAD + 36)), px + PAD + 36, y + 6, theme.c("text.body"));
            Draw.text(g, Units.compact(line.craftsPerHour), c1 - font.width(Units.compact(line.craftsPerHour)), y + 6, theme.c("text.body"));
            if (line.timed()) {
                String machines = Integer.toString(line.machines);
                Draw.text(g, machines, c2 - font.width(machines), y + 6, theme.c("text.primary"));
                String load = Math.round(line.load * 100) + "%";
                int loadColor = line.load > 0.9 ? theme.c("state.warn") : theme.c("text.muted");
                Draw.text(g, load, c3 - font.width(load), y + 6, loadColor);
                String energy = line.energyPerTick > 0 ? Units.energyPerTick(line.energyPerTick) : "—";
                Draw.text(g, energy, c4 - font.width(energy), y + 6, theme.c("text.muted"));
            } else {
                String manual = tr("ryscope.planner.manual");
                Draw.textScaled(g, manual, c4 - font.width(manual) * 0.75f, y + 7, 0.75f, theme.c("text.dim"));
            }
        }
        g.disableScissor();

        // --- итог ---
        int fy = py + ph - FOOTER;
        Draw.rect(g, px + PAD, fy, pw - PAD * 2, 1, Draw.alpha(theme.c("text.dim"), 0.4f));
        String total = Component.translatable("ryscope.planner.total", planner.totalMachines,
            planner.totalEnergy > 0 ? Units.energyPerTick(planner.totalEnergy) : "—").getString();
        Draw.text(g, total, px + PAD, fy + 6, theme.c("text.body"));
        if (planner.bottleneck != null && planner.lines.size() > 1) {
            String b = Component.translatable("ryscope.planner.bottleneck", planner.bottleneck.output.name().getString()).getString();
            Draw.textScaled(g, Draw.ellipsize(b, (int) ((pw - PAD * 2 - font.width(total) - 14) / 0.75f)),
                px + PAD + font.width(total) + 10, fy + 7, 0.75f, theme.c("state.warn"));
        }
        // сырьё в час
        int rx = px + PAD;
        Draw.textScaled(g, tr("ryscope.planner.raw"), rx, fy + 19, 0.75f, theme.c("text.muted"));
        Stack rawHover = null;
        int ry = fy + 27;
        for (CraftTree.Raw r : planner.tree.raw.values()) {
            if (rx + 20 > px + pw - PAD - 100) break;
            theme.slot(g, rx, ry, 20, over(mx, my, rx, ry, 20, 20));
            r.stack.render(g, rx + 2, ry + 2);
            String count = Units.compact(r.need);
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            Draw.textScaled(g, count, rx + 19 - font.width(count) * 0.6f, ry + 14, 0.6f, 0xFFFFFFFF);
            g.pose().popPose();
            if (over(mx, my, rx, ry, 20, 20)) rawHover = r.stack;
            rx += 23;
        }
        boolean pinned = Board.contains(target);
        String goal = tr(pinned ? "ryscope.tree.unpin_goal" : "ryscope.tree.pin_goal");
        int gw = font.width(goal) + 12, gx = px + pw - PAD - gw, gy = fy + 30;
        theme.button(g, gx, gy, gw, 13, over(mx, my, gx, gy, gw, 13), pinned);
        Draw.text(g, goal, gx + 6, gy + 3, pinned ? theme.c("accent.text") : theme.c("text.body"));

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        if (rawHover != null) rawHover.renderTooltip(g, mx, my);
        else if (hovered != null && over(mx, my, px + PAD + 16, 0, 18, height)) hovered.renderTooltip(g, mx, my);
        g.pose().popPose();
        Overlay.renderToast(this, g);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static void rightScaled(GuiGraphics g, String text, int right, int y, int color) {
        Font font = Minecraft.getInstance().font;
        Draw.textScaled(g, text, right - font.width(text) * 0.75f, y, 0.75f, color);
    }

    private long step() {
        return hasControlDown() ? 64 : hasShiftDown() ? 16 : 1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        Font font = Minecraft.getInstance().font;
        int ax = px + pw - PAD - 108, ay = py + PAD - 2;
        if (over(mx, my, ax, ay, 14, 14)) {
            planner.setRate(planner.rate - step());
            return true;
        }
        if (over(mx, my, ax + 94, ay, 14, 14)) {
            planner.setRate(planner.rate + step());
            return true;
        }
        int mxp = px + PAD, myp = py + PAD + 22;
        for (Planner.Mode mode : Planner.Mode.values()) {
            int w = font.width(modeLabel(mode)) + 12;
            if (over(mx, my, mxp, myp, w, 14)) {
                planner.setMode(mode);
                return true;
            }
            mxp += w + 3;
        }
        int fy = py + ph - FOOTER;
        Stack target = planner.tree.root.stack;
        String goal = tr(Board.contains(target) ? "ryscope.tree.unpin_goal" : "ryscope.tree.pin_goal");
        int gw = font.width(goal) + 12, gx = px + pw - PAD - gw, gy = fy + 30;
        if (over(mx, my, gx, gy, gw, 13)) {
            boolean added = Board.toggle(target, planner.rate);
            Overlay.toast(tr(added ? "ryscope.board.added" : "ryscope.board.removed"));
            return true;
        }
        int bodyY = py + HEADER, bodyH = ph - HEADER - FOOTER;
        if (my >= bodyY && my < bodyY + bodyH && over(mx, my, px + 4, bodyY, pw - 8, bodyH)) {
            int i = (int) ((my - bodyY + scroll) / ROW);
            if (i >= 0 && i < planner.lines.size()) {
                RecipeScreen.open(planner.lines.get(i).output, button == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
            }
            return true;
        }
        if (!over(mx, my, px, py, pw, ph)) onClose();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int ax = px + pw - PAD - 108, ay = py + PAD - 2;
        if (over(mx, my, ax, ay - 2, 108, 18)) {
            planner.setRate(planner.rate + (scrollY > 0 ? 1 : -1) * step());
            return true;
        }
        scroll -= (float) scrollY * ROW * 2;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE || Keys.matches(Keys.BACK, key, scan) || Minecraft.getInstance().options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) {
            planner.setRate(planner.rate + step());
            return true;
        }
        if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
            planner.setRate(planner.rate - step());
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
}
