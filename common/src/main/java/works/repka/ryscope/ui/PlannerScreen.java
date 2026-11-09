package works.repka.ryscope.ui;

import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Loading;
import works.repka.ryscore.ui.Type.Weight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscope.client.Keys;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Board;
import works.repka.ryscope.runtime.CraftTree;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.Planner;
import works.repka.ryscope.runtime.Units;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;

/** Планировщик производства: сколько машин нужно, чтобы делать N штук в час, и где узкое место. */
public final class PlannerScreen extends Screen {
    private static final int ROW = 20;
    private static final int PAD = Kit.PAD;
    private static final int HEADER = Kit.HEADER_H + Kit.TABS_H + 6 + 14 + 14;
    private static final int FOOTER = 58;

    @Nullable
    private final Screen parent;
    private final Planner planner;
    private int px, py, pw, ph;
    private float scroll;
    private final works.repka.ryscore.ui.Scroller scroller = new works.repka.ryscore.ui.Scroller();
    private final Kit.Hits hits = new Kit.Hits();

    private PlannerScreen(@Nullable Screen parent, Stack stack) {
        super(Component.translatable("ryscope.planner.title"));
        this.parent = parent;
        this.planner = new Planner(stack);
    }

    public static void open(Stack stack) {
        Minecraft mc = Minecraft.getInstance();
        // из сравнения в план переходят вкладкой: назад должно вести к дереву, а не к сравнению
        Screen parent = mc.screen instanceof CompareScreen cs ? cs.backTarget() : mc.screen;
        mc.setScreen(new PlannerScreen(parent, stack));
    }

    /** Куда вернуться, минуя соседние вкладки. */
    Screen backTarget() {
        return parent;
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
        hits.clear();
        renderBackground(g, mx, my, partialTick);
        pw = Math.min(width - 12, 520);
        py = Kit.panelTop(height);
        ph = height - py * 2;
        px = (width - pw) / 2;
        theme.panel(g, px, py, pw, ph);

        // план считается по частям, по несколько миллисекунд за кадр: окно открывается сразу
        planner.advance();
        if (!planner.ready()) {
            Loading.render(g, theme, px, py, pw, ph, "ryscope.planner.loading", planner.examined());
            return;
        }

        Stack target = planner.tree.root.stack;
        int iconX = px + PAD, iconY = py + 12, buttonY = py + 14;
        Kit.iconTile(g, theme, iconX, iconY);
        target.render(g, iconX + 4, iconY + 4);
        int closeX = px + pw - PAD - 18, stepperX = closeX - 7 - 110;
        Kit.close(g, theme, hits, closeX, buttonY, mx, my, this::onClose);
        float textX = iconX + 23 + 7;
        Kit.headerText(g, theme, target.name().getString(), title.getString(), textX, py, stepperX - 7 - textX);
        Kit.stepper(g, theme, hits, stepperX, buttonY, 110, Component.translatable("ryscope.planner.rate", planner.rate).getString(), mx, my,
            () -> planner.setRate(planner.rate - step()), () -> planner.setRate(planner.rate + step()));

        // --- вкладки ---
        int ty = py + Kit.HEADER_H;
        Kit.divider(g, theme, px + 0.5f, ty + Kit.TABS_H - 0.5f, pw - 1);
        int tx = px + PAD;
        tx += Kit.sectionTab(g, theme, hits, tx, ty, Kit.TABS_H, tr("ryscope.tree.tab"), false, mx, my, this::backToTree) + 2;
        tx += Kit.sectionTab(g, theme, hits, tx, ty, Kit.TABS_H, tr("ryscope.tree.plan"), true, mx, my, () -> {}) + 2;
        Kit.sectionTab(g, theme, hits, tx, ty, Kit.TABS_H, tr("ryscope.tree.compare"), false, mx, my, () -> CompareScreen.open(target));

        // --- режимы ---
        int mxp = px + PAD, myp = ty + Kit.TABS_H + 6;
        for (Planner.Mode mode : Planner.Mode.values()) {
            mxp += Kit.pill(g, theme, hits, mxp, myp, modeLabel(mode), planner.mode == mode, mx, my, () -> planner.setMode(mode)) + 3;
        }

        // --- заголовки столбцов ---
        int c4 = px + pw - PAD, c3 = c4 - 84, c2 = c3 - 58, c1 = c2 - 56;
        float hy = py + HEADER - 7;
        rightLabel(g, tr("ryscope.planner.col.energy"), c4, hy, 12, theme.c("text.muted"));
        rightLabel(g, tr("ryscope.planner.col.load"), c3, hy, 12, theme.c("text.muted"));
        rightLabel(g, tr("ryscope.planner.col.machines"), c2, hy, 12, theme.c("text.muted"));
        rightLabel(g, tr("ryscope.planner.col.crafts"), c1, hy, 12, theme.c("text.muted"));
        Kit.section(g, theme, tr("ryscope.planner.col.step"), px + PAD, hy);

        // --- строки ---
        int bodyY = py + HEADER, bodyH = ph - HEADER - FOOTER;
        float max = Math.max(0, planner.lines.size() * ROW - bodyH);
        scroll = scroller.update(max);
        hits.clip(bodyY, bodyY + bodyH);
        g.enableScissor(px + 1, bodyY, px + pw - 1, bodyY + bodyH);
        Stack hovered = null;
        for (int i = 0; i < planner.lines.size(); i++) {
            int y = bodyY + i * ROW - Math.round(scroll);
            if (y + ROW < bodyY || y > bodyY + bodyH) continue;
            Planner.Line line = planner.lines.get(i);
            float cy = y + ROW / 2f;
            boolean hov = my >= bodyY && my < bodyY + bodyH && over(mx, my, px + 4, y, pw - 8, ROW);
            if (hov) {
                Draw.round(g, px + 5, y, pw - 10, ROW - 1, 4.5f, Draw.alpha(theme.c("slot.hover"), 0.5f));
                hovered = line.output;
            }
            hits.add(px + 4, y, pw - 8, ROW, b -> RecipeScreen.open(line.output, b == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES));
            if (line == planner.bottleneck && planner.lines.size() > 1) Draw.round(g, px + 5, y + 2, 1.5f, ROW - 5, 0.5f, theme.c("state.warn"));
            try {
                g.pose().pushPose();
                g.pose().translate(px + PAD, y + 4, 0);
                g.pose().scale(0.75f, 0.75f, 1f);
                line.category.renderIcon(g, 0, 0);
                g.pose().popPose();
            } catch (Throwable ignored) {
                g.pose().popPose();
            }
            line.output.render(g, px + PAD + 16, y + 2);
            Draw.label(g, Draw.ellipsize(line.output.name().getString(), c1 - 44 - (px + PAD + 36), 13, Weight.REGULAR), px + PAD + 36, cy, 13, Weight.REGULAR, theme.c("text.body"));
            rightLabel(g, Units.compact(line.craftsPerHour), c1, cy, 13, theme.c("text.body"));
            if (line.timed()) {
                rightLabel(g, Integer.toString(line.machines), c2, cy, 13, theme.c("text.primary"));
                rightLabel(g, Math.round(line.load * 100) + "%", c3, cy, 13, line.load > 0.9 ? theme.c("state.warn") : theme.c("text.muted"));
                rightLabel(g, line.energyPerTick > 0 ? Units.energyPerTick(line.energyPerTick) : "—", c4, cy, 13, theme.c("text.muted"));
            } else {
                rightLabel(g, tr("ryscope.planner.manual"), c4, cy, 12, theme.c("text.dim"));
            }
        }
        g.disableScissor();
        hits.unclip();

        // --- итог ---
        int fy = py + ph - FOOTER;
        Kit.divider(g, theme, px + 0.5f, fy, pw - 1);
        String total = Component.translatable("ryscope.planner.total", planner.totalMachines,
            planner.totalEnergy > 0 ? Units.energyPerTick(planner.totalEnergy) : "—").getString();
        Draw.label(g, total, px + PAD, fy + 10, 13, Weight.MEDIUM, theme.c("text.primary"));
        boolean pinned = Board.contains(target);
        String goal = tr(pinned ? "ryscope.tree.unpin_goal" : "ryscope.tree.pin_goal");
        int gw = (int) Math.ceil(Kit.buttonWidth(goal, 13)), gx = px + pw - PAD - gw;
        Kit.button(g, theme, hits, gx, fy + 32, gw, 20, goal, true, mx, my, () -> {
            boolean added = Board.toggle(target, planner.rate);
            Overlay.toast(tr(added ? "ryscope.board.added" : "ryscope.board.removed"));
        });
        if (planner.bottleneck != null && planner.lines.size() > 1) {
            float tw = Draw.labelWidth(total, 13, Weight.MEDIUM);
            String b = Component.translatable("ryscope.planner.bottleneck", planner.bottleneck.output.name().getString()).getString();
            Draw.label(g, Draw.ellipsize(b, px + pw - PAD - (px + PAD + tw + 10), 12, Weight.REGULAR), px + PAD + tw + 10, fy + 10, 12, Weight.REGULAR, theme.c("state.warn"));
        }
        int rx = px + PAD, ry = fy + 33;
        Kit.section(g, theme, tr("ryscope.planner.raw"), rx, fy + 24);
        Stack rawHover = null;
        for (CraftTree.Raw r : planner.tree.raw.values()) {
            if (rx + 20 > gx - 8) break;
            boolean hot = over(mx, my, rx, ry, 20, 20);
            Kit.tile(g, theme, rx, ry, hot);
            r.stack.render(g, rx + 2, ry + 2);
            Kit.count(g, Units.compact(r.need), rx, ry);
            if (hot) rawHover = r.stack;
            rx += 23;
        }

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        if (rawHover != null) rawHover.renderTooltip(g, mx, my);
        else if (hovered != null && over(mx, my, px + PAD + 16, 0, 18, height)) hovered.renderTooltip(g, mx, my);
        g.pose().popPose();
        Overlay.renderToast(this, g);
    }

    private void backToTree() {
        Minecraft mc = Minecraft.getInstance();
        if (parent instanceof TreeScreen) mc.setScreen(parent);
        else TreeScreen.open(planner.tree.root.stack);
    }

    private static void rightLabel(GuiGraphics g, String text, int right, float cy, float size, int color) {
        Draw.label(g, text, right - Draw.labelWidth(text, size, Weight.REGULAR), cy, size, Weight.REGULAR, color);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private long step() {
        return hasControlDown() ? 64 : hasShiftDown() ? 16 : 1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!planner.ready()) return true;
        if (hits.click(mx, my, button)) return true;
        if (!over(mx, my, px, py, pw, ph)) onClose();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (!planner.ready()) return true;
        int ax = px + pw - PAD - 18 - 7 - 110, ay = py + 14;
        if (over(mx, my, ax, ay, 110, 18)) {
            planner.setRate(planner.rate + (scrollY > 0 ? 1 : -1) * step());
            return true;
        }
        scroller.by(-(float) scrollY * ROW * 2);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE || Keys.matches(Keys.BACK, key, scan) || Minecraft.getInstance().options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        if (!planner.ready()) return true;
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
