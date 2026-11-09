package works.repka.ryscope.ui;

import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Loading;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscope.client.Keys;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.CraftTree;
import works.repka.ryscope.runtime.CraftTree.Node;
import works.repka.ryscope.runtime.CraftTree.Option;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.Units;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;

/**
 * Сравнение маршрутов: один предмет, несколько способов его получить, рядом и в одинаковых единицах.
 * Для каждого способа: сырьё, число шагов, время, энергия, побочные продукты и хватает ли запасов сейчас.
 */
public final class CompareScreen extends Screen {
    private static final int PAD = 10;
    private static final int COL = 122;

    private static final class Route {
        Option option;
        CraftTree tree;
        double ticks;
        double energy;
        boolean timed;
        int missingNow;
        final List<Stack> byproducts = new ArrayList<>();
    }

    @Nullable
    private final Screen parent;
    private final Stack target;
    private final List<Route> routes = new ArrayList<>();
    private int px, py, pw, ph;
    private int first;

    private CompareScreen(@Nullable Screen parent, Stack stack) {
        super(Component.translatable("ryscope.compare.title"));
        this.parent = parent;
        this.target = stack.withAmount(1);
        this.probe = new CraftTree(target, true);
    }

    public static void open(Stack stack) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new CompareScreen(mc.screen, stack));
    }

    private final CraftTree probe;
    private CraftTree building;
    private boolean buildingWithInventory;
    private Route route;
    private int index;
    private boolean done;

    /** Порция расчёта: укладывается в несколько миллисекунд, чтобы окно не подвисало. */
    private void advance() {
        long until = System.nanoTime() + 8_000_000L;
        while (!done && System.nanoTime() < until) step();
    }

    private void step() {
        if (!probe.ready()) {
            probe.recompute();
            return;
        }
        List<Option> options = probe.root.options;
        if (options == null || index >= options.size() || routes.size() >= 12) {
            done = true;
            return;
        }
        if (building == null) {
            route = new Route();
            route.option = options.get(index);
            building = forced(index, false);
            buildingWithInventory = false;
            return;
        }
        if (!building.ready()) {
            building.recompute();
            return;
        }
        if (!buildingWithInventory) {
            route.tree = building;
            sum(route.tree.root, route);
            building = forced(index, true);
            buildingWithInventory = true;
            return;
        }
        route.missingNow = building.raw.size();
        for (Stack out : route.option.recipe.outputs()) {
            if (!out.key().equals(target.key())) route.byproducts.add(out);
        }
        routes.add(route);
        building = null;
        index++;
    }

    /** Дерево, в корне которого принудительно выбран рецепт с этим номером. */
    private CraftTree forced(int choice, boolean inventory) {
        CraftTree tree = new CraftTree(target, true);
        tree.inherit(probe);
        tree.useInventory = inventory;
        tree.root.choice = choice;
        tree.root.children.clear();
        tree.recompute();
        return tree;
    }

    private static void sum(Node node, Route r) {
        Option o = node.chosen();
        if (o != null && node.crafts > 0) {
            if (o.recipe.ticks() > 0) r.timed = true;
            r.ticks += node.crafts * (double) o.recipe.ticks();
            r.energy += node.crafts * (double) o.recipe.energy();
        }
        for (Node c : node.children) sum(c, r);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, ThemeManager.get().c("overlay"));
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private int visible() {
        return Math.max(1, Math.min(routes.size(), (width - 24 - PAD * 2) / COL));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme theme = ThemeManager.get();
        Font font = Minecraft.getInstance().font;
        renderBackground(g, mx, my, partialTick);
        int cols = visible();
        first = Math.max(0, Math.min(first, routes.size() - cols));
        pw = cols * COL + PAD * 2;
        ph = Math.min(height - 12, 196);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        theme.panel(g, px, py, pw, ph);
        if (!done) {
            advance();
            Loading.render(g, theme, px, py, pw, ph, "ryscope.compare.loading", probe.examined());
            return;
        }
        target.render(g, px + PAD, py + PAD - 1);
        Draw.text(g, Draw.ellipsize(target.name().getString(), pw - 120), px + PAD + 22, py + PAD - 1, theme.c("text.primary"));
        String sub = Component.translatable("ryscope.compare.count", routes.size()).getString();
        Draw.textScaled(g, sub, px + PAD + 22, py + PAD + 9, 0.75f, theme.c("text.muted"));
        if (routes.size() > cols) {
            String page = (first + 1) + "–" + (first + cols) + " / " + routes.size();
            int w = font.width(page);
            int nx = px + pw - PAD - w - 22;
            Draw.chevron(g, nx, py + PAD + 1, false, theme.c("text.muted"));
            Draw.text(g, page, nx + 11, py + PAD + 1, theme.c("text.muted"));
            Draw.chevron(g, nx + 15 + w, py + PAD + 1, true, theme.c("text.muted"));
        }

        Stack hover = null;
        for (int c = 0; c < cols; c++) {
            Route r = routes.get(first + c);
            int x = px + PAD + c * COL, y = py + PAD + 24, w = COL - 4, h = ph - PAD * 2 - 24;
            theme.card(g, x, y, w, h);
            int cx = x + 6, cy = y + 6;
            try {
                r.option.category.renderIcon(g, cx, cy);
            } catch (Throwable ignored) {
                // значок чужой категории
            }
            Draw.text(g, Draw.ellipsize(r.option.category.title.getString(), w - 30), cx + 20, cy + 4, theme.c("text.body"));
            cy += 22;

            Draw.textScaled(g, tr("ryscope.compare.raw"), cx, cy, 0.75f, theme.c("text.muted"));
            cy += 9;
            int i = 0;
            for (CraftTree.Raw raw : r.tree.raw.values()) {
                if (i >= 10) break;
                int sx = cx + (i % 5) * 21, sy = cy + (i / 5) * 21;
                theme.slot(g, sx, sy, 20, over(mx, my, sx, sy, 20, 20));
                raw.stack.render(g, sx + 2, sy + 2);
                String count = Units.compact(raw.need);
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                Draw.textScaled(g, count, sx + 19 - font.width(count) * 0.6f, sy + 14, 0.6f, 0xFFFFFFFF);
                g.pose().popPose();
                if (over(mx, my, sx, sy, 20, 20)) hover = raw.stack;
                i++;
            }
            if (r.tree.raw.isEmpty()) Draw.textScaled(g, "—", cx, cy + 4, 0.75f, theme.c("text.dim"));
            cy += 44;

            cy = row(g, theme, cx, cy, w, tr("ryscope.compare.steps"), Integer.toString(r.tree.steps));
            cy = row(g, theme, cx, cy, w, tr("ryscope.compare.time"), r.timed ? Units.time(r.ticks) : tr("ryscope.compare.unknown"));
            cy = row(g, theme, cx, cy, w, tr("ryscope.compare.energy"), r.energy > 0 ? Units.energy(r.energy) : "—");

            Draw.textScaled(g, tr("ryscope.compare.byproducts"), cx, cy + 2, 0.75f, theme.c("text.muted"));
            int bx = x + w - 8;
            if (r.byproducts.isEmpty()) {
                Draw.textScaled(g, "—", bx - 4, cy + 2, 0.75f, theme.c("text.dim"));
            } else {
                for (int b = Math.min(3, r.byproducts.size()) - 1; b >= 0; b--) {
                    bx -= 13;
                    g.pose().pushPose();
                    g.pose().translate(bx, cy - 1, 0);
                    g.pose().scale(0.75f, 0.75f, 1f);
                    r.byproducts.get(b).render(g, 0, 0);
                    g.pose().popPose();
                    if (over(mx, my, bx, cy - 1, 12, 12)) hover = r.byproducts.get(b);
                }
            }
            cy += 13;

            boolean ok = r.missingNow == 0;
            String now = ok ? tr("ryscope.compare.now_yes") : Component.translatable("ryscope.compare.now_no", r.missingNow).getString();
            // состояние передано и текстом, и цветом
            Draw.textScaled(g, Draw.ellipsize(now, (int) ((w - 12) / 0.75f)), cx, cy + 3, 0.75f, ok ? theme.c("state.ok") : theme.c("state.warn"));
        }

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        if (hover != null) hover.renderTooltip(g, mx, my);
        g.pose().popPose();
        Overlay.renderToast(this, g);
    }

    private static int row(GuiGraphics g, Theme theme, int x, int y, int w, String label, String value) {
        Font font = Minecraft.getInstance().font;
        Draw.textScaled(g, label, x, y + 2, 0.75f, theme.c("text.muted"));
        Draw.textScaled(g, value, x + w - 12 - font.width(value) * 0.75f, y + 2, 0.75f, theme.c("text.body"));
        return y + 11;
    }

    @Override
    public void tick() {
        if (done && routes.size() < 2) {
            Overlay.toast(Component.translatable("ryscope.compare.single").getString());
            onClose();
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!done) return true;
        if (!over(mx, my, px, py, pw, ph)) {
            onClose();
            return true;
        }
        int cols = visible();
        if (routes.size() > cols && my < py + PAD + 16) {
            first += mx > px + pw - PAD - 14 ? cols : mx > px + pw - PAD - 80 ? -cols : 0;
            return true;
        }
        for (int c = 0; c < cols; c++) {
            int x = px + PAD + c * COL;
            if (over(mx, my, x, py + PAD + 24, COL - 4, ph - PAD * 2 - 24)) {
                RecipeScreen.open(target, Lookup.Mode.RECIPES);
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (!done) return true;
        first += scrollY < 0 ? 1 : -1;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE || Keys.matches(Keys.BACK, key, scan) || Minecraft.getInstance().options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        if (!done) return true;
        if (key == GLFW.GLFW_KEY_LEFT) first--;
        if (key == GLFW.GLFW_KEY_RIGHT) first++;
        return true;
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
