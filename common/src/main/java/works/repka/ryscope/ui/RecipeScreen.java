package works.repka.ryscope.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Scroller;
import works.repka.ryscore.ui.Type.Weight;

/**
 * Карточка предмета, размеры из {@code design/CardV2.dc.html}. Числа в единицах интерфейса: половина пикселя макета.
 * История над карточкой, рейка категорий, шапка, вкладки, строка категории, рецепты со своей строкой наличия.
 */
public final class RecipeScreen extends ScaledScreen {
    private static final int CARD_W = 260;
    private static final int RAIL_W = 24, RAIL_SLOT = 17, RAIL_GAP = 2, RAIL_PAD = 5;
    private static final int PAD_X = 7;
    private static final int HEADER_H = 32;
    private static final int TABS_H = 16;
    private static final int ROW_H = 14;
    private static final int BLOCK_MARGIN = 3, BLOCK_PAD = 5, STATUS_GAP = 4, STATUS_H = 8;
    private static final int BOTTOM = 4;
    private static final int HISTORY_SLOT = 13, HISTORY_PAD = 2, HISTORY_GAP = 2, HISTORY_OFFSET = 23;
    private static final int BUTTON = 14, BUTTON_GAP = 3;
    private static final int MAX_PER_PAGE = 3;
    /** Карточка всегда одной высоты, как окно рецептов JEI: вся доступная высота, но не больше этого. */
    private static final int MAX_CARD_H = 360;
    private static final int SCROLL_STEP = 24;
    private static final int MAX_TABS = 11;

    /** Разделы карточки. Пятый, «Граф», открывает дерево крафта. */
    private enum Section { RECIPES, USES, SOURCES, HELP }

    private record Tab(Stack stack, Lookup.Mode mode) {}

    private record Status(String head, String tail, Kit.State state) {}

    public record Placed(RecipeView recipe, int x, int y, int rowX, int rowY, int rowW, int rowH) {}

    private record StationSpot(Stack stack, int x, int y, int size) {}

    private record Page(int start, int end) {}

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

    /** Для автотеста: дополнительная ширина карточки, чтобы проверить нехватку места. */
    public static int testExtraWidth;

    @Nullable
    private final Screen parent;
    private final Deque<Nav> history = new ArrayDeque<>();
    private Nav nav;
    private int cardX, cardY, cardW, cardH;
    private int widestCard;
    private final Kit.Hits hits = new Kit.Hits();
    private final List<StationSpot> stations = new ArrayList<>();
    private final List<StationSpot> historySpots = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();
    private final Map<RecipeView, Transfer> transfers = new IdentityHashMap<>();
    private final Map<RecipeView, Status> statuses = new IdentityHashMap<>();
    private int transferAge;
    private final Scroller edge = new Scroller();
    private final Scroller rail = new Scroller();
    @Nullable
    private RecipeView dragging;
    private List<Page> pages = List.of();
    private Object pagesFor;
    private int pagesAvail;
    private int tallestPage;
    /** Прокрутка страницы, которая не помещается в карточку целиком, и границы области рецептов. */
    private int pageScroll, recipeTop, recipeBottom = Integer.MAX_VALUE, pageContent;
    @Nullable
    private String pendingTip;

    private RecipeScreen(@Nullable Screen parent, Nav nav) {
        super(Component.translatable("ryscope.recipes.title"));
        this.parent = parent;
        this.nav = nav;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
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
        Overlay.showCard();
        show(Minecraft.getInstance(), new Nav(focus, section, sections));
        return true;
    }

    public static boolean openCategory(Category category) {
        List<Group> groups = Lookup.category(category);
        if (groups.isEmpty()) return false;
        Map<Section, List<Group>> sections = new EnumMap<>(Section.class);
        for (Section s : Section.values()) sections.put(s, s == Section.RECIPES ? groups : List.of());
        Overlay.showCard();
        show(Minecraft.getInstance(), new Nav(null, Section.RECIPES, sections));
        return true;
    }

    private static void show(Minecraft mc, Nav nav) {
        if (mc.screen instanceof RecipeScreen rs) {
            rs.history.push(rs.nav);
            rs.nav = nav;
            rs.rail.reset();
            rs.clearCaches();
        } else {
            mc.setScreen(new RecipeScreen(mc.screen, nav));
        }
    }

    private void clearCaches() {
        pageScroll = 0;
        transfers.clear();
        statuses.clear();
        pagesFor = null;
    }

    /** Ресурс, рецепты которого сейчас показаны, или null для просмотра категории. */
    @Nullable
    public Stack focus() {
        return nav.focus;
    }

    public int[] cardBounds() {
        return new int[] {cardX, cardY, cardW, cardH};
    }

    /** Показанные рецепты с их положением на экране. */
    public List<Placed> placed() {
        return placed;
    }

    private Group group() {
        return nav.groups.get(Math.min(nav.group, nav.groups.size() - 1));
    }

    private int pageCount() {
        return Math.max(1, pages.size());
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

    private boolean hasRail() {
        return nav.groups.size() > 1;
    }

    private int railWidth() {
        return hasRail() ? RAIL_W : 0;
    }

    private int chromeHeight() {
        return HEADER_H + (nav.focus != null ? TABS_H : 0) + ROW_H + BOTTOM;
    }

    private static int recipeRow(RecipeView r) {
        int plate = r.needsPlate() ? 3 : 0;
        return Math.max(r.height() + plate * 2, BUTTON * 2 + BUTTON_GAP);
    }

    private static int blockHeight(RecipeView r) {
        return BLOCK_PAD * 2 + recipeRow(r) + (r.isCrafting() ? STATUS_GAP + STATUS_H : 0);
    }

    /** Ширина карточки, которая нужна этому разделу. */
    public int preferredWidth() {
        int need = 0;
        for (RecipeView r : group().recipes()) {
            int plate = r.needsPlate() ? 3 : 0;
            need = Math.max(need, r.width() + plate * 2);
        }
        widestCard = Math.max(widestCard, Math.max(CARD_W, need + BUTTON + 6 + BLOCK_PAD * 2 + PAD_X * 2 + railWidth()));
        return widestCard + testExtraWidth;
    }

    private void buildPages(List<RecipeView> recipes, int avail) {
        if (pagesFor == group() && pagesAvail == avail) return;
        pagesFor = group();
        pagesAvail = avail;
        List<Page> out = new ArrayList<>();
        tallestPage = paginate(recipes, avail, out);
        pages = out;
    }

    /** Страницы рецептов под доступную высоту. @return высота самой высокой страницы */
    private static int paginate(List<RecipeView> recipes, int avail, @Nullable List<Page> out) {
        int tallest = 0;
        int start = 0;
        while (start < recipes.size()) {
            int used = 0, end = start;
            // рецепт модов берёт свою настоящую высоту: если второй не помещается, на странице остаётся один
            while (end < recipes.size() && end - start < MAX_PER_PAGE) {
                int h = blockHeight(recipes.get(end)) + BLOCK_MARGIN * 2;
                if (end > start && used + h > avail) break;
                used += h;
                end++;
            }
            if (out != null) out.add(new Page(start, end));
            tallest = Math.max(tallest, used);
            start = end;
        }
        return tallest;
    }


    private void relayout() {
        cardW = Math.min(width - 8, preferredWidth());
        int sidebar = Overlay.sidebarLeft(this);
        // по центру экрана; если там задевает правую панель, левее, но не за край
        cardX = Math.max(4, Math.min(sidebar - cardW - 4, (width - cardW) / 2));
        boolean strip = TABS.size() > 1;
        int top = strip ? HISTORY_OFFSET + 4 : 4;
        int chrome = chromeHeight();
        // одна высота для любого предмета, как в JEI; что не влезает, прокручивается
        cardH = Math.min(height - top - 4, MAX_CARD_H);
        cardY = Math.max(top, (height - cardH) / 2);
        buildPages(group().recipes(), Math.max(1, cardH - chrome));
        nav.page = Math.max(0, Math.min(nav.page, pageCount() - 1));
    }

    @Override
    public void removed() {
        super.removed();
        works.repka.ryscope.jei.StructureRecipeView.releaseAll();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, ThemeManager.get().c("overlay"));
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float partialTick) {
        Theme t = ThemeManager.get();
        hits.clear();
        pendingTip = null;
        placed.clear();
        stations.clear();
        historySpots.clear();
        relayout();
        renderBackground(g, mx, my, partialTick);
        Overlay.renderBackground(this, g, mx, my, partialTick);
        if (Overlay.cardCollapsed()) {
            renderCardStrip(g, t, mx, my);
            Overlay.renderTop(this, g, mx, my, partialTick);
            return;
        }
        renderHistory(g, t, mx, my);
        t.panel(g, cardX, cardY, cardW, cardH);
        Category hoveredCategory = hasRail() ? renderRail(g, t, mx, my) : null;
        renderHeader(g, t, mx, my);
        int y = cardY + HEADER_H;
        if (nav.focus != null) {
            renderSections(g, t, mx, my, y);
            y += TABS_H;
        }
        renderCategoryRow(g, t, mx, my, y);
        y += ROW_H;
        renderRecipes(g, t, mx, my, partialTick, y);
        Overlay.renderTop(this, g, mx, my, partialTick);
        renderTooltips(g, t, mx, my, hoveredCategory);
    }

    /** Карточка уступила место панели: остаётся полоска с кнопкой вернуть её. */
    private void renderCardStrip(GuiGraphics g, Theme t, int mx, int my) {
        int x = 4, y = 4, w = Overlay.STRIP_W, h = height - 8;
        t.panel(g, x, y, w, h);
        boolean hot = over(mx, my, x, y, w, h);
        Icons.draw(g, Icons.Shape.CHEVRON_RIGHT, x + (w - 8) / 2f, y + (h - 8) / 2f, 8f, hot ? t.c("accent") : t.c("button.fg"));
        hits.add(x, y, w, h, b -> Overlay.showCard());
    }

    // --- история ---

    private void renderHistory(GuiGraphics g, Theme t, int mx, int my) {
        if (TABS.size() <= 1) return;
        String label = tr("ryscope.history");
        float labelW = Draw.labelWidth(label, 10.5f, Weight.REGULAR);
        int leading = HISTORY_PAD + 2 + Math.round(labelW) + 3;
        int fit = Math.max(1, (cardW - leading - HISTORY_PAD + HISTORY_GAP) / (HISTORY_SLOT + HISTORY_GAP));
        int start = Math.max(0, TABS.size() - fit), count = TABS.size() - start;
        int w = leading + count * HISTORY_SLOT + (count - 1) * HISTORY_GAP + HISTORY_PAD;
        int h = HISTORY_SLOT + HISTORY_PAD * 2;
        int y = cardY - HISTORY_OFFSET;
        Draw.roundBox(g, cardX, y, w, h, 5f, t.c("panel.bg"), t.c("panel.border"));
        Draw.label(g, label, cardX + HISTORY_PAD + 2, y + h / 2f, 10.5f, Weight.REGULAR, t.c("text.dim"));
        int sx = cardX + leading;
        int sy = y + HISTORY_PAD;
        for (int i = start; i < TABS.size(); i++) {
            int index = i;
            Tab tab = TABS.get(i);
            boolean current = nav.focus != null && tab.stack.same(nav.focus);
            boolean hot = over(mx, my, sx, sy, HISTORY_SLOT, HISTORY_SLOT);
            Draw.roundBox(g, sx, sy, HISTORY_SLOT, HISTORY_SLOT, 3f, hot ? t.c("slot.hover") : t.c("slot.bg"), current ? 0 : t.c("card.border"));
            if (current) Draw.roundBorder(g, sx, sy, HISTORY_SLOT, HISTORY_SLOT, 3f, t.c("accent"), 1f);
            renderScaled(g, tab.stack, sx + 2.5f, sy + 2.5f, 0.5f);
            historySpots.add(new StationSpot(tab.stack, sx, sy, HISTORY_SLOT));
            hits.add(sx, sy, HISTORY_SLOT, HISTORY_SLOT, button -> {
                if (button != 0) closeTab(index);
                else if (nav.focus == null || !tab.stack.same(nav.focus)) open(tab.stack, tab.mode);
            });
            sx += HISTORY_SLOT + HISTORY_GAP;
        }
    }

    private static void renderScaled(GuiGraphics g, Stack stack, float x, float y, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        try {
            stack.render(g, 0, 0);
        } catch (Throwable ignored) {
            // чужой рендер
        }
        g.pose().popPose();
    }

    // --- рейка категорий ---

    private int railContent() {
        return nav.groups.size() * (RAIL_SLOT + RAIL_GAP) - RAIL_GAP;
    }

    @Nullable
    private Category renderRail(GuiGraphics g, Theme t, int mx, int my) {
        int top = cardY + RAIL_PAD, bottom = cardY + cardH - RAIL_PAD;
        if (!t.beveled()) {
            float radius = t.r("panel.radius");
            Draw.scissor(g, cardX, cardY, cardX + RAIL_W, cardY + cardH);
            Draw.round(g, cardX + 0.5f, cardY + 0.5f, cardW - 1, cardH - 1, radius, t.c("sidebar.bg"));
            g.disableScissor();
        }
        Draw.round(g, cardX + RAIL_W - 0.5f, cardY + 0.5f, 0.5f, cardH - 1, 0.02f, t.c("panel.border"));
        float position = rail.update(Math.max(0, railContent() - (bottom - top)));
        int x = cardX + (RAIL_W - RAIL_SLOT) / 2;
        Category hovered = null;
        hits.clip(top, bottom);
        Draw.scissor(g, cardX, top, cardX + RAIL_W, bottom);
        int size = nav.groups.size();
        for (int i = 0; i < size; i++) {
            int y = top + i * (RAIL_SLOT + RAIL_GAP) - Math.round(position);
            if (y + RAIL_SLOT < top || y > bottom) continue;
            int index = i;
            boolean active = i == nav.group, hot = over(mx, my, x, y, RAIL_SLOT, RAIL_SLOT) && my >= top && my < bottom;
            if (t.beveled()) {
                t.tab(g, x, y, RAIL_SLOT, RAIL_SLOT, active, hot);
            } else if (active) {
                Draw.roundBox(g, x, y, RAIL_SLOT, RAIL_SLOT, 4f, t.c("button.bg"), t.c("button.border"));
                Draw.round(g, cardX + 0.5f, y + 4, 1.5f, RAIL_SLOT - 8, 0.75f, t.c("accent"));
            } else if (hot) {
                Draw.round(g, x, y, RAIL_SLOT, RAIL_SLOT, 4f, t.c("slot.hover"));
            }
            g.pose().pushPose();
            g.pose().translate(x + 3.5f, y + 3.5f, 0);
            g.pose().scale(0.625f, 0.625f, 1f);
            try {
                nav.groups.get(i).category().renderIcon(g, 0, 0);
            } catch (Throwable ignored) {
                // значок чужой категории
            }
            g.pose().popPose();
            if (hot) hovered = nav.groups.get(i).category();
            hits.add(x, y, RAIL_SLOT, RAIL_SLOT, b -> setGroup(index));
        }
        g.disableScissor();
        hits.unclip();
        return hovered;
    }

    private void revealRail() {
        int visible = cardH - RAIL_PAD * 2;
        int y = nav.group * (RAIL_SLOT + RAIL_GAP);
        float position = rail.position();
        if (y < position) rail.jump(y);
        else if (y + RAIL_SLOT > position + visible) rail.jump(y + RAIL_SLOT - visible);
    }

    // --- шапка ---

    private void renderHeader(GuiGraphics g, Theme t, int mx, int my) {
        int left = cardX + railWidth() + PAD_X;
        int iconY = cardY + 7;
        Draw.roundBox(g, left, iconY, 20, 20, 4.5f, t.c("slot.bg"), t.c("button.border"));
        Stack shown = nav.focus;
        if (shown != null) shown.render(g, left + 2, iconY + 2);
        else group().category().renderIcon(g, left + 2, iconY + 2);
        int buttonY = cardY + 9;
        int closeX = cardX + cardW - PAD_X - 15, starX = closeX - 15 - 3;
        boolean closeHot = over(mx, my, closeX, buttonY, 15, 15);
        iconButton(g, t, closeX, buttonY, closeHot);
        Icons.draw(g, Icons.Shape.CROSS, closeX + 3.5f, buttonY + 3.5f, 8f, closeHot ? t.c("accent") : t.c("button.fg"));
        hits.add(closeX, buttonY, 15, 15, b -> onClose());
        if (shown != null) {
            boolean starHot = over(mx, my, starX, buttonY, 15, 15), marked = Bookmarks.contains(shown);
            iconButton(g, t, starX, buttonY, starHot);
            Icons.draw(g, Icons.Shape.STAR, starX + 3.5f, buttonY + 3.5f, 8f, marked || starHot ? t.c("accent") : t.c("button.fg"));
            hits.add(starX, buttonY, 15, 15, b -> Overlay.toast(tr(Bookmarks.toggle(nav.focus) ? "ryscope.bookmark.added" : "ryscope.bookmark.removed")));
        }
        float textX = left + 20 + 6;
        float room = starX - 6 - textX;
        String title = shown != null ? shown.name().getString() : group().category().title.getString();
        Draw.label(g, Draw.ellipsize(title, room, 17, Weight.SEMIBOLD), textX, iconY + 5.5f, 17, Weight.SEMIBOLD, t.c("text.primary"));
        String full = subtitle();
        String sub = Draw.ellipsize(full, room, 11.5f, Weight.REGULAR);
        Draw.label(g, sub, textX, iconY + 15f, 11.5f, Weight.REGULAR, t.c("text.muted"));
        if (!sub.equals(full) && mx >= textX && mx < textX + room && my >= iconY + 11 && my < iconY + 19) pendingTip = full;
    }

    private void iconButton(GuiGraphics g, Theme t, int x, int y, boolean hot) {
        Draw.roundBox(g, x, y, 15, 15, 4f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), t.c("button.border"));
    }

    /** Только id и тег: счётчики есть во вкладках. */
    private String subtitle() {
        if (nav.focus == null) return tr("ryscope.recipes.count", total(nav.groups));
        StringBuilder out = new StringBuilder(nav.focus.id().toString());
        List<String> tags = nav.focus.tags();
        if (!tags.isEmpty()) out.append(" · #").append(tags.get(0));
        return out.toString();
    }

    private void renderSections(GuiGraphics g, Theme t, int mx, int my, int y) {
        Draw.round(g, cardX + railWidth(), y + TABS_H - 0.5f, cardW - railWidth() - 0.5f, 0.5f, 0.02f, t.c("panel.border"));
        List<Kit.Tab> tabs = new ArrayList<>();
        for (Section s : Section.values()) {
            int count = total(nav.sections.get(s));
            String label = tr("ryscope.section." + s.name().toLowerCase(java.util.Locale.ROOT));
            tabs.add(new Kit.Tab(label, s != Section.HELP ? count : -1, nav.section == s, count > 0 ? () -> switchSection(s) : null));
        }
        tabs.add(new Kit.Tab(tr("ryscope.section.graph"), -1, false, () -> TreeScreen.open(nav.focus)));
        Kit.tabs(g, t, hits, cardX + railWidth() + 4, y, TABS_H, cardW - railWidth() - 8, tabs, mx, my);
    }

    private void renderCategoryRow(GuiGraphics g, Theme t, int mx, int my, int rowTop) {
        Group group = group();
        int y = rowTop + 5;
        float cy = y + 3.5f;
        int x = cardX + railWidth() + PAD_X;
        Stack mainStation = group.category().stations.isEmpty() ? null : group.category().stations.get(0);
        if (mainStation != null) {
            renderScaled(g, mainStation, x, y, 0.4375f);
            stations.add(new StationSpot(mainStation, x, y, 8));
        }
        float textX = x + (mainStation != null ? 11 : 0);
        int right = cardX + cardW - PAD_X;
        List<RecipeView> recipes = group.recipes();
        Page page = pages.isEmpty() ? new Page(0, 0) : pages.get(nav.page);
        String range = page.end - page.start > 1 ? (page.start + 1) + "–" + page.end : Integer.toString(page.start + 1);
        String pageText = tr("ryscope.recipes.range", range, recipes.size());
        float pageW = Draw.labelWidth(pageText, 11.5f, Weight.REGULAR);
        int nextX = right - 6, prevX = nextX - 10;
        int prevC = over(mx, my, prevX - 2, y - 2, 10, 11) ? t.c("accent") : t.c("button.fg");
        int nextC = over(mx, my, nextX - 2, y - 2, 10, 11) ? t.c("accent") : t.c("button.fg");
        Icons.draw(g, Icons.Shape.CHEVRON_LEFT, prevX, y + 0.5f, 6f, prevC);
        Icons.draw(g, Icons.Shape.CHEVRON_RIGHT, nextX, y + 0.5f, 6f, nextC);
        Draw.label(g, pageText, prevX - 4 - pageW, cy, 11.5f, Weight.REGULAR, t.c("text.muted"));
        hits.add(prevX - 2, y - 2, 10, 11, b -> setPage(nav.page - 1));
        hits.add(nextX - 2, y - 2, 10, 11, b -> setPage(nav.page + 1));
        float titleRoom = prevX - 4 - pageW - 6 - textX;
        String title = Draw.ellipsize(group.category().title.getString(), titleRoom, 12.5f, Weight.REGULAR);
        Draw.label(g, title, textX, cy, 12.5f, Weight.REGULAR, t.c("text.primary"));
        // остальные станции, на которых делается то же самое
        float stx = textX + Draw.labelWidth(title, 12.5f, Weight.REGULAR) + 5;
        for (int i = 1; i < group.category().stations.size() && stations.size() < 6; i++) {
            if (stx + 8 > prevX - 4 - pageW - 5) break;
            Stack station = group.category().stations.get(i);
            renderScaled(g, station, stx, y, 0.4375f);
            stations.add(new StationSpot(station, (int) stx, y, 8));
            stx += 10;
        }
    }

    // --- рецепты ---

    private void renderRecipes(GuiGraphics g, Theme t, int mx, int my, float partialTick, int top) {
        List<RecipeView> recipes = group().recipes();
        if (pages.isEmpty()) return;
        Page page = pages.get(nav.page);
        int bottom = cardY + cardH - BOTTOM;
        recipeTop = top;
        recipeBottom = bottom;
        pageContent = 0;
        for (int i = page.start; i < page.end; i++) pageContent += blockHeight(recipes.get(i)) + BLOCK_MARGIN * 2;
        pageScroll = Math.max(0, Math.min(pageScroll, maxScroll()));
        works.repka.ryscope.content.NativeRecipe.missingBadges = true;
        works.repka.ryscope.runtime.Ae2Terminal.setActive(parent instanceof AbstractContainerScreen<?> host ? host.getMenu() : null);
        // блок рецепта не шире, чем нужно самому широкому рецепту раздела; на широкой карточке стоит по центру
        int areaW = cardW - railWidth() - PAD_X * 2;
        int blockW = Math.min(areaW, widestCard - railWidth() - PAD_X * 2);
        int blockX = cardX + railWidth() + PAD_X + (areaW - blockW) / 2;
        // упор у края постраничного списка: рецепты чуть сдвигаются и возвращаются
        double scale = Draw.scale();
        float push = (float) (Math.round(edge.update(0) * scale) / scale);
        Draw.scissor(g, cardX, top, cardX + cardW, bottom);
        g.pose().pushPose();
        g.pose().translate(0, -push, 0);
        int y = top + BLOCK_MARGIN - pageScroll;
        for (int i = page.start; i < page.end; i++) {
            RecipeView r = recipes.get(i);
            int h = blockHeight(r);
            if (y + h < top || y > bottom) {
                // прокручен за край: не рисуется и не ловит мышь
                y += h + BLOCK_MARGIN * 2;
                continue;
            }
            Draw.roundBox(g, blockX, y, blockW, h, 5f, t.c("card.bg"), t.c("panel.border"));
            int plate = r.needsPlate() ? 3 : 0;
            int rowH = recipeRow(r);
            int innerX = blockX + BLOCK_PAD, innerW = blockW - BLOCK_PAD * 2, rowY = y + BLOCK_PAD;
            int free = innerW - BUTTON - 6 - r.width() - plate * 2;
            // по центру блока, если хватает места слева от кнопок
            int centered = (innerW - r.width() - plate * 2) / 2;
            int rx = innerX + plate + Math.max(0, Math.min(centered, free)), ry = rowY + (rowH - r.height()) / 2;
            if (plate > 0) t.plate(g, rx - plate, ry - plate, r.width() + plate * 2, r.height() + plate * 2);
            try {
                r.render(g, t, rx, ry, mx, my, partialTick);
            } catch (Throwable th) {
                Draw.text(g, "?", rx, ry, t.c("state.bad"));
            }
            Placed row = new Placed(r, rx, ry, innerX, rowY, innerW, rowH);
            placed.add(row);
            renderLock(g, t, r, blockX, y, blockW, h);
            // кнопки только целиком видимые: срезанные краем не должны нажиматься сквозь шапку
            if (rowY >= top && rowY + BUTTON * 2 + BUTTON_GAP <= bottom) renderButtons(g, t, row, mx, my);
            if (r.isCrafting()) renderStatus(g, t, r, innerX, innerW, rowY + rowH + STATUS_GAP, mx, my);
            y += h + BLOCK_MARGIN * 2;
        }
        g.pose().popPose();
        g.disableScissor();
        int max = maxScroll();
        if (max > 0) {
            int areaH = bottom - top, thumb = Math.max(12, areaH * areaH / pageContent);
            int thumbY = top + (areaH - thumb) * pageScroll / max;
            Draw.round(g, cardX + cardW - 5, thumbY, 2, thumb, 1f, Draw.alpha(t.c("text.dim"), 0.7f));
        }
    }

    private int maxScroll() {
        return Math.max(0, pageContent - (recipeBottom - recipeTop));
    }

    /** Курсор над видимой частью области рецептов. */
    private boolean inRecipes(double my) {
        return my >= recipeTop && my < recipeBottom;
    }

    private void renderLock(GuiGraphics g, Theme t, RecipeView r, int x, int y, int w, int h) {
        works.repka.ryscope.runtime.Stages.Stage lock = works.repka.ryscope.runtime.Stages.lockOf(r, group().category());
        if (lock == null) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 260);
        Draw.round(g, x, y, w, h, 5f, Draw.alpha(t.c("card.bg"), 0.78f));
        String text = tr("ryscope.stage.locked", lock.title());
        float textW = Draw.labelWidth(text, 13, Weight.MEDIUM);
        float cx = x + (w - textW) / 2f;
        Draw.lock(g, (int) cx - 11, y + h / 2 - 8, t.c("state.warn"));
        Draw.label(g, text, cx, y + h / 2f - 5, 13, Weight.MEDIUM, t.c("text.primary"));
        String need = works.repka.ryscope.runtime.Stages.requirement(lock);
        if (!need.isEmpty()) {
            String shown = Draw.ellipsize(need, w - 12, 11, Weight.REGULAR);
            Draw.label(g, shown, x + (w - Draw.labelWidth(shown, 11, Weight.REGULAR)) / 2f, y + h / 2f + 6, 11, Weight.REGULAR, t.c("text.muted"));
        }
        g.pose().popPose();
    }

    private boolean canGoal(RecipeView r) {
        return nav.focus != null && r.isCrafting() && !r.outputs().isEmpty();
    }

    /**
     * Место под «+» у рецепта крафта есть всегда: кнопки стоят на своих местах и не прыгают, пока перенос
     * проверяется или когда окно открыто не над станцией. Активна она только когда перенос возможен.
     */
    private boolean showsTransfer(RecipeView r) {
        return r.isCrafting();
    }

    /** Перенос можно нажать: окно над станцией, проверка готова и рецепт туда раскладывается. */
    private boolean transferEnabled(RecipeView r) {
        if (!(parent instanceof AbstractContainerScreen<?>)) return false;
        Transfer tr = transfers.get(r);
        return tr != null && tr.status() != Transfer.Status.UNSUPPORTED;
    }

    private int buttonsTop(Placed row) {
        boolean transfer = showsTransfer(row.recipe), goal = canGoal(row.recipe);
        int total = (transfer ? BUTTON : 0) + (transfer && goal ? BUTTON_GAP : 0) + (goal ? BUTTON : 0);
        return row.rowY + (row.rowH - total) / 2;
    }

    private static Kit.State stateOf(@Nullable Transfer tr) {
        if (tr == null || !tr.applicable()) return Kit.State.NONE;
        return tr.status() == Transfer.Status.PARTIAL ? Kit.State.PARTIAL : Kit.State.FULL;
    }

    private void renderButtons(GuiGraphics g, Theme t, Placed row, int mx, int my) {
        RecipeView r = row.recipe;
        int x = row.rowX + row.rowW - BUTTON, y = buttonsTop(row);
        if (showsTransfer(r)) {
            Transfer tr = transfers.get(r);
            boolean enabled = transferEnabled(r);
            boolean hot = enabled && over(mx, my, x, y, BUTTON, BUTTON);
            smallBox(g, t, x, y, hot, 0);
            Kit.State state = stateOf(tr);
            int color = !enabled ? Draw.alpha(t.c("text.dim"), 0.6f) : state == Kit.State.FULL ? t.c("accent") : t.c("text.muted");
            Icons.draw(g, Icons.Shape.PLUS, x + 3, y + 3, 8, color);
            if (enabled && state != Kit.State.FULL) Kit.indicator(g, t, x + BUTTON - 3, y - 1.5f, 4.5f, state, -1, -1);
            if (enabled) {
                hits.add(x, y, BUTTON, BUTTON, b -> {
                    if (b == 0 && parent instanceof AbstractContainerScreen<?> acs) doTransfer(acs, r, hasShiftDown());
                });
            }
            y += BUTTON + BUTTON_GAP;
        }
        if (canGoal(r)) {
            Stack output = r.outputs().get(0);
            boolean set = works.repka.ryscope.runtime.Board.contains(output), hot = over(mx, my, x, y, BUTTON, BUTTON);
            smallBox(g, t, x, y, hot, set ? Draw.alpha(t.c("accent"), 0.18f) : 0);
            Icons.drawRotated(g, Icons.Shape.PIN, x + 3, y + 3, 8, 35, set || hot ? t.c("accent") : t.c("button.fg"));
            hits.add(x, y, BUTTON, BUTTON, b -> {
                if (b != 0) return;
                boolean added = works.repka.ryscope.runtime.Board.toggle(output, 1);
                Overlay.toast(tr(added ? "ryscope.goals.added" : "ryscope.goals.removed"));
            });
        }
    }

    private void smallBox(GuiGraphics g, Theme t, int x, int y, boolean hot, int tint) {
        if (t.beveled()) {
            t.button(g, x, y, BUTTON, BUTTON, hot, false);
            return;
        }
        int fill = hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg");
        Draw.roundBox(g, x, y, BUTTON, BUTTON, 3.5f, fill, t.c("button.border"));
        if (tint != 0) Draw.round(g, x, y, BUTTON, BUTTON, 3.5f, tint);
    }

    private void renderStatus(GuiGraphics g, Theme t, RecipeView r, int x, int width, int y, int mx, int my) {
        Status s = statuses.get(r);
        float cy = y + STATUS_H / 2f;
        int w = width;
        if (Minecraft.getInstance().options.advancedItemTooltips && r.id() != null) {
            String id = Draw.ellipsize(r.id().toString(), w * 0.4f, 9, Weight.REGULAR);
            Draw.label(g, id, x + w - Draw.labelWidth(id, 9, Weight.REGULAR), cy, 9, Weight.REGULAR, t.c("text.dim"));
            w = (int) (w * 0.58f);
        }
        if (s == null) return;
        if (Kit.indicator(g, t, x, cy - 2.25f, 4.5f, s.state, mx, my)) {
            pendingTip = tr(switch (s.state) {
                case FULL -> "ryscope.status.state.full";
                case PARTIAL -> "ryscope.status.state.partial";
                case NONE -> "ryscope.status.state.none";
            });
        }
        float tx = x + 8.5f;
        float headW = Draw.labelWidth(s.head, 11.5f, Weight.SEMIBOLD);
        Draw.label(g, s.head, tx, cy, 11.5f, Weight.SEMIBOLD, t.c("text.primary"));
        if (s.tail.isEmpty()) return;
        float room = x + w - tx - headW - 3;
        String tail = Draw.ellipsize(s.tail, room, 11.5f, Weight.REGULAR);
        Draw.label(g, tail, tx + headW + 3, cy, 11.5f, Weight.REGULAR, t.c("text.secondary"));
        if (!tail.equals(s.tail) && my >= y - 1 && my < y + STATUS_H + 1 && mx >= tx && mx < x + w) pendingTip = s.head + " " + s.tail;
    }

    @Nullable
    private static Status statusOf(RecipeView recipe) {
        if (!recipe.isCrafting()) return null;
        boolean network = works.repka.ryscope.runtime.Ae2Terminal.active();
        if (!network && !works.repka.ryscope.runtime.InventoryView.tracking()) return null;
        int total = 0, have = 0;
        boolean stored = false;
        Map<String, Integer> missing = new LinkedHashMap<>();
        List<Ing> inputs = recipe.inputs();
        works.repka.ryscope.runtime.Ae2Terminal.State[] states = works.repka.ryscope.runtime.Supply.states(inputs);
        for (int i = 0; i < inputs.size(); i++) {
            Ing in = inputs.get(i);
            if (in.isEmpty()) continue;
            boolean items = false;
            for (Stack option : in.options) items |= option.key() instanceof net.minecraft.world.item.Item;
            if (!items) continue;
            total++;
            if (states[i] != works.repka.ryscope.runtime.Ae2Terminal.State.MISSING) {
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
        if (missing.isEmpty()) return new Status(tr("ryscope.status.all." + place), "", Kit.State.FULL);
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : missing.entrySet()) {
            if (shown == 2) break;
            if (shown++ > 0) names.append(", ");
            names.append(e.getKey()).append(" ×").append(e.getValue());
        }
        if (missing.size() > 2) names.append(' ').append(tr("ryscope.status.more", missing.size() - 2));
        String tail = tr("ryscope.status.have." + place) + " · " + tr("ryscope.status.missing", names.toString());
        if (stored) tail += " · " + tr("ryscope.status.stored");
        return new Status(tr("ryscope.status.head", have, total), tail, have == 0 ? Kit.State.NONE : Kit.State.PARTIAL);
    }

    private void renderTooltips(GuiGraphics g, Theme t, int mx, int my, @Nullable Category hoveredCategory) {
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
        } else if (hoveredCategory != null) {
            Kit.tooltip(g, t, List.of(hoveredCategory.title.getString()), mx, my);
        } else if (pendingTip != null) {
            Kit.tooltip(g, t, List.of(pendingTip), mx, my);
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
            if (!drawn) {
                for (Placed p : placed) buttonTooltip(g, t, font, p, mx, my);
            }
        }
        g.pose().popPose();
    }

    private void buttonTooltip(GuiGraphics g, Theme t, Font font, Placed p, int mx, int my) {
        int x = p.rowX + p.rowW - BUTTON, y = buttonsTop(p);
        if (showsTransfer(p.recipe)) {
            Transfer tr = transfers.get(p.recipe);
            boolean overPlus = over(mx, my, x - 2, y - 2, BUTTON + 4, BUTTON + 4);
            if (overPlus && !(parent instanceof AbstractContainerScreen<?>)) {
                Kit.tooltip(g, t, List.of(tr("ryscope.transfer.no_host")), mx, my);
            } else if (overPlus && tr == null) {
                Kit.tooltip(g, t, List.of(tr("ryscope.transfer.checking")), mx, my);
            } else if (overPlus && tr.status() == Transfer.Status.UNSUPPORTED) {
                Kit.tooltip(g, t, List.of(tr("ryscope.transfer.unsupported")), mx, my);
            } else if (tr != null && overPlus) {
                if (!tr.lines().isEmpty()) {
                    g.renderComponentTooltip(font, tr.lines(), mx, my);
                } else {
                    String key = tr.applicable() ? "ryscope.transfer.hint" : tr.message();
                    Kit.tooltip(g, t, List.of(tr(key == null ? "ryscope.transfer.missing" : key)), mx, my);
                }
            }
            y += BUTTON + BUTTON_GAP;
        }
        if (canGoal(p.recipe) && over(mx, my, x, y, BUTTON, BUTTON)) {
            boolean set = works.repka.ryscope.runtime.Board.contains(p.recipe.outputs().get(0));
            Kit.tooltip(g, t, List.of(tr(set ? "ryscope.goals.in" : "ryscope.goals.add")), mx, my);
        }
    }

    @Nullable
    private Stack recipeStackAt(int mx, int my) {
        for (Placed p : placed) {
            if (!inRecipes(my)) break;
            if (!over(mx, my, p.x - 4, p.y - 4, p.recipe.width() + 8, p.recipe.height() + 8)) continue;
            try {
                Stack s = p.recipe.stackAt(p.x, p.y, mx, my);
                if (s != null) return s;
            } catch (Throwable ignored) {
                // чужой рецепт
            }
        }
        for (StationSpot s : historySpots) if (over(mx, my, s.x, s.y, s.size, s.size)) return s.stack;
        for (StationSpot s : stations) if (over(mx, my, s.x, s.y, s.size, s.size)) return s.stack;
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
        clearCaches();
        revealRail();
    }

    private void setPage(int page) {
        int n = pageCount();
        nav.page = ((page % n) + n) % n;
        pageScroll = 0;
        transfers.clear();
        statuses.clear();
    }

    private boolean goBack() {
        if (history.isEmpty()) return false;
        nav = history.pop();
        clearCaches();
        return true;
    }

    private void switchSection(Section section) {
        if (nav.focus == null || nav.section == section || nav.sections.get(section).isEmpty()) return;
        history.push(nav);
        nav = new Nav(nav.focus, section, nav.sections);
        remember(nav.focus, section == Section.USES ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
        rail.reset();
        clearCaches();
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (Overlay.mouseClicked(this, mx, my, button)) return true;
        if (hits.click(mx, my, button)) return true;
        if (Overlay.cardCollapsed()) return true;
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
        for (Placed p : placed) {
            if (inRecipes(my) && over(mx, my, p.x, p.y, p.recipe.width(), p.recipe.height()) && p.recipe.mouseClicked(mx, my, button)) {
                dragging = p.recipe;
                return true;
            }
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
    protected boolean onRelease(double mx, double my, int button) {
        if (dragging != null) {
            dragging.mouseReleased(mx, my, button);
            dragging = null;
            return true;
        }
        return Overlay.mouseReleased(this, mx, my, button) || super.onRelease(mx, my, button);
    }

    @Override
    protected boolean onDrag(double mx, double my, int button, double dx, double dy) {
        if (dragging != null && dragging.mouseDragged(mx, my, button, dx, dy)) return true;
        return Overlay.mouseDragged(this, mx, my, button) || super.onDrag(mx, my, button, dx, dy);
    }

    @Override
    protected boolean onScroll(double mx, double my, double scrollX, double scrollY) {
        if (Overlay.mouseScrolled(this, mx, my, scrollY)) return true;
        if (hasRail() && over(mx, my, cardX, cardY, RAIL_W, cardH)) {
            rail.by(-(float) scrollY * (RAIL_SLOT + RAIL_GAP));
            return true;
        }
        for (Placed p : placed) {
            if (inRecipes(my) && over(mx, my, p.x, p.y, p.recipe.width(), p.recipe.height()) && p.recipe.mouseScrolled(mx, my, scrollX, scrollY)) return true;
        }
        if (over(mx, my, cardX, cardY, cardW, cardH)) {
            // страница выше карточки: сначала прокрутка, у края листание
            int max = maxScroll();
            if (scrollY < 0 && pageScroll < max) {
                pageScroll = Math.min(max, pageScroll + SCROLL_STEP);
                return true;
            }
            if (scrollY > 0 && pageScroll > 0) {
                pageScroll = Math.max(0, pageScroll - SCROLL_STEP);
                return true;
            }
            // по кругу, как стрелки: с последней страницы на первую; одна страница отвечает упругим отскоком
            if (pageCount() > 1) setPage(nav.page + (scrollY < 0 ? 1 : -1));
            else edge.by(scrollY < 0 ? 1 : -1);
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
