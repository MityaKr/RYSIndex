package works.repka.ryscope.ui;

import works.repka.ryscore.ui.Draw;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
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
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.RecipeView.Transfer;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.platform.Platform;
import works.repka.ryscope.runtime.Bookmarks;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.Lookup.Group;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;

/** Карточка предмета: рецепты и использования по категориям, с историей переходов. */
public final class RecipeScreen extends Screen {
    // отступы и высоты частей карточки; на низких экранах сжимаются, чтобы помещалось больше рецептов
    private int PAD = 10;
    private int HEADER_H = 24;
    private int TABS_H = 24;
    private int TITLE_H = 14;
    private int FOOTER_H = 12;
    private static final int INNER = 6;
    private static final int GAP = 4;
    private static final int BUTTON_ICON = 20;
    private static final int STATUS_H = 16;
    private static final int STATUS_BOX = 12;
    /** Ширина колонки кнопок справа от рецепта: подписанные кнопки, если хватает места, иначе значок. */
    private int buttonCol = BUTTON_ICON;
    private boolean labelled;
    /** Хватает ли шапке места на числа рядом с названиями вкладок. */
    private boolean modeCounts = true;
    private int statusH;
    private static final int TAB_W = 22;
    private static final int MIN_CARD_W = 290;

    // --- вкладки открытых предметов: живут, пока игрок в мире ---

    private record Tab(Stack stack, Lookup.Mode mode) {}

    private static final List<Tab> TABS = new ArrayList<>();
    private static final int MAX_TABS = 11;
    private static final int STRIP_H = 22;

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

    /** Ресурс, рецепты которого сейчас показаны, или null для просмотра категории. */
    @Nullable
    public Stack focus() {
        return nav.focus;
    }

    /** Сколько первых вкладок не помещается по ширине карточки: показываем самые свежие. */
    private int tabStart() {
        return Math.max(0, TABS.size() - Math.max(1, cardW / TAB_W));
    }

    private int itemTabAt(double mx, double my) {
        if (TABS.isEmpty()) return -1;
        int y = cardY - STRIP_H + 1;
        if (my < y || my >= y + 20 || mx < cardX) return -1;
        int i = (int) ((mx - cardX) / TAB_W);
        int index = tabStart() + i;
        return index < TABS.size() && mx - cardX - i * TAB_W < TAB_W - 2 ? index : -1;
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
            // закрытая вкладка не должна возвращаться по «назад»
            if (!history.isEmpty()) history.pop();
        } else {
            onClose();
        }
    }

    private static final class Nav {
        @Nullable Stack focus;
        Lookup.Mode mode;
        List<Group> groups;
        int group;
        int page;
        /** Сколько рецептов и использований: считается один раз на предмет. */
        @Nullable int[] counts;

        Nav(@Nullable Stack focus, Lookup.Mode mode, List<Group> groups) {
            this.focus = focus;
            this.mode = mode;
            this.groups = groups;
        }
    }

    private record Placed(RecipeView recipe, int x, int y, int cardX, int cardY, int cardW, int cardH) {}

    private record StationSpot(Stack stack, int x, int y) {}

    private final List<StationSpot> stations = new ArrayList<>();

    @Nullable
    private final Screen parent;
    private final Deque<Nav> history = new ArrayDeque<>();
    private Nav nav;
    private int cardX, cardY, cardW, cardH;
    private int perPage = 1;
    /** Ширина карточки за это открытие: только растёт, иначе окно дёргается при смене вкладки. */
    private int widestCard;
    private int tabScroll;
    private final List<Placed> placed = new ArrayList<>();
    private final Map<RecipeView, Transfer> transfers = new IdentityHashMap<>();
    private final Map<RecipeView, Status> statuses = new IdentityHashMap<>();
    private int transferAge;

    private RecipeScreen(@Nullable Screen parent, Nav nav) {
        super(Component.translatable("ryscope.recipes.title"));
        this.parent = parent;
        this.nav = nav;
    }

    /** Открывает рецепты или использования ресурса. Если их нет, показывает уведомление и ничего не открывает. */
    public static boolean open(Stack stack, Lookup.Mode mode) {
        Minecraft mc = Minecraft.getInstance();
        List<Group> groups = Lookup.find(stack, mode);
        if (groups.isEmpty()) {
            // игрок чаще хочет увидеть хоть что-то: пробуем второй режим
            Lookup.Mode other = mode == Lookup.Mode.RECIPES ? Lookup.Mode.USES : Lookup.Mode.RECIPES;
            List<Group> alt = Lookup.find(stack, other);
            if (alt.isEmpty()) {
                Overlay.toast(Component.translatable("ryscope.recipes.none", stack.name()).getString());
                return false;
            }
            Overlay.toast(Component.translatable(mode == Lookup.Mode.RECIPES ? "ryscope.recipes.only_uses" : "ryscope.recipes.only_recipes").getString());
            groups = alt;
            mode = other;
        }
        Nav nav = new Nav(stack.withAmount(1), mode, groups);
        remember(nav.focus, mode);
        show(mc, nav);
        return true;
    }

    public static boolean openCategory(Category category) {
        List<Group> groups = Lookup.category(category);
        if (groups.isEmpty()) return false;
        show(Minecraft.getInstance(), new Nav(null, Lookup.Mode.RECIPES, groups));
        return true;
    }

    private static void show(Minecraft mc, Nav nav) {
        if (mc.screen instanceof RecipeScreen rs) {
            rs.history.push(rs.nav);
            rs.nav = nav;
            rs.tabScroll = 0;
            rs.transfers.clear();
        } else {
            mc.setScreen(new RecipeScreen(mc.screen, nav));
        }
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

    /** Строка под рецептом: сколько ингредиентов есть и чего не хватает. */
    private record Status(String head, String tail, String color) {}

    @Nullable
    private static Status statusOf(RecipeView recipe) {
        if (!recipe.isCrafting()) return null;
        boolean network = works.repka.ryscope.runtime.Ae2Terminal.active();
        if (!network && !works.repka.ryscope.runtime.InventoryView.tracking()) return null;
        int total = 0, have = 0;
        boolean stored = false;
        Map<String, Integer> missing = new java.util.LinkedHashMap<>();
        for (works.repka.ryscope.model.Ing in : recipe.inputs()) {
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
        if (missing.isEmpty()) return new Status(tr("ryscope.status.all." + place), "", "state.ok");
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : missing.entrySet()) {
            if (shown == 2) break;
            if (shown++ > 0) names.append(", ");
            names.append(e.getKey()).append(" ×").append(e.getValue());
        }
        if (missing.size() > 2) names.append(' ').append(Component.translatable("ryscope.status.more", missing.size() - 2).getString());
        String tail = tr("ryscope.status.have." + place) + " · " + Component.translatable("ryscope.status.missing", names.toString()).getString();
        if (stored) tail += " · " + tr("ryscope.status.stored");
        return new Status(Component.translatable("ryscope.status.head", have, total).getString(), tail, have == 0 ? "state.bad" : "state.warn");
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private int count(Lookup.Mode mode) {
        if (nav.focus == null) return 0;
        if (nav.counts == null) {
            nav.counts = new int[2];
            for (Lookup.Mode m : Lookup.Mode.values()) {
                int sum = 0;
                for (Group g : m == nav.mode ? nav.groups : Lookup.find(nav.focus, m)) sum += g.recipes().size();
                nav.counts[m == Lookup.Mode.RECIPES ? 0 : 1] = sum;
            }
        }
        return nav.counts[mode == Lookup.Mode.RECIPES ? 0 : 1];
    }

    // --- вкладки «рецепты» и «использования» ---

    private int modeW(Font font, Lookup.Mode mode) {
        int count = count(mode);
        String label = tr(mode == Lookup.Mode.RECIPES ? "ryscope.mode.recipes" : "ryscope.mode.uses");
        return font.width(label) + (count > 0 && modeCounts ? 4 + font.width(Integer.toString(count)) : 0) + 12;
    }

    private int modeY(Theme theme) {
        return cardY + PAD + (theme.lineTabs ? 0 : 1);
    }

    private int modeH(Theme theme) {
        return theme.lineTabs ? HEADER_H - 5 : 14;
    }

    private int treeX(Font font) {
        return cardX + PAD + modeW(font, Lookup.Mode.RECIPES) + 3 + modeW(font, Lookup.Mode.USES) + 4;
    }

    private int closeX() {
        return cardX + cardW - PAD - 14;
    }

    private int starX() {
        return closeX() - 18;
    }

    // --- кнопки справа от рецепта ---

    private boolean canTransfer(RecipeView recipe) {
        if (!(parent instanceof AbstractContainerScreen<?>)) return false;
        Transfer tr = transfers.get(recipe);
        return tr == null || tr.status() != Transfer.Status.UNSUPPORTED;
    }

    private boolean canGoal(RecipeView recipe) {
        return labelled && nav.focus != null && recipe.isCrafting() && !recipe.outputs().isEmpty();
    }

    private int buttonsTop(Placed p) {
        int total = canTransfer(p.recipe) && canGoal(p.recipe) ? 30 : canGoal(p.recipe) ? 13 : labelled ? 15 : 14;
        return p.cardY + (p.cardH - statusH - total) / 2;
    }

    private int[] transferRect(Placed p) {
        return new int[] {p.cardX + p.cardW - buttonCol + 2, buttonsTop(p), labelled ? buttonCol - 8 : 14, labelled ? 15 : 14};
    }

    private int[] goalRect(Placed p) {
        int top = buttonsTop(p) + (canTransfer(p.recipe) ? 17 : 0);
        return new int[] {p.cardX + p.cardW - buttonCol + 2, top, buttonCol - 8, 13};
    }

    private static boolean over(double mx, double my, int[] r) {
        return over(mx, my, r[0], r[1], r[2], r[3]);
    }

    private void relayout() {
        boolean compact = height < 300;
        PAD = compact ? 7 : 10;
        HEADER_H = compact ? 20 : 24;
        TABS_H = compact ? 22 : 24;
        TITLE_H = compact ? 13 : 14;
        FOOTER_H = 0;
        List<RecipeView> recipes = group().recipes();
        int maxW = 124, maxH = 20;
        for (RecipeView r : recipes) {
            maxW = Math.max(maxW, r.width());
            maxH = Math.max(maxH, r.height());
        }
        int plate = recipes.isEmpty() || !recipes.get(0).needsPlate() ? 0 : 3;
        boolean crafting = false;
        for (RecipeView r : recipes) crafting |= r.isCrafting();
        statusH = !compact && crafting && parent instanceof AbstractContainerScreen<?> ? STATUS_H : 0;
        int rowH = maxH + INNER * 2 + plate * 2 + statusH;
        Font font = Minecraft.getInstance().font;
        int labelW = Math.max(font.width(tr("ryscope.transfer.button")), Math.max(font.width(tr("ryscope.goals.add")), font.width(tr("ryscope.goals.in")))) + 14;
        labelled = maxW + plate * 2 + INNER * 2 + labelW + 8 + PAD * 2 <= width - 8;
        buttonCol = labelled ? labelW + 8 : BUTTON_ICON;
        widestCard = Math.max(widestCard, Math.max(MIN_CARD_W, maxW + plate * 2 + INNER * 2 + buttonCol + PAD * 2));
        cardW = Math.min(width - 8, widestCard);
        int chrome = PAD + HEADER_H + TABS_H + TITLE_H + FOOTER_H + PAD;
        // место под вкладки предметов оставлено всегда: иначе появление второй вкладки сдвинуло бы карточку
        cardY = Math.max(2, height / 18) + STRIP_H;
        int bottomGap = compact ? 3 : 6;
        int bodyAvail = height - cardY - bottomGap - chrome;
        perPage = Math.max(1, Math.min(recipes.size(), (bodyAvail + GAP) / (rowH + GAP)));
        int needed = chrome + perPage * (rowH + GAP) - GAP;
        if (cardY + needed > height - bottomGap) cardY = Math.max(STRIP_H + 2, height - bottomGap - needed);
        cardH = Math.min(height - cardY - bottomGap, needed);
        cardX = (width - cardW) / 2;
        modeCounts = true;
        modeCounts = nav.focus == null || treeX(font) + 15 + 4 <= starX();
        nav.page = Math.max(0, Math.min(nav.page, pages() - 1));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, ThemeManager.get().c("overlay"));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme theme = ThemeManager.get();
        Font font = Minecraft.getInstance().font;
        relayout();
        renderBackground(g, mx, my, partialTick);
        Overlay.renderBackground(this, g, mx, my, partialTick);
        theme.panel(g, cardX, cardY, cardW, cardH);

        // --- вкладки открытых предметов над карточкой ---
        if (!TABS.isEmpty()) {
            int sy = cardY - STRIP_H + 1;
            int start = tabStart();
            for (int i = start; i < TABS.size(); i++) {
                int sx = cardX + (i - start) * TAB_W;
                Tab tab = TABS.get(i);
                boolean current = nav.focus != null && tab.stack.same(nav.focus);
                theme.tab(g, sx, sy, TAB_W - 2, 20, current, over(mx, my, sx, sy, TAB_W - 2, 20));
                tab.stack.render(g, sx + 2, sy + 2);
            }
        }

        // --- шапка: режимы, дерево крафта, справка; сам предмет виден на вкладке над карточкой ---
        int hy = cardY + PAD;
        Group group = group();
        if (theme.lineTabs) Draw.rect(g, cardX + 1, hy + HEADER_H - 5, cardW - 2, 1, theme.c("card.border"));
        if (nav.focus != null) {
            int mode_y = modeY(theme), mode_h = modeH(theme), mode_x = cardX + PAD;
            for (Lookup.Mode mode : new Lookup.Mode[] {Lookup.Mode.RECIPES, Lookup.Mode.USES}) {
                int mw = modeW(font, mode), n = count(mode);
                boolean active = nav.mode == mode, hot = over(mx, my, mode_x, mode_y, mw, mode_h);
                theme.tabLine(g, mode_x, mode_y, mw, mode_h, active, hot);
                String label = tr(mode == Lookup.Mode.RECIPES ? "ryscope.mode.recipes" : "ryscope.mode.uses");
                int textY = mode_y + (mode_h - 8) / 2;
                Draw.text(g, label, mode_x + 6, textY, theme.tabLineFg(active, hot));
                if (n > 0 && modeCounts) Draw.text(g, Integer.toString(n), mode_x + 10 + font.width(label), textY, theme.lineTabs ? theme.c("text.dim") : theme.tabLineFg(active, hot));
                mode_x += mw + 3;
            }
            // кнопка «дерево крафта»
            int treeX = treeX(font);
            boolean th = over(mx, my, treeX, hy + 1, 15, 14);
            theme.button(g, treeX, hy + 1, 15, 14, th, false);
            int tc = th ? theme.c("accent") : theme.c("text.muted");
            Draw.rect(g, treeX + 3, hy + 4, 3, 3, tc);
            Draw.rect(g, treeX + 9, hy + 4, 3, 3, tc);
            Draw.rect(g, treeX + 9, hy + 9, 3, 3, tc);
            Draw.rect(g, treeX + 6, hy + 5, 3, 1, tc);
            Draw.rect(g, treeX + 7, hy + 5, 1, 6, tc);
            Draw.rect(g, treeX + 7, hy + 10, 2, 1, tc);
        }
        boolean closeHot = over(mx, my, closeX(), hy + 1, 14, 14);
        theme.button(g, closeX(), hy + 1, 14, 14, closeHot, false);
        Draw.cross(g, closeX() + 4, hy + 4, closeHot ? theme.c("accent") : theme.c("text.muted"));
        if (nav.focus != null) {
            boolean starHot = over(mx, my, starX(), hy + 1, 14, 14), marked = Bookmarks.contains(nav.focus);
            theme.button(g, starX(), hy + 1, 14, 14, starHot, false);
            Draw.star(g, starX() + 3, hy + 3, marked, marked || starHot ? theme.c("accent") : theme.c("text.muted"));
        }
        // --- вкладки категорий ---
        int ty = cardY + PAD + HEADER_H;
        int tabsW = cardW - PAD * 2;
        int visibleTabs = Math.max(1, tabsW / (TAB_W + 2));
        tabScroll = Math.max(0, Math.min(tabScroll, Math.max(0, nav.groups.size() - visibleTabs)));
        if (nav.group < tabScroll) tabScroll = nav.group;
        if (nav.group >= tabScroll + visibleTabs) tabScroll = nav.group - visibleTabs + 1;
        Category hoveredTab = null;
        for (int i = tabScroll; i < Math.min(nav.groups.size(), tabScroll + visibleTabs); i++) {
            int tx = cardX + PAD + (i - tabScroll) * (TAB_W + 2);
            boolean active = i == nav.group;
            boolean hov = over(mx, my, tx, ty, TAB_W, 20);
            theme.tab(g, tx, ty, TAB_W, 20, active, hov);
            try {
                nav.groups.get(i).category().renderIcon(g, tx + 3, ty + 2);
            } catch (Throwable ignored) {
                // значок чужой категории
            }
            if (hov) hoveredTab = nav.groups.get(i).category();
        }

        // --- заголовок категории и страницы ---
        int ly = ty + TABS_H;
        String catTitle = group.category().title.getString();
        String pageText = (nav.page + 1) + " / " + pages();
        int pageW = font.width(pageText);
        int px = cardX + cardW - PAD - pageW - 22;
        // значок подсказки в строке категории: в шапке его перекрывали вкладки
        int infoX = px - 15, infoY = ly + 1;
        boolean infoHot = over(mx, my, infoX - 1, infoY - 1, 12, 12);
        Draw.info(g, infoX, infoY, infoHot ? theme.c("accent") : theme.c("text.dim"));
        String shownTitle = Draw.ellipsize(catTitle, infoX - cardX - PAD - 8);
        Draw.text(g, shownTitle, cardX + PAD, ly + 2, theme.c("text.body"));
        stations.clear();
        int stx = cardX + PAD + font.width(shownTitle) + 8;
        for (Stack station : group.category().stations) {
            if (stx + 12 > infoX - 6 || stations.size() >= 6) break;
            g.pose().pushPose();
            g.pose().translate(stx, ly - 1, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            try {
                station.render(g, 0, 0);
            } catch (Throwable ignored) {
                // чужой рендер
            }
            g.pose().popPose();
            stations.add(new StationSpot(station, stx, ly - 1));
            stx += 14;
        }
        boolean multi = pages() > 1;
        int pageColor = multi ? theme.c("text.muted") : theme.c("text.dim");
        Draw.chevron(g, px, ly + 2, false, over(mx, my, px - 3, ly, 10, 11) && multi ? theme.c("accent") : pageColor);
        Draw.text(g, pageText, px + 11, ly + 2, pageColor);
        Draw.chevron(g, px + 15 + pageW, ly + 2, true, over(mx, my, px + 12 + pageW, ly, 10, 11) && multi ? theme.c("accent") : pageColor);

        // --- рецепты ---
        placed.clear();
        List<RecipeView> recipes = group.recipes();
        int maxH = 20;
        for (RecipeView r : recipes) maxH = Math.max(maxH, r.height());
        int by = ly + TITLE_H;
        boolean canTransfer = parent instanceof AbstractContainerScreen<?>;
        works.repka.ryscope.content.NativeRecipe.missingBadges = canTransfer;
        works.repka.ryscope.runtime.Ae2Terminal.setActive(parent instanceof AbstractContainerScreen<?> host ? host.getMenu() : null);
        for (int i = 0; i < perPage; i++) {
            int idx = nav.page * perPage + i;
            if (idx >= recipes.size()) break;
            RecipeView r = recipes.get(idx);
            int plate = r.needsPlate() ? 3 : 0;
            int rowH = maxH + INNER * 2 + plate * 2 + statusH;
            int rx0 = cardX + PAD, rw = cardW - PAD * 2;
            theme.card(g, rx0, by, rw, rowH);
            int areaW = rw - buttonCol;
            int rx = rx0 + Math.max(INNER + plate, (areaW - r.width()) / 2);
            int ry = by + (rowH - statusH - r.height()) / 2;
            if (plate > 0) theme.plate(g, rx - plate, ry - plate, r.width() + plate * 2, r.height() + plate * 2);
            try {
                r.render(g, theme, rx, ry, mx, my, partialTick);
            } catch (Throwable t) {
                Draw.text(g, "?", rx, ry, theme.c("state.bad"));
            }
            placed.add(new Placed(r, rx, ry, rx0, by, rw, rowH));
            works.repka.ryscope.runtime.Stages.Stage lock = works.repka.ryscope.runtime.Stages.lockOf(r, group.category());
            if (lock != null) {
                // рецепт закрытой стадии виден, но приглушён и подписан: что нужно сделать, чтобы открыть
                g.pose().pushPose();
                g.pose().translate(0, 0, 260);
                Draw.round(g, rx0 + 1, by + 1, rw - 2, rowH - 2, theme.r("card.radius"), Draw.alpha(theme.c("card.bg"), 0.78f));
                String stageText = Component.translatable("ryscope.stage.locked", lock.title()).getString();
                String need = works.repka.ryscope.runtime.Stages.requirement(lock);
                int tw = font.width(stageText);
                Draw.lock(g, rx0 + (rw - tw) / 2 - 11, by + rowH / 2 - 8, theme.c("state.warn"));
                Draw.text(g, stageText, rx0 + (rw - tw) / 2, by + rowH / 2 - 9, theme.c("text.primary"));
                if (!need.isEmpty()) {
                    String needText = Draw.ellipsize(need, (int) ((rw - 12) / 0.75f));
                    Draw.textScaled(g, needText, rx0 + (rw - font.width(needText) * 0.75f) / 2f, by + rowH / 2 + 3, 0.75f, theme.c("text.muted"));
                }
                g.pose().popPose();
            }
            if (Minecraft.getInstance().options.advancedItemTooltips && r.id() != null) {
                Draw.textScaled(g, Draw.ellipsize(r.id().toString(), (int) ((rw - 8) / 0.6f)), rx0 + 4, by + rowH - statusH - 7, 0.6f, theme.c("text.dim"));
            }

            Placed row = placed.get(placed.size() - 1);
            Transfer tr = transfers.get(r);
            if (canTransfer && tr != null && tr.status() != Transfer.Status.UNSUPPORTED) {
                int[] b = transferRect(row);
                boolean ok = tr.applicable();
                theme.button(g, b[0], b[1], b[2], b[3], over(mx, my, b), ok);
                int fg = ok ? theme.c("accent.text") : theme.c("text.dim");
                if (labelled) {
                    Draw.textCentered(g, tr("ryscope.transfer.button"), b[0] + b[2] / 2, b[1] + 4, fg);
                } else {
                    Draw.rect(g, b[0] + 6, b[1] + 3, 2, 8, fg);
                    Draw.rect(g, b[0] + 3, b[1] + 6, 8, 2, fg);
                }
                if (!ok) Draw.rect(g, b[0] + b[2] - 4, b[1] + 1, 3, 3, theme.c("state.bad"));
                else if (tr.status() == Transfer.Status.PARTIAL) Draw.rect(g, b[0] + b[2] - 4, b[1] + 1, 3, 3, theme.c("state.warn"));
            }
            if (canGoal(r)) {
                int[] b = goalRect(row);
                boolean set = works.repka.ryscope.runtime.Board.contains(r.outputs().get(0));
                theme.button(g, b[0], b[1], b[2], b[3], over(mx, my, b), false);
                Draw.textCentered(g, tr(set ? "ryscope.goals.in" : "ryscope.goals.add"), b[0] + b[2] / 2, b[1] + 3, set ? theme.c("accent") : theme.c("text.body"));
            }
            Status status = statusH > 0 ? statuses.get(r) : null;
            if (status != null) {
                int sx = rx0 + 4, sy = by + rowH - STATUS_BOX - 3, sw = rw - 8;
                Draw.roundBox(g, sx, sy, sw, STATUS_BOX, Math.min(theme.r("button.radius"), 3f), theme.c("panel.bg"), theme.c("panel.border"));
                Draw.dot(g, sx + 4, sy + 4, 4, theme.c(status.color));
                Draw.textScaled(g, status.head, sx + 12, sy + 3, 0.75f, theme.c("text.primary"));
                if (!status.tail.isEmpty()) {
                    float tailX = sx + 12 + font.width(status.head) * 0.75f + 3;
                    Draw.textScaled(g, Draw.ellipsize(status.tail, (int) ((sx + sw - 4 - tailX) / 0.75f)), tailX, sy + 3, 0.75f, theme.c("text.muted"));
                }
            }
            by += rowH + GAP;
        }

        // --- подсказки ---
        Overlay.renderTop(this, g, mx, my, partialTick);
        if (infoHot) {
            List<Component> help = new ArrayList<>();
            for (String part : Component.translatable("ryscope.recipes.hint").getString().split(" · ")) help.add(Component.literal(part));
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.renderComponentTooltip(font, help, mx, my);
            g.pose().popPose();
        }
        if (!Overlay.isOver(mx, my)) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            Stack hovered = recipeStackAt(mx, my);
            boolean byRecipe = false;
            if (hovered != null && !Lens.held()) {
                // рецепт чужого мода сам рисует подсказку слота: в ней бывают шансы и условия
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
                if (!drawn && canTransfer) {
                    for (Placed p : placed) {
                        Transfer tr = transfers.get(p.recipe);
                        if (tr != null && tr.status() != Transfer.Status.UNSUPPORTED && over(mx, my, transferRect(p))) {
                            if (!tr.lines().isEmpty()) {
                                g.renderComponentTooltip(font, tr.lines(), mx, my);
                                continue;
                            }
                            String key = tr.applicable() ? "ryscope.transfer.hint" : tr.message();
                            g.renderTooltip(font, Component.translatable(key == null ? "ryscope.transfer.missing" : key), mx, my);
                        }
                    }
                }
            }
            g.pose().popPose();
        }
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
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
        int itemTab = itemTabAt(mx, my);
        if (itemTab >= 0) return TABS.get(itemTab).stack;
        for (StationSpot s : stations) if (over(mx, my, s.x, s.y, 12, 12)) return s.stack;
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
        if (parent instanceof AbstractContainerScreen<?> acs && (transferAge++ % 10 == 0 || transfers.isEmpty())) {
            for (Placed p : placed) {
                try {
                    statuses.put(p.recipe, statusOf(p.recipe));
                } catch (Throwable t) {
                    statuses.remove(p.recipe);
                }
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
    }

    private void setPage(int page) {
        int n = pages();
        nav.page = ((page % n) + n) % n;
        transfers.clear();
    }

    private boolean goBack() {
        if (history.isEmpty()) return false;
        nav = history.pop();
        transfers.clear();
        return true;
    }

    private void switchMode(Lookup.Mode mode) {
        if (nav.focus == null || nav.mode == mode) return;
        List<Group> groups = Lookup.find(nav.focus, mode);
        if (groups.isEmpty()) {
            Overlay.toast(Component.translatable(mode == Lookup.Mode.RECIPES ? "ryscope.recipes.no_recipes" : "ryscope.recipes.no_uses").getString());
            return;
        }
        history.push(nav);
        nav = new Nav(nav.focus, mode, groups);
        remember(nav.focus, mode);
        transfers.clear();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (Overlay.mouseClicked(this, mx, my, button)) return true;
        int itemTab = itemTabAt(mx, my);
        if (itemTab >= 0) {
            // левая кнопка переключает вкладку, правая и средняя закрывают
            Tab tab = TABS.get(itemTab);
            if (button != 0) closeTab(itemTab);
            else if (nav.focus == null || !tab.stack.same(nav.focus)) open(tab.stack, tab.mode);
            return true;
        }
        Font font = Minecraft.getInstance().font;
        Theme clickTheme = ThemeManager.get();
        if (over(mx, my, closeX(), cardY + PAD + 1, 14, 14)) {
            onClose();
            return true;
        }
        // режимы
        if (nav.focus != null) {
            int hy = cardY + PAD + 1;
            if (over(mx, my, starX(), hy, 14, 14)) {
                boolean added = Bookmarks.toggle(nav.focus);
                Overlay.toast(tr(added ? "ryscope.bookmark.added" : "ryscope.bookmark.removed"));
                return true;
            }
            int modeTop = modeY(clickTheme), modeBottom = modeH(clickTheme);
            int recipesW = modeW(font, Lookup.Mode.RECIPES);
            if (over(mx, my, cardX + PAD, modeTop, recipesW, modeBottom)) {
                switchMode(Lookup.Mode.RECIPES);
                return true;
            }
            if (over(mx, my, cardX + PAD + recipesW + 3, modeTop, modeW(font, Lookup.Mode.USES), modeBottom)) {
                switchMode(Lookup.Mode.USES);
                return true;
            }
            if (over(mx, my, treeX(font), hy, 15, 14)) {
                TreeScreen.open(nav.focus);
                return true;
            }
        }
        // вкладки
        int ty = cardY + PAD + HEADER_H;
        int visibleTabs = Math.max(1, (cardW - PAD * 2) / (TAB_W + 2));
        for (int i = tabScroll; i < Math.min(nav.groups.size(), tabScroll + visibleTabs); i++) {
            int tx = cardX + PAD + (i - tabScroll) * (TAB_W + 2);
            if (over(mx, my, tx, ty, TAB_W, 20)) {
                setGroup(i);
                return true;
            }
        }
        // страницы
        int ly = ty + TABS_H;
        String pageText = (nav.page + 1) + " / " + pages();
        int pageW = font.width(pageText);
        int px = cardX + cardW - PAD - pageW - 22;
        if (over(mx, my, px - 3, ly, 10, 11)) {
            setPage(nav.page - 1);
            return true;
        }
        if (over(mx, my, px + 12 + pageW, ly, 10, 11)) {
            setPage(nav.page + 1);
            return true;
        }
        // в цели
        if (button == 0) {
            for (Placed p : placed) {
                if (!canGoal(p.recipe) || !over(mx, my, goalRect(p))) continue;
                boolean added = works.repka.ryscope.runtime.Board.toggle(p.recipe.outputs().get(0), 1);
                Overlay.toast(tr(added ? "ryscope.goals.added" : "ryscope.goals.removed"));
                return true;
            }
        }
        // перенос
        if (parent instanceof AbstractContainerScreen<?> acs && button == 0) {
            for (Placed p : placed) {
                if (!over(mx, my, transferRect(p))) continue;
                Transfer tr = transfers.get(p.recipe);
                if (tr == null || tr.status() == Transfer.Status.UNSUPPORTED) continue;
                doTransfer(acs, p.recipe, hasShiftDown());
                return true;
            }
        }
        // ресурсы в рецептах
        Stack stack = recipeStackAt((int) mx, (int) my);
        if (stack != null) {
            if (button == 2) {
                boolean added = Bookmarks.toggle(stack);
                Overlay.toast(Component.translatable(added ? "ryscope.bookmark.added" : "ryscope.bookmark.removed").getString());
            } else {
                open(stack, button == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
            }
            return true;
        }
        if (!over(mx, my, cardX, cardY, cardW, cardH) && !Overlay.isOver(mx, my) && button == 0) {
            onClose();
            return true;
        }
        return true;
    }

    private void doTransfer(AbstractContainerScreen<?> acs, RecipeView recipe, boolean max) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu != acs.getMenu()) {
            Overlay.toast(Component.translatable("ryscope.transfer.closed").getString());
            return;
        }
        Transfer result;
        try {
            result = recipe.transfer(acs, max, false);
        } catch (Throwable t) {
            result = Transfer.missing("ryscope.transfer.failed");
        }
        if (result.applicable()) {
            mc.setScreen(acs);
        } else {
            Overlay.toast(Component.translatable(result.message() == null ? "ryscope.transfer.missing" : result.message()).getString());
        }
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
        int ty = cardY + PAD + HEADER_H;
        if (over(mx, my, cardX, ty, cardW, TABS_H)) {
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
            // над рецептом, но не над слотом: копируется id рецепта
            double cx = Overlay.mouseX(), cy = Overlay.mouseY();
            for (Placed p : placed) {
                if (over(cx, cy, p.cardX, p.cardY, p.cardW, p.cardH) && p.recipe.id() != null) {
                    // Ctrl+Shift+C: весь рецепт в JSON, как в датапаке
                    if (hasShiftDown() && p.recipe instanceof works.repka.ryscope.content.NativeRecipe nr && nr.holder != null
                        && Minecraft.getInstance().level != null) {
                        var ops = Minecraft.getInstance().level.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                        String json = net.minecraft.world.item.crafting.Recipe.CODEC.encodeStart(ops, nr.holder.value()).result()
                            .map(j -> new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(j)).orElse(null);
                        if (json != null) {
                            Minecraft.getInstance().keyboardHandler.setClipboard(json);
                            Overlay.toast(Component.translatable("ryscope.copied.json", p.recipe.id().toString()).getString());
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
            // контейнер успели закрыть на сервере: возвращаться некуда
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
