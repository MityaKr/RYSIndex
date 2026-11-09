package works.repka.ryscope.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscope.client.Keys;
import works.repka.ryscope.content.HelpContent;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.RecipeView.Transfer;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Bookmarks;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.Lookup.Group;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Type.Weight;

/**
 * Карточка предмета, размеры из {@code design/Main.dc.html}. Числа в единицах GUI: половина пикселя макета.
 * Шапка, разделы, строка категории, рецепт, строка наличия.
 */
public final class RecipeScreen extends Screen {
    private static final int CARD_W = 260;
    private static final int PAD_X = 9;
    private static final float HEADER_H = 44f;
    private static final float TABS_H = 24.5f;
    private static final int BODY_TOP = 8;
    private static final int GAP = 7;
    private static final int STRIP_H = 22;
    private static final int TAB_W = 22;
    private static final int MAX_TABS = 11;
    private static final int ROW_H_CATEGORY = 9;
    private static final int STATUS_H = 27;
    private static final int BOTTOM = 9;
    private static final int BUTTONS_W = 50;
    private static final int MANY_RECIPES = 6;

    /** Разделы карточки. Пятый, «Граф», открывает дерево крафта. */
    private enum Section { RECIPES, USES, SOURCES, HELP }

    private record Tab(Stack stack, Lookup.Mode mode) {}

    private record Hit(int x, int y, int w, int h, IntConsumer action) {}

    private record Status(String head, String tail, String link, String color) {}

    private record Placed(RecipeView recipe, int x, int y, int rowX, int rowY, int rowW, int rowH) {}

    private record StationSpot(Stack stack, int x, int y) {}

    private static final List<Tab> TABS = new ArrayList<>();

    private static void remember(Stack stack, Lookup.Mode mode) {
        for (int i = 0; i < TABS.size(); i++) {
            if (TABS.get(i).stack.same(stack)) {
                TABS.set(i, new Tab(stack, mode));
                return;
            }
        }
        TABS.add(new Tab(stack, mode));
        while (TABS.size() > MAX_TABS) TABS.remove(0);
    }

    public static void clearTabs() {
        TABS.clear();
    }

    public static int tabCount() {
        return TABS.size();
    }

    private static final class Nav {
        @Nullable final Stack focus;
        Section section;
        final Map<Section, List<Group>> sections;
        List<Group> groups;
        int group;
        int page;

        Nav(@Nullable Stack focus, Section section, Map<Section, List<Group>> sections) {
            this.focus = focus;
            this.section = section;
            this.sections = sections;
            this.groups = sections.get(section);
        }
    }

    @Nullable
    private final Screen parent;
    private final Deque<Nav> history = new ArrayDeque<>();
    private Nav nav;
    private int cardX, cardY, cardW, cardH;
    private int perPage = 1, rowH;
    private int widestCard;
    private int tabScroll;
    private int stripY;
    private boolean strip;
    private final List<Hit> hits = new ArrayList<>();
    private final List<StationSpot> stations = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();
    private final Map<RecipeView, Transfer> transfers = new IdentityHashMap<>();
    private final Map<RecipeView, Status> statuses = new IdentityHashMap<>();
    private int transferAge;

    private RecipeScreen(@Nullable Screen parent, Nav nav) {
        super(Component.translatable("ryscope.recipes.title"));
        this.parent = parent;
        this.nav = nav;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** Формы множественного числа: окончание ключа one, few или many. */
    private static String plural(String key, int n) {
        int a = Math.abs(n) % 100, b = a % 10;
        String form = a >= 11 && a <= 14 ? "many" : b == 1 ? "one" : b >= 2 && b <= 4 ? "few" : "many";
        return tr(key + "." + form, n);
    }

    private static Map<Section, List<Group>> split(Stack stack) {
        Map<Section, List<Group>> out = new EnumMap<>(Section.class);
        for (Section s : Section.values()) out.put(s, new ArrayList<>());
        for (Group g : Lookup.find(stack, Lookup.Mode.RECIPES)) {
            boolean crafting = false;
            for (RecipeView r : g.recipes()) crafting |= r.isCrafting();
            Section s = g.category().id.equals(HelpContent.CATEGORY) ? Section.HELP : crafting ? Section.RECIPES : Section.SOURCES;
            out.get(s).add(g);
        }
        for (Group g : Lookup.find(stack, Lookup.Mode.USES)) {
            (g.category().id.equals(HelpContent.CATEGORY) ? out.get(Section.HELP) : out.get(Section.USES)).add(g);
        }
        return out;
    }

    private static int total(List<Group> groups) {
        int sum = 0;
        for (Group g : groups) sum += g.recipes().size();
        return sum;
    }

    /** Открывает рецепты или использования ресурса. Если их нет, показывает уведомление и ничего не открывает. */
    public static boolean open(Stack stack, Lookup.Mode mode) {
        Stack focus = stack.withAmount(1);
        Map<Section, List<Group>> sections = split(focus);
        Section wanted = mode == Lookup.Mode.RECIPES ? Section.RECIPES : Section.USES;
        Section section = wanted;
        if (sections.get(section).isEmpty()) {
            section = null;
            for (Section s : new Section[] {Section.RECIPES, Section.SOURCES, Section.USES, Section.HELP}) {
                if (!sections.get(s).isEmpty()) {
                    section = s;
                    break;
                }
            }
            if (section == null) {
                Overlay.toast(tr("ryscope.recipes.none", stack.name()));
                return false;
            }
            if (section == Section.USES || wanted == Section.USES) {
                Overlay.toast(tr(mode == Lookup.Mode.RECIPES ? "ryscope.recipes.only_uses" : "ryscope.recipes.only_recipes"));
            }
        }
        remember(focus, section == Section.USES ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
        show(Minecraft.getInstance(), new Nav(focus, section, sections));
        return true;
    }

    public static boolean openCategory(Category category) {
        List<Group> groups = Lookup.category(category);
        if (groups.isEmpty()) return false;
        Map<Section, List<Group>> sections = new EnumMap<>(Section.class);
        for (Section s : Section.values()) sections.put(s, s == Section.RECIPES ? groups : List.of());
        show(Minecraft.getInstance(), new Nav(null, Section.RECIPES, sections));
        return true;
    }

    private static void show(Minecraft mc, Nav nav) {
        if (mc.screen instanceof RecipeScreen rs) {
            rs.history.push(rs.nav);
            rs.nav = nav;
            rs.tabScroll = 0;
            rs.transfers.clear();
            rs.statuses.clear();
        } else {
            mc.setScreen(new RecipeScreen(mc.screen, nav));
        }
    }

    /** Ресурс, рецепты которого сейчас показаны, или null для просмотра категории. */
    @Nullable
    public Stack focus() {
        return nav.focus;
    }

    public int[] cardBounds() {
        return new int[] {cardX, cardY, cardW, cardH};
    }

    private Group group() {
        return nav.groups.get(Math.min(nav.group, nav.groups.size() - 1));
    }

    private int pages() {
        return Math.max(1, (group().recipes().size() + perPage - 1) / perPage);
    }

    // --- вкладки открытых предметов над карточкой ---

    private int tabStart() {
        return Math.max(0, TABS.size() - Math.max(1, cardW / TAB_W));
    }

    private int currentTab() {
        if (nav.focus == null) return -1;
        for (int i = 0; i < TABS.size(); i++) if (TABS.get(i).stack.same(nav.focus)) return i;
        return -1;
    }

    private void cycleTab(int step) {
        if (TABS.isEmpty()) return;
        int cur = currentTab();
        Tab next = TABS.get(Math.floorMod((cur < 0 ? 0 : cur) + step, TABS.size()));
        if (nav.focus == null || !next.stack.same(nav.focus)) open(next.stack, next.mode);
    }

    private void closeTab(int index) {
        Tab closed = TABS.remove(index);
        if (nav.focus == null || !closed.stack.same(nav.focus)) return;
        if (TABS.isEmpty()) {
            onClose();
            return;
        }
        Tab next = TABS.get(Math.min(index, TABS.size() - 1));
        if (open(next.stack, next.mode)) {
            if (!history.isEmpty()) history.pop();
        } else {
            onClose();
        }
    }

    // --- раскладка ---

    private boolean categoryStrip() {
        return nav.groups.size() > 1;
    }

    private int chromeHeight() {
        return (int) Math.ceil(HEADER_H + (nav.focus != null ? TABS_H : 0)) + BODY_TOP + ROW_H_CATEGORY + GAP + STATUS_H + GAP + BOTTOM;
    }

    private void relayout() {
        List<RecipeView> recipes = group().recipes();
        int maxW = 124, maxH = 20;
        for (RecipeView r : recipes) {
            maxW = Math.max(maxW, r.width());
            maxH = Math.max(maxH, r.height());
        }
        int plate = !recipes.isEmpty() && recipes.get(0).needsPlate() ? 3 : 0;
        rowH = Math.max(maxH + plate * 2, 30);
        widestCard = Math.max(widestCard, Math.max(CARD_W, maxW + plate * 2 + BUTTONS_W + 8 + PAD_X * 2));
        cardW = Math.min(width - 8, widestCard);
        int sidebar = Overlay.sidebarLeft(this);
        // как в макете: карточка смещена вправо внутри свободной части, чтобы слева было видно инвентарь
        cardX = Math.max(categoryStrip() ? 28 : 4, Math.min(sidebar - cardW - 4, (sidebar - cardW) * 5 / 6));
        strip = TABS.size() > 1;
        cardY = Math.max(strip ? STRIP_H + 2 : 4, height * 36 / 400);
        int chrome = chromeHeight();
        int avail = height - cardY - 4 - chrome;
        // немного рецептов листаются по одному, как в макете; длинные списки занимают всю высоту
        int fit = Math.max(1, (avail + GAP) / (rowH + GAP));
        perPage = recipes.size() <= MANY_RECIPES ? 1 : Math.min(recipes.size(), fit);
        int needed = chrome + perPage * (rowH + GAP) - GAP;
        cardH = Math.min(height - cardY - 4, needed);
        nav.page = Math.max(0, Math.min(nav.page, pages() - 1));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, ThemeManager.get().c("overlay"));
    }

    private void hit(int x, int y, int w, int h, IntConsumer action) {
        hits.add(new Hit(x, y, w, h, action));
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme t = ThemeManager.get();
        hits.clear();
        relayout();
        renderBackground(g, mx, my, partialTick);
        Overlay.renderBackground(this, g, mx, my, partialTick);
        t.panel(g, cardX, cardY, cardW, cardH);
        renderStrip(g, t, mx, my);
        renderHeader(g, t, mx, my);
        float y = cardY + HEADER_H;
        if (nav.focus != null) {
            renderSections(g, t, mx, my, y);
            y += TABS_H;
        }
        int by = (int) Math.ceil(y) + BODY_TOP;
        Category hoveredTab = null;
        if (categoryStrip()) hoveredTab = renderCategories(g, t, mx, my);
        renderCategoryRow(g, t, mx, my, by);
        by += ROW_H_CATEGORY + GAP;
        renderRecipes(g, t, mx, my, partialTick, by);

        Overlay.renderTop(this, g, mx, my, partialTick);
        renderTooltips(g, t, mx, my, hoveredTab);
    }

    private void renderStrip(GuiGraphics g, Theme t, int mx, int my) {
        if (!strip) return;
        stripY = cardY - STRIP_H + 1;
        int start = tabStart();
        for (int i = start; i < TABS.size(); i++) {
            int sx = cardX + (i - start) * TAB_W, index = i;
            Tab tab = TABS.get(i);
            boolean current = nav.focus != null && tab.stack.same(nav.focus);
            t.tab(g, sx, stripY, TAB_W - 2, 20, current, over(mx, my, sx, stripY, TAB_W - 2, 20));
            tab.stack.render(g, sx + 2, stripY + 2);
            hit(sx, stripY, TAB_W - 2, 20, button -> {
                if (button != 0) closeTab(index);
                else if (nav.focus == null || !tab.stack.same(nav.focus)) open(tab.stack, tab.mode);
            });
        }
    }

    private void renderHeader(GuiGraphics g, Theme t, int mx, int my) {
        int iconX = cardX + PAD_X;
        int iconY = cardY + 12;
        Draw.roundBox(g, iconX, iconY, 23, 23, 5f, t.c("slot.bg"), t.c("card.border"));
        Stack shown = nav.focus;
        if (shown != null) shown.render(g, iconX + 4, iconY + 4);
        else group().category().renderIcon(g, iconX + 4, iconY + 4);
        int buttonY = cardY + 14;
        int closeX = cardX + cardW - PAD_X - 18, starX = closeX - 18 - 7;
        boolean closeHot = over(mx, my, closeX, buttonY, 18, 18);
        iconButton(g, t, closeX, buttonY, closeHot);
        Draw.cross(g, closeX + 5, buttonY + 5, closeHot ? t.c("accent") : t.c("button.fg"));
        hit(closeX, buttonY, 18, 18, b -> onClose());
        if (shown != null) {
            boolean starHot = over(mx, my, starX, buttonY, 18, 18), marked = Bookmarks.contains(shown);
            iconButton(g, t, starX, buttonY, starHot);
            Draw.star(g, starX + 5, buttonY + 5, marked, marked || starHot ? t.c("accent") : t.c("button.fg"));
            hit(starX, buttonY, 18, 18, b -> Overlay.toast(tr(Bookmarks.toggle(nav.focus) ? "ryscope.bookmark.added" : "ryscope.bookmark.removed")));
        }
        float textX = iconX + 23 + 7;
        float room = starX - 7 - textX;
        String title = shown != null ? shown.name().getString() : group().category().title.getString();
        Draw.label(g, Draw.ellipsize(title, room, 20, Weight.SEMIBOLD), textX, cardY + 15f, 20, Weight.SEMIBOLD, t.c("text.primary"));
        // подзаголовок переносится на вторую строку, как в макете; место под неё есть всегда, поэтому карточка не прыгает
        float subY = cardY + 26f;
        for (String line : Draw.wrapLabel(subtitle(), room, 12, Weight.REGULAR, 2)) {
            Draw.label(g, line, textX, subY, 12, Weight.REGULAR, t.c("text.muted"));
            subY += 8f;
        }
    }

    private void iconButton(GuiGraphics g, Theme t, int x, int y, boolean hot) {
        Draw.roundBox(g, x, y, 18, 18, 4.5f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), t.c("button.border"));
    }

    private String subtitle() {
        if (nav.focus == null) return plural("ryscope.count.recipes", total(nav.groups));
        StringBuilder out = new StringBuilder(nav.focus.id().toString());
        List<String> tags = nav.focus.tags();
        if (!tags.isEmpty()) out.append(" · #").append(tags.get(0));
        out.append(" · ").append(plural("ryscope.count.recipes", total(nav.sections.get(Section.RECIPES))));
        out.append(" · ").append(plural("ryscope.count.uses", total(nav.sections.get(Section.USES))));
        return out.toString();
    }

    private void renderSections(GuiGraphics g, Theme t, int mx, int my, float y) {
        Draw.round(g, cardX + 0.5f, y + TABS_H - 0.5f, cardW - 1, 0.5f, 0.02f, t.c("card.border"));
        int x = cardX + PAD_X;
        int top = (int) y, h = (int) Math.ceil(TABS_H);
        for (Section s : Section.values()) {
            List<Group> groups = nav.sections.get(s);
            int count = total(groups);
            String label = tr("ryscope.section." + s.name().toLowerCase(java.util.Locale.ROOT));
            x = sectionTab(g, t, mx, my, x, top, h, label, s != Section.HELP ? count : -1, nav.section == s, count > 0 ? b -> switchSection(s) : null);
        }
        sectionTab(g, t, mx, my, x, top, h, tr("ryscope.section.graph"), -1, false, b -> TreeScreen.open(nav.focus));
    }

    private int sectionTab(GuiGraphics g, Theme t, int mx, int my, int x, int top, int h, String label, int count, boolean active, @Nullable IntConsumer action) {
        Weight weight = active ? Weight.MEDIUM : Weight.REGULAR;
        String number = count >= 0 ? Integer.toString(count) : "";
        // ширина как у текста в одну строку, а число стоит под названием: так вкладки выглядят в макете
        float natural = Draw.labelWidth(number.isEmpty() ? label : label + " " + number, 13, weight);
        int w = (int) Math.ceil(natural) + 12;
        boolean hot = action != null && over(mx, my, x, top, w, h);
        t.tabLine(g, x, top, w, h, active, hot);
        Draw.label(g, label, x + 6, top + 7.5f, 13, weight, active || hot ? t.c("text.primary") : t.c("text.muted"));
        if (!number.isEmpty()) Draw.label(g, number, x + 6, top + 15.5f, 13, Weight.REGULAR, t.c("text.muted"));
        if (action != null) hit(x, top, w, h, action);
        return x + w + 2;
    }

    /** Значки категорий столбцом слева от карточки: сама карточка остаётся как в макете. */
    @Nullable
    private Category renderCategories(GuiGraphics g, Theme t, int mx, int my) {
        int x = cardX - 25, top = cardY + 8;
        int visible = Math.max(1, (height - top - 4) / 24);
        tabScroll = Math.max(0, Math.min(tabScroll, Math.max(0, nav.groups.size() - visible)));
        if (nav.group < tabScroll) tabScroll = nav.group;
        if (nav.group >= tabScroll + visible) tabScroll = nav.group - visible + 1;
        Category hovered = null;
        for (int i = tabScroll; i < Math.min(nav.groups.size(), tabScroll + visible); i++) {
            int ty = top + (i - tabScroll) * 24, index = i;
            boolean hov = over(mx, my, x, ty, 22, 22);
            t.tab(g, x, ty, 22, 22, i == nav.group, hov);
            try {
                nav.groups.get(i).category().renderIcon(g, x + 3, ty + 3);
            } catch (Throwable ignored) {
                // значок чужой категории
            }
            if (hov) hovered = nav.groups.get(i).category();
            hit(x, ty, 22, 22, b -> setGroup(index));
        }
        return hovered;
    }

    private void renderCategoryRow(GuiGraphics g, Theme t, int mx, int my, int y) {
        Group group = group();
        float cy = y + ROW_H_CATEGORY / 2f;
        int x = cardX + PAD_X;
        stations.clear();
        Stack mainStation = group.category().stations.isEmpty() ? null : group.category().stations.get(0);
        if (mainStation != null) {
            renderSmall(g, mainStation, x, y);
            stations.add(new StationSpot(mainStation, x, y));
        }
        float textX = x + (mainStation != null ? 13 : 0);
        int pageRight = cardX + cardW - PAD_X;
        String pageText = (nav.page + 1) + " / " + pages();
        float pageW = Draw.labelWidth(pageText, 13, Weight.REGULAR);
        float nextX = pageRight - 5, prevX = nextX - 10;
        boolean multi = pages() > 1;
        int prevC = multi && over(mx, my, (int) prevX - 2, y - 1, 8, 11) ? t.c("accent") : t.c("button.fg");
        int nextC = multi && over(mx, my, (int) nextX - 2, y - 1, 8, 11) ? t.c("accent") : t.c("button.fg");
        Draw.label(g, "‹", prevX, cy, 13, Weight.REGULAR, prevC);
        Draw.label(g, "›", nextX, cy, 13, Weight.REGULAR, nextC);
        Draw.label(g, pageText, prevX - 5 - pageW, cy, 13, Weight.REGULAR, t.c("text.muted"));
        hit((int) prevX - 2, y - 1, 8, 11, b -> setPage(nav.page - 1));
        hit((int) nextX - 2, y - 1, 8, 11, b -> setPage(nav.page + 1));
        float titleRoom = prevX - 5 - pageW - 8 - textX;
        String title = Draw.ellipsize(group.category().title.getString(), titleRoom, 13, Weight.REGULAR);
        Draw.label(g, title, textX, cy, 13, Weight.REGULAR, t.c("text.secondary"));
        // остальные станции, на которых делается то же самое
        float stx = textX + Draw.labelWidth(title, 13, Weight.REGULAR) + 6;
        for (int i = 1; i < group.category().stations.size() && stations.size() < 6; i++) {
            if (stx + 9 > prevX - 5 - pageW - 6) break;
            Stack station = group.category().stations.get(i);
            renderSmall(g, station, (int) stx, y);
            stations.add(new StationSpot(station, (int) stx, y));
            stx += 11;
        }
    }

    private static void renderSmall(GuiGraphics g, Stack stack, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.5625f, 0.5625f, 1f);
        try {
            stack.render(g, 0, 0);
        } catch (Throwable ignored) {
            // чужой рендер
        }
        g.pose().popPose();
    }

    private void renderRecipes(GuiGraphics g, Theme t, int mx, int my, float partialTick, int top) {
        placed.clear();
        List<RecipeView> recipes = group().recipes();
        boolean canTransfer = parent instanceof AbstractContainerScreen<?>;
        works.repka.ryscope.content.NativeRecipe.missingBadges = true;
        works.repka.ryscope.runtime.Ae2Terminal.setActive(parent instanceof AbstractContainerScreen<?> host ? host.getMenu() : null);
        int rowX = cardX + PAD_X, rowW = cardW - PAD_X * 2;
        int by = top;
        for (int i = 0; i < perPage; i++) {
            int idx = nav.page * perPage + i;
            if (idx >= recipes.size()) break;
            RecipeView r = recipes.get(idx);
            int plate = r.needsPlate() ? 3 : 0;
            int rx = rowX + plate, ry = by + (rowH - r.height()) / 2;
            if (plate > 0) t.plate(g, rx - plate, ry - plate, r.width() + plate * 2, r.height() + plate * 2);
            try {
                r.render(g, t, rx, ry, mx, my, partialTick);
            } catch (Throwable th) {
                Draw.text(g, "?", rx, ry, t.c("state.bad"));
            }
            Placed row = new Placed(r, rx, ry, rowX, by, rowW, rowH);
            placed.add(row);
            renderLock(g, t, r, rowX, by, rowW);
            if (Minecraft.getInstance().options.advancedItemTooltips && r.id() != null) {
                Draw.label(g, Draw.ellipsize(r.id().toString(), rowW, 9, Weight.REGULAR), rowX, by + rowH + 2, 9, Weight.REGULAR, t.c("text.dim"));
            }
            renderButtons(g, t, row, mx, my, canTransfer);
            by += rowH + GAP;
        }
        renderStatus(g, t, placed.isEmpty() ? null : placed.get(0).recipe, rowX, rowW, by);
    }

    private void renderLock(GuiGraphics g, Theme t, RecipeView r, int x, int y, int w) {
        works.repka.ryscope.runtime.Stages.Stage lock = works.repka.ryscope.runtime.Stages.lockOf(r, group().category());
        if (lock == null) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 260);
        Draw.round(g, x, y, w, rowH, t.r("card.radius"), Draw.alpha(t.c("card.bg"), 0.78f));
        String text = tr("ryscope.stage.locked", lock.title());
        float textW = Draw.labelWidth(text, 13, Weight.MEDIUM);
        float cx = x + (w - textW) / 2f;
        Draw.lock(g, (int) cx - 11, y + rowH / 2 - 8, t.c("state.warn"));
        Draw.label(g, text, cx, y + rowH / 2f - 5, 13, Weight.MEDIUM, t.c("text.primary"));
        String need = works.repka.ryscope.runtime.Stages.requirement(lock);
        if (!need.isEmpty()) {
            String shown = Draw.ellipsize(need, w - 12, 11, Weight.REGULAR);
            Draw.label(g, shown, x + (w - Draw.labelWidth(shown, 11, Weight.REGULAR)) / 2f, y + rowH / 2f + 6, 11, Weight.REGULAR, t.c("text.muted"));
        }
        g.pose().popPose();
    }

    private boolean canGoal(RecipeView r) {
        return nav.focus != null && r.isCrafting() && !r.outputs().isEmpty();
    }

    private void renderButtons(GuiGraphics g, Theme t, Placed row, int mx, int my, boolean canTransfer) {
        RecipeView r = row.recipe;
        int x = row.rowX + row.rowW - BUTTONS_W;
        Transfer tr = transfers.get(r);
        boolean transfer = canTransfer && (tr == null || tr.status() != Transfer.Status.UNSUPPORTED);
        boolean goal = canGoal(r);
        int total = (transfer ? 20 : 0) + (transfer && goal ? 4 : 0) + (goal ? 17 : 0);
        int y = row.rowY + (row.rowH - total) / 2;
        if (transfer) {
            // главная кнопка всегда янтарная, как в макете; чего не хватает, видно по строке наличия и подсказке
            boolean hot = over(mx, my, x, y, BUTTONS_W, 20);
            int fill = hot ? Draw.mix(t.c("accent"), 0xFFFFFFFF, 0.15f) : t.c("accent");
            Draw.round(g, x, y, BUTTONS_W, 20, 4.5f, fill);
            String label = tr("ryscope.transfer.button");
            Draw.label(g, label, x + (BUTTONS_W - Draw.labelWidth(label, 13, Weight.SEMIBOLD)) / 2f, y + 10f, 13, Weight.SEMIBOLD, t.c("accent.text"));
            hit(x, y, BUTTONS_W, 20, b -> {
                if (b == 0 && parent instanceof AbstractContainerScreen<?> acs && tr != null) doTransfer(acs, r, hasShiftDown());
            });
            y += 24;
        }
        if (goal) {
            Stack output = r.outputs().get(0);
            boolean set = works.repka.ryscope.runtime.Board.contains(output), hot = over(mx, my, x, y, BUTTONS_W, 17);
            int fill = hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg");
            Draw.roundBox(g, x, y, BUTTONS_W, 17, 4.5f, fill, t.c("button.border"));
            String label = tr(set ? "ryscope.goals.in" : "ryscope.goals.add");
            Draw.label(g, label, x + (BUTTONS_W - Draw.labelWidth(label, 12, Weight.REGULAR)) / 2f, y + 8.5f, 12, Weight.REGULAR, set ? t.c("accent") : t.c("button.fg"));
            hit(x, y, BUTTONS_W, 17, b -> {
                if (b != 0) return;
                boolean added = works.repka.ryscope.runtime.Board.toggle(output, 1);
                Overlay.toast(tr(added ? "ryscope.goals.added" : "ryscope.goals.removed"));
            });
        }
    }

    private void renderStatus(GuiGraphics g, Theme t, @Nullable RecipeView r, int x, int w, int y) {
        Draw.roundBox(g, x, y, w, STATUS_H, 4.5f, t.c("status.bg"), t.c("status.border"));
        Status s = r == null ? null : statuses.get(r);
        if (s == null) return;
        Draw.dot(g, x + 6, y + STATUS_H / 2 - 2, 4, t.c(s.color));
        record Word(String text, int kind, float width) {}
        List<Word> words = new ArrayList<>();
        for (String part : s.head.split(" ")) words.add(new Word(part, 0, Draw.labelWidth(part, 12.5f, Weight.SEMIBOLD)));
        if (!s.tail.isEmpty()) for (String part : s.tail.split(" ")) words.add(new Word(part, 1, Draw.labelWidth(part, 12.5f, Weight.REGULAR)));
        if (!s.link.isEmpty()) {
            words.add(new Word("·", 1, Draw.labelWidth("·", 12.5f, Weight.REGULAR)));
            for (String part : s.link.split(" ")) words.add(new Word(part, 2, Draw.labelWidth(part, 12.5f, Weight.REGULAR)));
        }
        float space = Draw.labelWidth(" ", 12.5f, Weight.REGULAR);
        float left = x + 15, limit = w - 15 - 6;
        List<List<Word>> lines = new ArrayList<>();
        List<Word> line = new ArrayList<>();
        float used = 0;
        for (Word word : words) {
            if (!line.isEmpty() && used + space + word.width > limit) {
                lines.add(line);
                line = new ArrayList<>();
                used = 0;
            }
            used += (line.isEmpty() ? 0 : space) + word.width;
            line.add(word);
        }
        if (!line.isEmpty()) lines.add(line);
        if (lines.size() > 2) lines = new ArrayList<>(lines.subList(0, 2));
        float cy = y + STATUS_H / 2f - (lines.size() - 1) * 4f;
        for (List<Word> row : lines) {
            float px = left;
            for (Word word : row) {
                int color = word.kind == 0 ? t.c("text.primary") : word.kind == 2 ? t.c("chip.mod.fg") : t.c("text.secondary");
                Draw.label(g, word.text, px, cy, 12.5f, word.kind == 0 ? Weight.SEMIBOLD : Weight.REGULAR, color);
                if (word.kind == 2) Draw.round(g, px, cy + 3.75f, word.width + (word == row.get(row.size() - 1) ? 0 : space), 0.5f, 0.02f, t.c("chip.mod.fg"));
                px += word.width + space;
            }
            cy += 8f;
        }
    }

    @Nullable
    private static Status statusOf(RecipeView recipe) {
        if (!recipe.isCrafting()) return null;
        boolean network = works.repka.ryscope.runtime.Ae2Terminal.active();
        if (!network && !works.repka.ryscope.runtime.InventoryView.tracking()) return null;
        int total = 0, have = 0;
        boolean stored = false;
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Ing in : recipe.inputs()) {
            if (in.isEmpty()) continue;
            boolean items = false;
            for (Stack option : in.options) items |= option.key() instanceof net.minecraft.world.item.Item;
            if (!items) continue;
            total++;
            boolean ok = network
                ? works.repka.ryscope.runtime.Ae2Terminal.stateOf(in) != works.repka.ryscope.runtime.Ae2Terminal.State.MISSING
                : works.repka.ryscope.runtime.InventoryView.has(in);
            if (ok) {
                have++;
                continue;
            }
            missing.merge(in.options.get(0).name().getString(), 1, Integer::sum);
            if (works.repka.ryscope.runtime.Config.get().countStorages) {
                for (Stack option : in.options) {
                    stored |= option.key() instanceof net.minecraft.world.item.Item item && works.repka.ryscope.runtime.Storages.count(item) > 0;
                }
            }
        }
        if (total == 0) return null;
        String place = network ? "network" : "inventory";
        if (missing.isEmpty()) return new Status(tr("ryscope.status.all." + place), "", "", "state.ok");
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : missing.entrySet()) {
            if (shown == 2) break;
            if (shown++ > 0) names.append(", ");
            names.append(e.getKey()).append(" ×").append(e.getValue());
        }
        if (missing.size() > 2) names.append(' ').append(tr("ryscope.status.more", missing.size() - 2));
        String tail = tr("ryscope.status.have." + place) + " · " + tr("ryscope.status.missing", names.toString());
        return new Status(tr("ryscope.status.head", have, total), tail, stored ? tr("ryscope.status.stored") : "", have == 0 ? "state.bad" : "state.warn");
    }

    private void renderTooltips(GuiGraphics g, Theme t, int mx, int my, @Nullable Category hoveredTab) {
        if (Overlay.isOver(mx, my)) return;
        Font font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        Stack hovered = recipeStackAt(mx, my);
        boolean byRecipe = false;
        if (hovered != null && !Lens.held()) {
            for (Placed p : placed) {
                if (over(mx, my, p.x, p.y, p.recipe.width(), p.recipe.height())) {
                    try {
                        byRecipe = p.recipe.renderTooltip(g, p.x, p.y, mx, my);
                    } catch (Throwable ignored) {
                        // подсказка чужого рецепта
                    }
                    break;
                }
            }
        }
        if (hovered != null) {
            if (!Lens.held() && !byRecipe) hovered.renderTooltip(g, mx, my);
        } else if (hoveredTab != null) {
            g.renderTooltip(font, hoveredTab.title, mx, my);
        } else {
            boolean drawn = false;
            for (Placed p : placed) {
                if (over(mx, my, p.x, p.y, p.recipe.width(), p.recipe.height())) {
                    try {
                        drawn = p.recipe.renderTooltip(g, p.x, p.y, mx, my);
                    } catch (Throwable ignored) {
                        // подсказка чужого рецепта
                    }
                    break;
                }
            }
            if (!drawn && parent instanceof AbstractContainerScreen<?>) {
                for (Placed p : placed) {
                    Transfer tr = transfers.get(p.recipe);
                    int x = p.rowX + p.rowW - BUTTONS_W;
                    boolean goal = canGoal(p.recipe);
                    int total = 20 + (goal ? 21 : 0);
                    int y = p.rowY + (p.rowH - total) / 2;
                    if (tr != null && tr.status() != Transfer.Status.UNSUPPORTED && over(mx, my, x, y, BUTTONS_W, 20)) {
                        if (!tr.lines().isEmpty()) {
                            g.renderComponentTooltip(font, tr.lines(), mx, my);
                        } else {
                            String key = tr.applicable() ? "ryscope.transfer.hint" : tr.message();
                            g.renderTooltip(font, Component.translatable(key == null ? "ryscope.transfer.missing" : key), mx, my);
                        }
                    }
                }
            }
        }
        g.pose().popPose();
    }

    @Nullable
    private Stack recipeStackAt(int mx, int my) {
        for (Placed p : placed) {
            if (!over(mx, my, p.x - 4, p.y - 4, p.recipe.width() + 8, p.recipe.height() + 8)) continue;
            try {
                Stack s = p.recipe.stackAt(p.x, p.y, mx, my);
                if (s != null) return s;
            } catch (Throwable ignored) {
                // чужой рецепт
            }
        }
        if (strip && my >= stripY && my < stripY + 20 && mx >= cardX) {
            int i = (mx - cardX) / TAB_W, index = tabStart() + i;
            if (index < TABS.size() && mx - cardX - i * TAB_W < TAB_W - 2) return TABS.get(index).stack;
        }
        for (StationSpot s : stations) if (over(mx, my, s.x, s.y, 10, 10)) return s.stack;
        return null;
    }

    /** Ресурс под курсором внутри карточки (для горячих клавиш и линзы). */
    @Nullable
    public Stack hoveredStack(int mx, int my) {
        return recipeStackAt(mx, my);
    }

    @Nullable
    public Stack hoveredStackAtMouse() {
        return recipeStackAt((int) Overlay.mouseX(), (int) Overlay.mouseY());
    }

    @Override
    public void tick() {
        for (Placed p : placed) {
            try {
                p.recipe.tick();
            } catch (Throwable ignored) {
                // чужой рецепт
            }
        }
        if (transferAge++ % 10 != 0 && !transfers.isEmpty()) return;
        for (Placed p : placed) {
            try {
                statuses.put(p.recipe, statusOf(p.recipe));
            } catch (Throwable t) {
                statuses.remove(p.recipe);
            }
            if (parent instanceof AbstractContainerScreen<?> acs) {
                Transfer tr;
                try {
                    tr = p.recipe.transfer(acs, false, true);
                } catch (Throwable t) {
                    tr = Transfer.UNSUPPORTED;
                }
                transfers.put(p.recipe, tr);
            }
        }
    }

    private void setGroup(int index) {
        int n = nav.groups.size();
        nav.group = ((index % n) + n) % n;
        nav.page = 0;
        transfers.clear();
        statuses.clear();
    }

    private void setPage(int page) {
        int n = pages();
        nav.page = ((page % n) + n) % n;
        transfers.clear();
        statuses.clear();
    }

    private boolean goBack() {
        if (history.isEmpty()) return false;
        nav = history.pop();
        transfers.clear();
        statuses.clear();
        return true;
    }

    private void switchSection(Section section) {
        if (nav.focus == null || nav.section == section || nav.sections.get(section).isEmpty()) return;
        history.push(nav);
        nav = new Nav(nav.focus, section, nav.sections);
        remember(nav.focus, section == Section.USES ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
        transfers.clear();
        statuses.clear();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (Overlay.mouseClicked(this, mx, my, button)) return true;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (over(mx, my, h.x, h.y, h.w, h.h)) {
                h.action.accept(button);
                return true;
            }
        }
        Stack stack = recipeStackAt((int) mx, (int) my);
        if (stack != null) {
            if (button == 2) {
                boolean added = Bookmarks.toggle(stack);
                Overlay.toast(tr(added ? "ryscope.bookmark.added" : "ryscope.bookmark.removed"));
            } else {
                open(stack, button == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
            }
            return true;
        }
        if (!over(mx, my, cardX, cardY, cardW, cardH) && !Overlay.isOver(mx, my) && button == 0) onClose();
        return true;
    }

    private void doTransfer(AbstractContainerScreen<?> acs, RecipeView recipe, boolean max) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu != acs.getMenu()) {
            Overlay.toast(tr("ryscope.transfer.closed"));
            return;
        }
        Transfer result;
        try {
            result = recipe.transfer(acs, max, false);
        } catch (Throwable t) {
            result = Transfer.missing("ryscope.transfer.failed");
        }
        if (result.applicable()) mc.setScreen(acs);
        else Overlay.toast(tr(result.message() == null ? "ryscope.transfer.missing" : result.message()));
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return Overlay.mouseReleased(this, mx, my, button) || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return Overlay.mouseDragged(this, mx, my, button) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (Overlay.mouseScrolled(this, mx, my, scrollY)) return true;
        if (categoryStrip() && over(mx, my, cardX - 25, cardY, 24, height - cardY)) {
            setGroup(nav.group + (scrollY < 0 ? 1 : -1));
            return true;
        }
        if (over(mx, my, cardX, cardY, cardW, cardH)) {
            setPage(nav.page + (scrollY < 0 ? 1 : -1));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (Overlay.keyPressed(this, key, scan, mods)) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (Keys.matches(Keys.BACK, key, scan)) {
            if (!goBack()) onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_C && hasControlDown()) {
            double cx = Overlay.mouseX(), cy = Overlay.mouseY();
            for (Placed p : placed) {
                if (over(cx, cy, p.rowX, p.rowY, p.rowW, p.rowH) && p.recipe.id() != null) {
                    if (hasShiftDown() && p.recipe instanceof works.repka.ryscope.content.NativeRecipe nr && nr.holder != null
                        && Minecraft.getInstance().level != null) {
                        var ops = Minecraft.getInstance().level.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                        String json = net.minecraft.world.item.crafting.Recipe.CODEC.encodeStart(ops, nr.holder.value()).result()
                            .map(j -> new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(j)).orElse(null);
                        if (json != null) {
                            Minecraft.getInstance().keyboardHandler.setClipboard(json);
                            Overlay.toast(tr("ryscope.copied.json", p.recipe.id().toString()));
                            return true;
                        }
                    }
                    Overlay.copy(p.recipe.id().toString());
                    return true;
                }
            }
        }
        if (key == GLFW.GLFW_KEY_TAB && hasControlDown()) {
            cycleTab(hasShiftDown() ? -1 : 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_W && hasControlDown()) {
            int cur = currentTab();
            if (cur >= 0) closeTab(cur);
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> setPage(nav.page - 1);
            case GLFW.GLFW_KEY_RIGHT -> setPage(nav.page + 1);
            case GLFW.GLFW_KEY_UP -> setGroup(nav.group - 1);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_TAB -> setGroup(nav.group + (hasShiftDown() ? -1 : 1));
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        return Overlay.charTyped(this, c, mods);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        Overlay.onScreenClosed();
        if (parent instanceof AbstractContainerScreen<?> acs && (mc.player == null || mc.player.containerMenu != acs.getMenu())) {
            mc.setScreen(null);
            return;
        }
        mc.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
