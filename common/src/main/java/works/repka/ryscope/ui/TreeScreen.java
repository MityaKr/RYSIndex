package works.repka.ryscope.ui;

import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Loading;
import works.repka.ryscore.ui.Numbers;
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
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Board;
import works.repka.ryscope.runtime.Bookmarks;
import works.repka.ryscope.runtime.CraftTree;
import works.repka.ryscope.runtime.CraftTree.Node;
import works.repka.ryscope.runtime.InventoryView;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;

/** Дерево крафта: раскрывающийся список «что из чего», с учётом инвентаря и итогом по сырью. */
public final class TreeScreen extends ScaledScreen {
    private static final int ROW = 20;
    private static final int PAD = Kit.PAD;
    private static final int HEADER = Kit.HEADER_H + Kit.TABS_H + 4;
    private static final int FOOTER = 52;
    /** Блок «Заказать в сети» над итогом: подпись и ряд плашек. */
    private static final int ORDER_H = 36;
    /** Строка «Нужны станции» над итогом. */
    private static final int STATIONS_H = 16;

    private record Row(Node node, int y) {}

    @Nullable
    private final Screen parent;
    private final CraftTree tree;
    private int px, py, pw, ph;
    private float scroll;
    private final works.repka.ryscore.ui.Scroller scroller = new works.repka.ryscore.ui.Scroller();
    private final List<Row> rows = new ArrayList<>();
    private int refresh;
    private final Kit.Hits hits = new Kit.Hits();
    /** Открыта вкладка «Все цели»: общая сумма по доске вместо дерева предмета. */
    private boolean all;
    /** На вкладке «Все цели» показано дерево, а не таблица сырья. */
    private boolean allTree;

    /** Последний маршрут дерева каждого предмета: новое открытие показывает тот же выбор рецептов. */
    private static final java.util.Map<String, CraftTree.Route> ROUTES = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, CraftTree.Route> eldest) {
            return size() > 32;
        }
    };

    private TreeScreen(@Nullable Screen parent, Stack stack, @Nullable CraftTree.Route route, long amount) {
        super(Component.translatable("ryscope.tree.title"));
        this.parent = parent;
        this.tree = new CraftTree(stack, true);
        tree.apply(route != null ? route : ROUTES.get(key(stack)));
        if (amount > 1) tree.setAmount(amount);
    }

    public static void open(Stack stack) {
        open(stack, null, 0);
    }

    /** Общая сумма по всем целям доски: вкладка «Все цели» поверх дерева первой цели. */
    public static void openAll() {
        List<Board.Goal> goals = Board.goals();
        if (goals.isEmpty()) return;
        openGoal(goals.get(0));
        if (Minecraft.getInstance().screen instanceof TreeScreen ts) ts.all = true;
    }

    /** Для автотеста: открыта ли вкладка «Все цели». */
    public boolean showsAll() {
        return all;
    }

    /** Дерево с этим маршрутом и количеством: так открывается цель с доски и выбранный в сравнении маршрут. */
    public static void open(Stack stack, @Nullable CraftTree.Route route, long amount) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen;
        if (parent instanceof TreeScreen ts) parent = ts.parent;
        mc.setScreen(new TreeScreen(parent, stack, route, amount));
    }

    /**
     * Дерево цели с доски: её маршрут и количество. Цель с выбором по умолчанию (route == null) открывается с пустым
     * маршрутом, а не с запомненным в ROUTES, иначе дерево покажет чужой выбор и предложит «Обновить цель».
     */
    public static void openGoal(works.repka.ryscope.runtime.Board.Goal goal) {
        open(goal.stack, goal.route != null ? goal.route : new CraftTree.Route(), goal.amount);
    }

    private static String key(Stack stack) {
        return stack.id().toString();
    }

    /** Маршрут этого дерева для плана и сравнения. */
    CraftTree.Route route(boolean everyNode) {
        return tree.route(everyNode);
    }

    /** Выбрать в корне рецепт маршрута из сравнения. */
    void chooseRoot(works.repka.ryscope.model.RecipeView recipe) {
        if (tree.ready()) tree.choose(tree.root, recipe);
        else tree.apply(CraftTree.Route.root(tree.root.stack, recipe));
        scroller.reset();
    }

    @Override
    public void removed() {
        super.removed();
        // в статическом запоминании только id рецептов: сами объекты держат рантайм JEI и снимок этого мира
        CraftTree.Route route = tree.route(false).withoutRecipes();
        if (route.isEmpty()) ROUTES.remove(key(tree.root.stack));
        else ROUTES.put(key(tree.root.stack), route);
    }

    /** Забыть запомненные маршруты: при выходе из мира. */
    public static void clearRoutes() {
        ROUTES.clear();
    }

    /** Вкладка «Сравнить» неактивна, если сравнивать нечего: у предмета меньше двух способов. */
    static int compareTab(GuiGraphics g, Theme t, Kit.Hits hits, int x, int y, boolean enabled, int mx, int my, Runnable action) {
        String label = tr("ryscope.tree.compare");
        if (enabled) return Kit.sectionTab(g, t, hits, x, y, Kit.TABS_H, label, false, mx, my, action);
        int w = (int) Math.ceil(Draw.labelWidth(label, 12f, Weight.SEMIBOLD)) + 12;
        Kit.tabs(g, t, hits, x, y, Kit.TABS_H, w, List.of(new Kit.Tab(label, -1, false, null)), mx, my);
        return w;
    }

    /** Куда вернуться: экран, из которого открыто дерево. */
    @Nullable
    Screen backTarget() {
        return parent;
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
    protected void draw(GuiGraphics g, int mx, int my, float partialTick) {
        Theme theme = ThemeManager.get();
        hits.clear();
        renderBackground(g, mx, my, partialTick);
        pw = Math.min(width - 12, 480);
        py = Kit.panelTop(height);
        ph = height - py * 2;
        px = (width - pw) / 2;
        theme.panel(g, px, py, pw, ph);
        if (all) {
            drawAll(g, theme, mx, my);
            return;
        }

        // дерево строится по частям, по несколько миллисекунд за кадр: окно открывается сразу
        if (!tree.ready()) tree.recompute();
        renderHeader(g, theme, mx, my);
        if (!tree.ready()) {
            rows.clear();
            Loading.render(g, theme, px, py + Kit.HEADER_H, pw, ph - Kit.HEADER_H, "ryscope.tree.loading", tree.examined());
            return;
        }
        renderTabs(g, theme, mx, my);

        List<Node> nodes = new ArrayList<>();
        flatten(tree.root, nodes);
        int bodyY = py + HEADER, bodyH = ph - HEADER - footerH();
        float max = Math.max(0, nodes.size() * ROW - bodyH);
        scroll = scroller.update(max);
        rows.clear();
        hits.clip(bodyY, bodyY + bodyH);
        Draw.scissor(g, px + 1, bodyY, px + pw - 1, bodyY + bodyH);
        Node hovered = null;
        stationTip = null;
        for (int i = 0; i < nodes.size(); i++) {
            int y = bodyY + i * ROW - Math.round(scroll);
            if (y + ROW < bodyY || y > bodyY + bodyH) continue;
            Node n = nodes.get(i);
            rows.add(new Row(n, y));
            if (renderRow(g, theme, n, y, mx, my, bodyY, bodyH)) hovered = n;
        }
        g.disableScissor();
        hits.unclip();

        Stack rawHover = renderFooter(g, theme, mx, my);
        Stack orderHover = renderOrdered(g, theme, mx, my);
        if (rawHover == null) rawHover = orderHover;
        renderStations(g, theme);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        CraftTree.Raw hoveredRaw = rawHover == null ? null : tree.raw.get(rawHover.key());
        if (hoveredRaw != null && hoveredRaw.truncated) {
            // не настоящее сырьё: у предмета есть рецепт, дерево просто не раскрыло ветку
            List<Component> lines = new ArrayList<>(rawHover.tooltipLines());
            lines.add(Component.translatable("ryscope.tree.truncated.tip").withStyle(net.minecraft.ChatFormatting.GRAY));
            g.renderComponentTooltip(Minecraft.getInstance().font, lines, mx, my);
        } else if (rawHover != null) rawHover.renderTooltip(g, mx, my);
        else if (hovered != null && over(mx, my, px + PAD + hovered.depth * 12 + 9, 0, 18, height)) hovered.stack.renderTooltip(g, mx, my);
        else if (stationTip != null) Kit.tooltip(g, theme, List.of(stationTip, tr("ryscope.tree.no_station.tip")), mx, my);
        else if (autocraftHot) Kit.tooltip(g, theme, List.of(tr("ryscope.tree.autocraft.tip")), mx, my);
        g.pose().popPose();
        Overlay.renderToast(this, g);
    }

    /** Высота итога внизу окна: с блоком «Заказать в сети», если сеть что-то закажет, и строкой «Нужны станции». */
    private int footerH() {
        if (!tree.ready()) return FOOTER;
        return FOOTER + (tree.ordered.isEmpty() ? 0 : ORDER_H) + (tree.missingStations.isEmpty() ? 0 : STATIONS_H);
    }

    /** Подсказка у значка «нет станции» под курсором, null, если курсор не на нём. */
    @Nullable
    private String stationTip;

    /** Чем назвать станцию категории: её первым катализатором, без него названием категории. */
    static String stationName(Category c) {
        return c.stations.isEmpty() ? c.title.getString() : c.stations.get(0).name().getString();
    }

    /** «Нужны станции: …» по категориям, станций которых у игрока нет. */
    static String missingStations(CraftTree t) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Category c : t.missingStations) names.add(stationName(c));
        return Component.translatable("ryscope.tree.need_stations", String.join(", ", names)).getString();
    }

    private void renderStations(GuiGraphics g, Theme t) {
        if (tree.missingStations.isEmpty()) return;
        int sy = py + ph - footerH();
        Kit.divider(g, t, px + 0.5f, sy, pw - 1);
        Draw.label(g, Draw.ellipsize(missingStations(tree), pw - PAD * 2, 12, Weight.REGULAR), px + PAD, sy + 8.5f, 12, Weight.REGULAR, t.c("state.warn"));
    }

    /** Под курсором переключатель автокрафта: показать подсказку. */
    private boolean autocraftHot;

    private void renderHeader(GuiGraphics g, Theme t, int mx, int my) {
        int iconX = px + PAD, iconY = py + 12;
        Kit.iconTile(g, t, iconX, iconY);
        tree.root.stack.render(g, iconX + 4, iconY + 4);
        int closeX = px + pw - PAD - 18, stepperX = closeX - 7 - 72, buttonY = py + 14;
        Kit.close(g, t, hits, closeX, buttonY, mx, my, this::onClose);
        String sub = tree.ready() ? Component.translatable("ryscope.tree.summary", tree.steps, tree.maxDepth).getString() : "";
        float textX = iconX + 23 + 7;
        Kit.headerText(g, t, tree.root.stack.name().getString(), sub, textX, py, stepperX - 7 - textX);
        if (tree.ready()) {
            int step = hasShiftDown() ? 8 : 1;
            Kit.stepper(g, t, hits, stepperX, buttonY, 72, Long.toString(tree.amount), mx, my, () -> tree.setAmount(tree.amount - step), () -> tree.setAmount(tree.amount + step));
        }
    }

    private void renderTabs(GuiGraphics g, Theme t, int mx, int my) {
        int y = py + Kit.HEADER_H;
        Kit.divider(g, t, px + 0.5f, y + Kit.TABS_H - 0.5f, pw - 1);
        int x = px + PAD;
        x += Kit.sectionTab(g, t, hits, x, y, Kit.TABS_H, tr("ryscope.tree.tab"), !all, mx, my, () -> {
            all = false;
            scroller.reset();
        }) + 2;
        x += Kit.sectionTab(g, t, hits, x, y, Kit.TABS_H, tr("ryscope.tree.plan"), false, mx, my, () -> PlannerScreen.open(tree.root.stack, tree.route(true))) + 2;
        x += compareTab(g, t, hits, x, y, tree.ready() && tree.root.options != null && tree.root.options.size() > 1, mx, my, () -> CompareScreen.open(tree.root.stack)) + 2;
        // общая сумма по доске: вкладка есть, пока закреплена хоть одна цель
        if (!Board.goals().isEmpty()) {
            x += Kit.sectionTab(g, t, hits, x, y, Kit.TABS_H, tr("ryscope.tree.all_goals"), all, mx, my, () -> {
                all = true;
                scroller.reset();
            });
        }
        String label = tr(all ? "ryscope.tree.all_tree" : "ryscope.tree.shortest");
        boolean active = all ? allTree : tree.shortest();
        int w = (int) Math.ceil(Draw.labelWidth(label, 12.5f, active ? Weight.MEDIUM : Weight.REGULAR)) + 12;
        int pillX = px + pw - PAD - w;
        if (pillX > x + 4) {
            Kit.pill(g, t, hits, pillX, y + 1, label, active, mx, my, () -> {
                if (all) allTree = !allTree;
                else tree.setShortest(!tree.shortest());
                scroller.reset();
            });
        } else {
            pillX = px + pw - PAD;
        }
        autocraftHot = false;
        // переключатель есть, только если сеть хоть что-то умеет делать: иначе переключать нечего
        if (works.repka.ryscope.runtime.Ae2Terminal.anyCraftableKnown()) {
            String ae = tr("ryscope.tree.autocraft");
            boolean on = works.repka.ryscope.runtime.Config.get().treeAutocraft;
            int aw = (int) Math.ceil(Draw.labelWidth(ae, 12.5f, on ? Weight.MEDIUM : Weight.REGULAR)) + 12;
            int ax = pillX - 4 - aw;
            if (ax > x + 4) {
                Kit.pill(g, t, hits, ax, y + 1, ae, on, mx, my, () -> {
                    works.repka.ryscope.runtime.Config.get().treeAutocraft = !on;
                    works.repka.ryscope.runtime.Config.save();
                    if (tree.ready()) tree.recompute();
                });
                autocraftHot = over(mx, my, ax, y + 1, aw, 14);
            }
        }
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private boolean renderRow(GuiGraphics g, Theme t, Node n, int y, int mx, int my, int bodyY, int bodyH) {
        return renderRow(g, t, n, y, mx, my, bodyY, bodyH, null, 0);
    }

    /**
     * Строка узла. {@code path} не null: дерево общей суммы, только для чтения (оно пересобирается раз в секунду,
     * поэтому свёрнутые ветки помнятся по пути, а рецепт выбирается в дереве самой цели). {@code shift}: на сколько
     * уровней сдвинуть влево (синтетический корень суммы не показывается).
     */
    private boolean renderRow(GuiGraphics g, Theme t, Node n, int y, int mx, int my, int bodyY, int bodyH, @Nullable String path, int shift) {
        boolean readOnly = path != null;
        boolean collapsed = readOnly ? collapsedPaths.contains(path) : n.collapsed;
        int depth = n.depth - shift;
        boolean hov = my >= bodyY && my < bodyY + bodyH && over(mx, my, px + 4, y, pw - 8, ROW);
        if (hov) Draw.round(g, px + 5, y, pw - 10, ROW - 1, 4.5f, Draw.alpha(t.c("slot.hover"), 0.5f));
        float cy = y + ROW / 2f;
        int x = px + PAD + depth * 12;
        for (int d = 1; d <= depth; d++) Draw.round(g, px + PAD + d * 12 - 7, y, 0.5f, ROW, 0.02f, Draw.alpha(t.c("text.dim"), 0.45f));
        hits.add(px + 4, y, pw - 8, ROW, b -> {
            if (readOnly) {
                if (!n.children.isEmpty() && !collapsedPaths.remove(path)) collapsedPaths.add(path);
            } else if (!n.children.isEmpty()) n.collapsed = !n.collapsed;
            else if (n.truncated) tree.expand(n);
        });
        if (!n.children.isEmpty()) {
            int c = t.c("text.muted");
            if (collapsed) Draw.label(g, "›", x + 1, cy, 13, Weight.REGULAR, c);
            else for (int k = 0; k < 3; k++) Draw.rect(g, x + k, y + 9 + k, 6 - k * 2, 1, c);
        }
        n.stack.render(g, x + 9, y + 2);
        hits.add(x + 9, y, 18, ROW, b -> RecipeScreen.open(n.stack, b == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES));
        float right = px + pw - PAD;
        boolean have = n.parent != null && n.satisfied();
        boolean spare = n.parent != null && !have && n.remaining() == 0 && n.fromSurplus > 0;
        String state = "";
        int stateColor = 0;
        if (have) {
            state = tr("ryscope.tree.have");
            stateColor = t.c("state.ok");
        } else if (spare) {
            state = tr("ryscope.tree.from_surplus");
            stateColor = t.c("state.ok");
        } else if (n.ordered) {
            state = Component.translatable("ryscope.tree.ordered", Numbers.compact(n.remaining())).getString();
            stateColor = t.c("state.order");
        } else if (n.chosen() == null) {
            state = tr(n.parent == null ? "ryscope.tree.no_recipe" : "ryscope.tree.raw");
            if (!n.sources.isEmpty()) state += " · " + Draw.ellipsize(n.sources, 110f, 12, Weight.REGULAR);
            stateColor = t.c("state.warn");
        } else if (n.truncated) {
            state = tr("ryscope.tree.truncated");
            stateColor = t.c("state.warn");
        } else if (n.fromInventory > 0) {
            state = Component.translatable("ryscope.tree.have_part", n.fromInventory).getString();
            stateColor = t.c("state.ok");
        }
        if (n.routeFrom != null) {
            // две цели выбрали для ресурса разные рецепты: взят выбор цели выше на доске
            String from = Component.translatable("ryscope.tree.route_from", n.routeFrom.name().getString()).getString();
            state = state.isEmpty() ? from : state + " · " + from;
            if (stateColor == 0) stateColor = t.c("text.dim");
        }
        if (readOnly && !have && !n.ordered && n.chosen() != null) {
            // в сумме рецепт не листается: только значок станции
            try {
                g.pose().pushPose();
                g.pose().translate(right - 13, y + 4, 0);
                g.pose().scale(0.75f, 0.75f, 1f);
                n.chosen().category.renderIcon(g, 0, 0);
                g.pose().popPose();
            } catch (Throwable ignored) {
                g.pose().popPose();
            }
            noStation(g, t, n, (int) right - 13, y, mx, my);
            right -= 17;
        }
        if (!readOnly && n.options != null && !n.options.isEmpty() && !have && !n.ordered) {
            String sw = (n.choice + 1) + " / " + n.options.size();
            float sww = Draw.labelWidth(sw, 13, Weight.REGULAR);
            float nextX = right - 5, prevX = nextX - sww - 10 - 5;
            boolean h1 = over(mx, my, (int) prevX - 2, y + 3, 8, 13), h2 = over(mx, my, (int) nextX - 2, y + 3, 8, 13);
            Draw.label(g, "‹", prevX, cy, 13, Weight.REGULAR, h1 ? t.c("accent") : t.c("button.fg"));
            Draw.label(g, sw, prevX + 8, cy, 13, Weight.REGULAR, t.c("text.muted"));
            Draw.label(g, "›", nextX, cy, 13, Weight.REGULAR, h2 ? t.c("accent") : t.c("button.fg"));
            hits.add((int) prevX - 2, y, 9, ROW, b -> tree.cycleChoice(n, -1));
            hits.add((int) nextX - 2, y, 9, ROW, b -> tree.cycleChoice(n, 1));
            right = prevX - 6;
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
                noStation(g, t, n, (int) right - 13, y, mx, my);
                right -= 17;
            }
        }
        if (!state.isEmpty()) {
            float sw = Draw.labelWidth(state, 12, Weight.REGULAR);
            Draw.label(g, state, right - sw, cy, 12, Weight.REGULAR, stateColor);
            right -= sw + 6;
        }
        String label = n.needed + "× " + n.stack.name().getString();
        Draw.label(g, Draw.ellipsize(label, right - (x + 29), 13, Weight.REGULAR), x + 29, cy, 13, Weight.REGULAR, have ? t.c("text.muted") : t.c("text.body"));
        return hov;
    }

    /** Значок «нет станции» на углу значка категории узла; под курсором запоминает подсказку. */
    private void noStation(GuiGraphics g, Theme t, Node n, int ix, int y, int mx, int my) {
        if (!n.stationMissing || n.chosen() == null) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        Kit.indicator(g, t, ix + 8, y + 2.5f, 5, Kit.State.NONE, -1, -1);
        g.pose().popPose();
        if (over(mx, my, ix, y + 3, 13, 14)) {
            stationTip = Component.translatable("ryscope.tree.no_station", stationName(n.chosen().category)).getString();
        }
    }

    // --- все цели ---

    /** Пути свёрнутых веток в дереве общей суммы: узлы там новые после каждой пересборки. */
    private final java.util.Set<String> collapsedPaths = new java.util.HashSet<>();

    private record AllRow(Node node, String path) {}

    private void flattenAll(Node node, String path, List<AllRow> out) {
        out.add(new AllRow(node, path));
        if (collapsedPaths.contains(path)) return;
        for (Node c : node.children) flattenAll(c, path + "/" + c.stack.id(), out);
    }

    /** Вкладка «Все цели»: общая сумма по доске таблицей сырья или деревом. */
    private void drawAll(GuiGraphics g, Theme t, int mx, int my) {
        stationTip = null;
        Board.wantCombined();
        CraftTree sum = Board.combined();
        renderAllHeader(g, t, sum, mx, my);
        renderTabs(g, t, mx, my);
        int bodyY = py + HEADER, bodyH = ph - HEADER - FOOTER;
        rows.clear();
        if (Board.goals().isEmpty()) {
            Draw.label(g, tr("ryscope.tree.all_none"), px + PAD, bodyY + 14, 13, Weight.REGULAR, t.c("text.muted"));
            return;
        }
        if (sum == null) {
            Loading.render(g, t, px, bodyY, pw, ph - HEADER, "ryscope.tree.loading", Board.combinedExamined());
            return;
        }
        Stack hover = allTree ? renderAllTree(g, t, sum, mx, my, bodyY, bodyH) : renderTable(g, t, sum, mx, my, bodyY, bodyH);
        renderAllFooter(g, t, sum, mx, my);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        if (hover != null) hover.renderTooltip(g, mx, my);
        else if (stationTip != null) Kit.tooltip(g, t, List.of(stationTip, tr("ryscope.tree.no_station.tip")), mx, my);
        else if (autocraftHot) Kit.tooltip(g, t, List.of(tr("ryscope.tree.autocraft.tip")), mx, my);
        g.pose().popPose();
        Overlay.renderToast(this, g);
    }

    private void renderAllHeader(GuiGraphics g, Theme t, @Nullable CraftTree sum, int mx, int my) {
        int iconX = px + PAD, iconY = py + 12;
        Kit.iconTile(g, t, iconX, iconY);
        List<Board.Goal> goals = Board.goals();
        if (!goals.isEmpty()) goals.get(0).stack.render(g, iconX + 4, iconY + 4);
        int closeX = px + pw - PAD - 18;
        Kit.close(g, t, hits, closeX, py + 14, mx, my, this::onClose);
        String sub = "";
        if (sum != null) {
            sub = Component.translatable("ryscope.tree.all_summary", sum.goalCount(), sum.steps).getString();
            if (sum.incomplete) sub += " · " + tr("ryscope.tree.all_incomplete");
        }
        float textX = iconX + 23 + 7;
        Kit.headerText(g, t, tr("ryscope.tree.all_goals"), sub, textX, py, closeX - 7 - textX);
    }

    /** Дерево общей суммы без синтетического корня: цели верхним уровнем. */
    @Nullable
    private Stack renderAllTree(GuiGraphics g, Theme t, CraftTree sum, int mx, int my, int bodyY, int bodyH) {
        List<AllRow> list = new ArrayList<>();
        for (Node goal : sum.root.children) flattenAll(goal, goal.stack.id().toString(), list);
        float max = Math.max(0, list.size() * ROW - bodyH);
        scroll = scroller.update(max);
        hits.clip(bodyY, bodyY + bodyH);
        Draw.scissor(g, px + 1, bodyY, px + pw - 1, bodyY + bodyH);
        Stack hover = null;
        for (int i = 0; i < list.size(); i++) {
            int y = bodyY + i * ROW - Math.round(scroll);
            if (y + ROW < bodyY || y > bodyY + bodyH) continue;
            AllRow r = list.get(i);
            boolean hot = renderRow(g, t, r.node(), y, mx, my, bodyY, bodyH, r.path(), 1);
            if (hot && over(mx, my, px + PAD + (r.node().depth - 1) * 12 + 9, 0, 18, height)) hover = r.node().stack;
        }
        g.disableScissor();
        hits.unclip();
        return hover;
    }

    /** Колонки таблицы сырья справа налево: ширина одной колонки числа. */
    private static final int COL = 50;

    /**
     * Итог сырья по всем целям одной таблицей: нужно, в инвентаре, в запомненных хранилищах, закажет сеть, осталось.
     * Сначала то, что ещё добывать, потом заказ в сети, потом то, что уже есть.
     */
    @Nullable
    private Stack renderTable(GuiGraphics g, Theme t, CraftTree sum, int mx, int my, int bodyY, int bodyH) {
        List<CraftTree.Leaf> leaves = new ArrayList<>(sum.leaves.values());
        leaves.sort((a, b) -> a.left != b.left ? Long.compare(b.left, a.left)
            : a.ordered != b.ordered ? Long.compare(b.ordered, a.ordered) : Long.compare(b.need, a.need));
        boolean storage = false, order = false;
        for (CraftTree.Leaf l : leaves) {
            storage |= l.storage > 0;
            order |= l.ordered > 0;
        }
        storage |= works.repka.ryscope.runtime.Storages.inRange() > 0;
        List<String> heads = new ArrayList<>(List.of(tr("ryscope.tree.col.need"), tr("ryscope.tree.col.inventory")));
        if (storage) heads.add(tr("ryscope.tree.col.storage"));
        if (order) heads.add(tr("ryscope.tree.col.order"));
        heads.add(tr("ryscope.tree.col.left"));
        int right = px + pw - PAD;
        int firstCol = right - heads.size() * COL;
        // шапка таблицы
        float hy = bodyY + 7;
        Kit.caps(g, t, tr("ryscope.tree.col.item"), px + PAD + 10, hy, 10, 0.4f);
        for (int i = 0; i < heads.size(); i++) {
            String h = heads.get(i);
            float w = Kit.capsWidth(h, 10, 0.4f);
            Kit.caps(g, t, Draw.ellipsize(h, COL - 4, 10, Weight.MEDIUM), firstCol + (i + 1) * COL - Math.min(w, COL - 4), hy, 10, 0.4f);
        }
        int top = bodyY + 14, height = bodyH - 14;
        Kit.divider(g, t, px + PAD, top - 0.5f, pw - PAD * 2);
        float max = Math.max(0, leaves.size() * ROW - height);
        scroll = scroller.update(max);
        hits.clip(top, top + height);
        Draw.scissor(g, px + 1, top, px + pw - 1, top + height);
        Stack hover = null;
        for (int i = 0; i < leaves.size(); i++) {
            int y = top + i * ROW - Math.round(scroll);
            if (y + ROW < top || y > top + height) continue;
            CraftTree.Leaf l = leaves.get(i);
            boolean hov = my >= top && my < top + height && over(mx, my, px + 4, y, pw - 8, ROW);
            if (hov) Draw.round(g, px + 5, y, pw - 10, ROW - 1, 4.5f, Draw.alpha(t.c("slot.hover"), 0.5f));
            float cy = y + ROW / 2f;
            int x = px + PAD;
            Kit.State state = l.left == 0 ? (l.ordered > 0 ? Kit.State.ORDER : Kit.State.FULL)
                : (l.inventory + l.storage + l.ordered > 0 ? Kit.State.PARTIAL : Kit.State.NONE);
            Kit.indicator(g, t, x, cy - 3, 6, state, -1, -1);
            l.stack.render(g, x + 10, y + 2);
            if (my >= top && my < top + height && over(mx, my, x + 10, y + 2, 16, 16)) hover = l.stack;
            Stack stack = l.stack;
            hits.add(px + 4, y, pw - 8, ROW, b -> RecipeScreen.open(stack, b == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES));
            Draw.label(g, Draw.ellipsize(l.stack.name().getString(), firstCol - (x + 30) - 4, 13, Weight.REGULAR), x + 30, cy, 13, Weight.REGULAR,
                state == Kit.State.FULL ? t.c("text.muted") : t.c("text.body"));
            List<long[]> cols = new ArrayList<>();
            cols.add(new long[] {l.need, 0});
            cols.add(new long[] {l.inventory, 1});
            if (storage) cols.add(new long[] {l.storage, 1});
            if (order) cols.add(new long[] {l.ordered, 2});
            cols.add(new long[] {l.left, 3});
            for (int c = 0; c < cols.size(); c++) {
                long v = cols.get(c)[0];
                int kind = (int) cols.get(c)[1];
                String text = v == 0 && kind != 0 ? "·" : Numbers.compact(v);
                int color = v == 0 ? t.c("text.dim") : switch (kind) {
                    case 2 -> t.c("state.order");
                    case 3 -> t.c(l.truncated ? "state.warn" : "state.bad");
                    default -> t.c("text.body");
                };
                float w = Draw.labelWidth(text, 13, Weight.REGULAR);
                Draw.label(g, text, firstCol + (c + 1) * COL - w, cy, 13, Weight.REGULAR, color);
            }
        }
        g.disableScissor();
        hits.unclip();
        return hover;
    }

    private void renderAllFooter(GuiGraphics g, Theme t, CraftTree sum, int mx, int my) {
        int fy = py + ph - FOOTER;
        Kit.divider(g, t, px + 0.5f, fy, pw - 1);
        String share = tr("ryscope.tree.to_chat");
        int bw = (int) Math.ceil(Kit.buttonWidth(share, 12)), bx = px + pw - PAD - bw;
        Kit.button(g, t, hits, bx, fy + 6, bw, 18, share, false, mx, my, () -> Share.toChat(sum));
        float room = bx - px - PAD - 8;
        float ly = fy + 12;
        for (String line : Draw.wrapLabel(tr("ryscope.tree.all_note"), room, 12, Weight.REGULAR, 2)) {
            Draw.label(g, line, px + PAD, ly, 12, Weight.REGULAR, t.c("text.muted"));
            ly += 9;
        }
        String extra = "";
        if (!sum.missingStations.isEmpty()) extra = missingStations(sum);
        if (sum.incomplete) extra = extra.isEmpty() ? tr("ryscope.tree.all_incomplete.tip") : extra + " · " + tr("ryscope.tree.all_incomplete.tip");
        if (!sum.ordered.isEmpty() && sum.orderedAt > 0) {
            String at = Component.translatable("ryscope.tree.order_at", clock(sum.orderedAt)).getString();
            extra = extra.isEmpty() ? Component.translatable("ryscope.tree.col.order").getString() + ": " + at : extra + " · " + at;
        }
        if (!extra.isEmpty()) {
            Draw.label(g, Draw.ellipsize(extra, pw - PAD * 2, 12, Weight.REGULAR), px + PAD, fy + 40, 12, Weight.REGULAR,
                sum.incomplete || !sum.missingStations.isEmpty() ? t.c("state.warn") : t.c("text.dim"));
        }
    }

    /** Блок «Заказать в сети: N» над итогом: что сеть сделает сама, с временем данных. @return предмет под курсором */
    @Nullable
    private Stack renderOrdered(GuiGraphics g, Theme t, int mx, int my) {
        if (tree.ordered.isEmpty()) return null;
        int oy = py + ph - FOOTER - ORDER_H;
        Kit.divider(g, t, px + 0.5f, oy, pw - 1);
        String caption = Component.translatable("ryscope.tree.to_order", tree.ordered.size()).getString();
        if (tree.orderedAt > 0) caption += " · " + Component.translatable("ryscope.tree.order_at", clock(tree.orderedAt)).getString();
        Kit.section(g, t, Draw.ellipsize(caption, pw - PAD * 2, 12, Weight.REGULAR), px + PAD, oy + 9);
        return tiles(g, t, tree.ordered.values(), px + PAD, oy + 14, px + pw - PAD, true, mx, my);
    }

    /** Время данных «сеть сделает»: ЧЧ:ММ. */
    static String clock(long millis) {
        return java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Ряд плашек сырья или заказа: предмет, количество, отметка. @return предмет под курсором */
    @Nullable
    private Stack tiles(GuiGraphics g, Theme t, java.util.Collection<CraftTree.Raw> list, int rx, int ry, int end, boolean order, int mx, int my) {
        Stack hover = null;
        for (CraftTree.Raw r : list) {
            if (rx + 20 > end - 8) {
                Draw.label(g, "…", rx + 2, ry + 10, 13, Weight.REGULAR, t.c("text.dim"));
                break;
            }
            boolean hot = over(mx, my, rx, ry, 20, 20);
            Kit.tile(g, t, rx, ry, hot);
            r.stack.render(g, rx + 2, ry + 2);
            Kit.count(g, r.need >= 1000 ? (r.need / 1000) + "k" : Long.toString(r.need), rx, ry);
            if (order) Kit.indicator(g, t, rx + 14.5f, ry + 1f, 4.5f, Kit.State.ORDER, -1, -1);
            else if (r.truncated) Kit.indicator(g, t, rx + 14.5f, ry + 1f, 4.5f, Kit.State.PARTIAL, -1, -1);
            if (hot) hover = r.stack;
            Stack stack = r.stack;
            hits.add(rx, ry, 20, 20, b -> RecipeScreen.open(stack, b == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES));
            rx += 23;
        }
        return hover;
    }

    private Stack renderFooter(GuiGraphics g, Theme t, int mx, int my) {
        int fy = py + ph - FOOTER;
        Kit.divider(g, t, px + 0.5f, fy, pw - 1);
        Board.Goal pinned = Board.goal(tree.root.stack);
        CraftTree.Route route = tree.route(false);
        // цель уже закреплена, но с другим количеством или маршрутом: главное действие обновляет её
        boolean changed = pinned != null && (pinned.amount != tree.amount || !route.sameAs(pinned.route));
        String goal = tr(pinned == null ? "ryscope.tree.pin_goal" : changed ? "ryscope.tree.update_goal" : "ryscope.tree.unpin_goal");
        int gw = (int) Math.ceil(Kit.buttonWidth(goal, 13)), gx = px + pw - PAD - gw, gy = fy + 6;
        Kit.button(g, t, hits, gx, gy, gw, 20, goal, true, mx, my, () -> {
            if (pinned != null && !changed) {
                Board.remove(tree.root.stack);
                Overlay.toast(tr("ryscope.board.removed"));
                return;
            }
            if (pinned == null && tree.root.stack.item().isEmpty()) {
                // доска считает только предметы: жидкость или чужой ресурс целью не станет
                Overlay.toast(tr("ryscope.board.unsupported"));
                return;
            }
            boolean added = Board.set(tree.root.stack, tree.amount, route);
            Overlay.toast(tr(added ? "ryscope.board.added" : "ryscope.board.updated"));
        });
        int tilesEnd = gx;
        if (changed) {
            String unpin = tr("ryscope.tree.unpin_short");
            int uw = (int) Math.ceil(Kit.buttonWidth(unpin, 12));
            tilesEnd = gx - 4 - uw;
            Kit.button(g, t, hits, tilesEnd, gy, uw, 20, unpin, false, mx, my, () -> {
                Board.remove(tree.root.stack);
                Overlay.toast(tr("ryscope.board.removed"));
            });
        }
        Stack rawHover = null;
        if (tree.raw.isEmpty()) {
            String done = tr(tree.ordered.isEmpty() ? "ryscope.tree.all_have" : "ryscope.tree.all_have_order");
            Draw.label(g, Draw.ellipsize(done, tilesEnd - px - PAD - 8, 13, Weight.REGULAR), px + PAD, fy + 14, 13, Weight.REGULAR, t.c("state.ok"));
        } else {
            String pin = tr("ryscope.tree.pin");
            int bw = (int) Math.ceil(Kit.buttonWidth(pin, 12)), by = gy + 24;
            Kit.button(g, t, hits, px + pw - PAD - Math.max(bw, gw), by, Math.max(bw, gw), 17, pin, false, mx, my, () -> {
                int added = 0;
                for (CraftTree.Raw r : tree.raw.values()) {
                    if (!Bookmarks.contains(r.stack) && Bookmarks.toggle(r.stack)) added++;
                }
                Overlay.toast(Component.translatable("ryscope.tree.pinned", added).getString());
            });
            String caption = tr("ryscope.tree.to_gather");
            if (!InventoryView.tracking()) caption += " · " + tr("ryscope.tree.creative");
            else if (works.repka.ryscope.runtime.Storages.inRange() > 0) caption += " · " + Component.translatable("ryscope.tree.storages", works.repka.ryscope.runtime.Storages.inRange()).getString();
            Kit.section(g, t, Draw.ellipsize(caption, tilesEnd - px - PAD - 8, 12, Weight.REGULAR), px + PAD, fy + 11);
            rawHover = tiles(g, t, tree.raw.values(), px + PAD, fy + 23, tilesEnd, false, mx, my);
        }
        return rawHover;
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Nullable
    private Node nodeAt(double mx, double my) {
        int bodyY = py + HEADER, bodyH = ph - HEADER - footerH();
        if (my < bodyY || my >= bodyY + bodyH) return null;
        for (Row r : rows) if (my >= r.y && my < r.y + ROW && mx >= px + 4 && mx < px + pw - 4) return r.node;
        return null;
    }

    @Override
    public void tick() {
        // инвентарь меняется, пока игрок собирает: пересчитываем раз в секунду; при новых данных «сеть сделает»
        // сразу
        long now = CraftTree.inputsVersion();
        boolean changed = now != inputs;
        inputs = now;
        // на вкладке «Все цели» сумму пересчитывает доска, а дерево предмета ждёт
        if (!all && tree.ready() && (++refresh % 20 == 0 || changed)) tree.recompute();
    }

    /** Признак входных данных дерева, по которому оно посчитано в последний раз. */
    private long inputs = CraftTree.inputsVersion();

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (hits.click(mx, my, button)) return true;
        if (!over(mx, my, px, py, pw, ph)) onClose();
        return true;
    }

    @Override
    protected boolean onScroll(double mx, double my, double scrollX, double scrollY) {
        if (all) {
            scroller.by(-(float) scrollY * ROW * 2);
            return true;
        }
        if (!tree.ready()) return true;
        int ax = px + pw - PAD - 18 - 7 - 72, ay = py + 14;
        if (over(mx, my, ax, ay, 72, 18)) {
            tree.setAmount(tree.amount + (scrollY > 0 ? 1 : -1) * (hasShiftDown() ? 8 : 1));
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
        if (all || !tree.ready()) return true;
        Node n = nodeAt(Overlay.mouseX(), Overlay.mouseY());
        if (n != null) {
            if (Keys.matches(Keys.RECIPES, key, scan)) return RecipeScreen.open(n.stack, Lookup.Mode.RECIPES);
            if (Keys.matches(Keys.USES, key, scan)) return RecipeScreen.open(n.stack, Lookup.Mode.USES);
            if (Keys.matches(Keys.BOOKMARK, key, scan)) {
                Overlay.toggleBookmark(n.stack);
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
        if (all) return null;
        Node n = nodeAt(Overlay.mouseX(), Overlay.mouseY());
        return n == null || n.chosen() == null ? null : n.chosen().category;
    }
}
