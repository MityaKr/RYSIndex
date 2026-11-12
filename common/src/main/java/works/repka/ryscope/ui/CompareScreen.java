package works.repka.ryscope.ui;

import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Loading;
import works.repka.ryscore.ui.Type.Weight;
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
public final class CompareScreen extends ScaledScreen {
    private static final int PAD = Kit.PAD;
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
    private final Kit.Hits hits = new Kit.Hits();

    private CompareScreen(@Nullable Screen parent, Stack stack) {
        super(Component.translatable("ryscope.compare.title"));
        this.parent = parent;
        this.target = stack.withAmount(1);
        this.probe = new CraftTree(target, true);
    }

    public static void open(Stack stack) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen instanceof PlannerScreen ps ? ps.backTarget() : mc.screen;
        mc.setScreen(new CompareScreen(parent, stack));
    }

    /** Куда вернуться, минуя соседние вкладки. */
    Screen backTarget() {
        return parent;
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
        return Math.max(1, Math.min(routes.size(), Math.min(4, (width - 24 - PAD * 2) / COL)));
    }

    private static final int CARD_H = 158;

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float partialTick) {
        Theme theme = ThemeManager.get();
        hits.clear();
        renderBackground(g, mx, my, partialTick);
        int cols = visible();
        first = Math.max(0, Math.min(first, Math.max(0, routes.size() - cols)));
        pw = Math.max(320, cols * COL + PAD * 2 - 4);
        py = Kit.panelTop(height);
        ph = Math.min(height - py - 4, Kit.HEADER_H + Kit.TABS_H + 8 + CARD_H + PAD);
        px = (width - pw) / 2;
        theme.panel(g, px, py, pw, ph);
        if (!done) {
            advance();
            Loading.render(g, theme, px, py, pw, ph, "ryscope.compare.loading", probe.examined());
            return;
        }
        int iconX = px + PAD, iconY = py + 12, buttonY = py + 14;
        Kit.iconTile(g, theme, iconX, iconY);
        target.render(g, iconX + 4, iconY + 4);
        int closeX = px + pw - PAD - 18;
        Kit.close(g, theme, hits, closeX, buttonY, mx, my, this::onClose);
        int right = closeX - 7;
        if (routes.size() > cols) {
            String page = (first + 1) + "–" + (first + cols) + " / " + routes.size();
            float w = Draw.labelWidth(page, 13, Weight.REGULAR);
            int nx = right - 5, px1 = (int) (nx - w - 18);
            float cy = buttonY + 9;
            Draw.label(g, "‹", px1, cy, 13, Weight.REGULAR, over(mx, my, px1 - 2, buttonY, 9, 18) ? theme.c("accent") : theme.c("button.fg"));
            Draw.label(g, page, px1 + 9, cy, 13, Weight.REGULAR, theme.c("text.muted"));
            Draw.label(g, "›", nx, cy, 13, Weight.REGULAR, over(mx, my, nx - 2, buttonY, 9, 18) ? theme.c("accent") : theme.c("button.fg"));
            hits.add(px1 - 2, buttonY, 9, 18, b -> first -= cols);
            hits.add(nx - 2, buttonY, 9, 18, b -> first += cols);
            right = px1 - 7;
        }
        float textX = iconX + 23 + 7;
        int ways = probe.root.options == null ? routes.size() : probe.root.options.size();
        String count = ways > routes.size() ? Component.translatable("ryscope.compare.count_of", ways, routes.size()).getString()
            : Component.translatable("ryscope.compare.count", routes.size()).getString();
        Kit.headerText(g, theme, target.name().getString(), count, textX, py, right - textX);

        int ty = py + Kit.HEADER_H;
        Kit.divider(g, theme, px + 0.5f, ty + Kit.TABS_H - 0.5f, pw - 1);
        int tx = px + PAD;
        tx += Kit.sectionTab(g, theme, hits, tx, ty, Kit.TABS_H, tr("ryscope.tree.tab"), false, mx, my, this::backToTree) + 2;
        tx += Kit.sectionTab(g, theme, hits, tx, ty, Kit.TABS_H, tr("ryscope.tree.plan"), false, mx, my,
            () -> PlannerScreen.open(target, parent instanceof TreeScreen ts ? ts.route(true) : null)) + 2;
        Kit.sectionTab(g, theme, hits, tx, ty, Kit.TABS_H, tr("ryscope.tree.compare"), true, mx, my, () -> {});

        Stack hover = null;
        boolean overCard = false;
        int cardTop = ty + Kit.TABS_H + 8;
        int cardH = ph - (cardTop - py) - PAD;
        for (int c = 0; c < Math.min(cols, routes.size() - first); c++) {
            Route r = routes.get(first + c);
            int x = px + PAD + c * COL, y = cardTop, w = COL - 4;
            theme.card(g, x, y, w, cardH);
            hits.add(x, y, w, cardH, b -> {
                if (b == 0) chooseRoute(r);
            });
            overCard |= over(mx, my, x, y, w, cardH);
            int cx = x + 6;
            try {
                r.option.category.renderIcon(g, cx, y + 5);
            } catch (Throwable ignored) {
                // значок чужой категории
            }
            Draw.label(g, Draw.ellipsize(r.option.category.title.getString(), w - 30, 13, Weight.MEDIUM), cx + 20, y + 13, 13, Weight.MEDIUM, theme.c("text.primary"));
            Kit.section(g, theme, tr("ryscope.compare.raw"), cx, y + 30);
            int i = 0, rawY = y + 36;
            for (CraftTree.Raw raw : r.tree.raw.values()) {
                if (i >= 10) break;
                int sx = cx + (i % 5) * 21, sy = rawY + (i / 5) * 21;
                boolean hot = over(mx, my, sx, sy, 20, 20);
                Kit.tile(g, theme, sx, sy, hot);
                raw.stack.render(g, sx + 2, sy + 2);
                Kit.count(g, Units.compact(raw.need), sx, sy);
                if (hot) hover = raw.stack;
                Stack stack = raw.stack;
                hits.add(sx, sy, 20, 20, b -> RecipeScreen.open(stack, b == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES));
                i++;
            }
            if (r.tree.raw.isEmpty()) Draw.label(g, "—", cx, rawY + 8, 12, Weight.REGULAR, theme.c("text.dim"));
            float ry = y + 88;
            row(g, theme, cx, ry, w, tr("ryscope.compare.steps"), Integer.toString(r.tree.steps));
            row(g, theme, cx, ry + 11, w, tr("ryscope.compare.time"), r.timed ? Units.time(r.ticks) : tr("ryscope.compare.unknown"));
            row(g, theme, cx, ry + 22, w, tr("ryscope.compare.energy"), r.energy > 0 ? Units.energy(r.energy) : "—");
            Kit.section(g, theme, tr("ryscope.compare.byproducts"), cx, ry + 33);
            int bx = x + w - 6;
            if (r.byproducts.isEmpty()) {
                Draw.label(g, "—", bx - 5, ry + 33, 12, Weight.REGULAR, theme.c("text.dim"));
            } else {
                for (int b = Math.min(3, r.byproducts.size()) - 1; b >= 0; b--) {
                    bx -= 13;
                    int by = (int) ry + 27;
                    g.pose().pushPose();
                    g.pose().translate(bx, by, 0);
                    g.pose().scale(0.75f, 0.75f, 1f);
                    r.byproducts.get(b).render(g, 0, 0);
                    g.pose().popPose();
                    if (over(mx, my, bx, by, 12, 12)) hover = r.byproducts.get(b);
                }
            }
            boolean ok = r.missingNow == 0;
            String now = ok ? tr("ryscope.compare.now_yes") : Component.translatable("ryscope.compare.now_no", r.missingNow).getString();
            float sy = y + cardH - 9;
            Draw.dot(g, cx, (int) sy - 2, 4, theme.c(ok ? "state.ok" : "state.warn"));
            Draw.label(g, Draw.ellipsize(now, w - 18, 12, Weight.REGULAR), cx + 8, sy, 12, Weight.REGULAR, ok ? theme.c("state.ok") : theme.c("state.warn"));
        }

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        if (hover != null) hover.renderTooltip(g, mx, my);
        else if (overCard) Kit.tooltip(g, theme, List.of(tr("ryscope.compare.pick")), mx, my);
        g.pose().popPose();
        Overlay.renderToast(this, g);
    }

    /** Клик по карточке: этот маршрут выбирается в дереве, и окно возвращается к дереву. */
    private void chooseRoute(Route r) {
        Minecraft mc = Minecraft.getInstance();
        if (parent instanceof TreeScreen ts) {
            ts.chooseRoot(r.option.recipe);
            mc.setScreen(ts);
        } else {
            mc.setScreen(parent);
            TreeScreen.open(target, CraftTree.Route.root(target, r.option.recipe), 0);
        }
    }

    private void backToTree() {
        Minecraft mc = Minecraft.getInstance();
        if (parent instanceof TreeScreen) mc.setScreen(parent);
        else TreeScreen.open(target);
    }

    private static void row(GuiGraphics g, Theme theme, int x, float cy, int w, String label, String value) {
        Kit.section(g, theme, label, x, cy);
        Draw.label(g, value, x + w - 12 - Draw.labelWidth(value, 12, Weight.REGULAR), cy, 12, Weight.REGULAR, theme.c("text.body"));
    }

    @Override
    public void tick() {
        if (done && routes.size() < 2) {
            Overlay.toast(Component.translatable(routes.isEmpty() ? "ryscope.compare.none" : "ryscope.compare.single").getString());
            onClose();
        }
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (!done) return true;
        if (hits.click(mx, my, button)) return true;
        if (!over(mx, my, px, py, pw, ph)) onClose();
        return true;
    }

    @Override
    protected boolean onScroll(double mx, double my, double scrollX, double scrollY) {
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
