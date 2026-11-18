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
    private static final int RAIL_W = 28, RAIL_SLOT = 20, RAIL_GAP = 2, RAIL_PAD = 5;
    private static final int PAD_X = 7;
    private static final int HEADER_H = 32;
    private static final int TABS_H = 16;
    /**
     * Строка категории: станции 12 единиц, масштаб 0.75 (решение автора 18.11: полные 16 забирают внимание и место),
     * сверху и снизу по 3. Сколько станций встанет после названия, решает ширина (не больше шести), остальное в «+N».
     */
    private static final int ROW_H = 18, ROW_ICON = 12, ROW_ICON_TOP = 3, ROW_STEP = 14, ROW_MAX_STATIONS = 6;
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
        /** Рецепт, страницу которого показать при первой раскладке (открыта закладка на рецепт). */
        @Nullable
        works.repka.ryscope.runtime.RecipeRef reveal;
        /** Версия сведений модов семьи, по которой собраны их категории: при новой версии карточки перечитываются. */
        int links = works.repka.ryscope.runtime.Links.version();

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
    /** Станция под всеми окнами: из дерева, плана или сравнения перенос и заказ AE2 идут в неё. */
    @Nullable
    private final AbstractContainerScreen<?> host;
    private final Deque<Nav> history = new ArrayDeque<>();
    /** Куда вернуться клавишей «вперёд» после «назад». Новый переход его очищает. */
    private final Deque<Nav> forward = new ArrayDeque<>();
    private Nav nav;
    private int cardX, cardY, cardW, cardH;
    private int widestCard;
    private final Kit.Hits hits = new Kit.Hits();
    private final List<StationSpot> stations = new ArrayList<>();
    private final List<StationSpot> historySpots = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();
    /** Версии инвентаря и сети AE2 при последнем пересчёте «+» и статусов. */
    private int transferInputs = Integer.MIN_VALUE;
    private final Map<RecipeView, Transfer> transfers = new IdentityHashMap<>();
    private final Map<RecipeView, Status> statuses = new IdentityHashMap<>();
    private int transferAge;
    private final Scroller edge = new Scroller();
    private final Scroller rail = new Scroller();
    @Nullable
    private RecipeView dragging;
    private List<Page> pages = List.of();
    private Object pagesFor;
    private Object widthFor;
    private int widthNeed;
    private int pagesAvail;
    private int tallestPage;
    /** Прокрутка страницы, которая не помещается в карточку целиком, и границы области рецептов. */
    private int pageScroll, recipeTop, recipeBottom = Integer.MAX_VALUE, pageContent;
    @Nullable
    private String pendingTip;
    /** Курсор над значком подсказки в шапке: показать клавиши карточки. */
    private boolean hintHover;
    /** «+N» у станций категории и плашка со всеми станциями: x, y, w, h; w = 0, если «+N» нет. */
    private final int[] stationsChip = new int[4], stationsPopup = new int[4];
    /** Название в строке категории, как нарисовано (для автотеста), и середина его левого края. */
    private String stripTitle = "";
    private final int[] stripTitleAt = new int[2];
    private boolean stationsOpen;
    private final List<StationSpot> popupSpots = new ArrayList<>();
    /** Что показывает плашка (станции или варианты ячейки), её заголовок и место, от которого она открыта. */
    private List<Stack> popupStacks = List.of();
    private String popupTitle = "";
    private final int[] popupAnchor = new int[4];
    private static final int POPUP_MAX = 72;

    /** Чип «N квестов» в шапке (макет {@code _audit/ryscope-quests/3_quests_chip}): упоминания предмета от модов семьи. */
    private static final int CHIP_H = 13, MENTION_W = 165, MENTION_ROW = 24, MENTION_RUBRIC = 12, MENTION_HEAD = 13, MENTION_MAX = 200;
    private List<works.repka.ryscope.runtime.Links.Shown> mentions = List.of();
    @Nullable
    private Stack mentionsFor;
    private int mentionsVersion = Integer.MIN_VALUE;
    /** Раскрытый список: номер чипа (-1: закрыт), закреплён ли кликом, место панельки и строк. */
    private int mentionsOpen = -1;
    private boolean mentionsPinned;
    private int[] mentionsBox = new int[4];
    private final Scroller mentionsScroll = new Scroller();

    private record MentionSpot(int x, int y, int w, int h, works.repka.ryscore.links.ItemLinks.Mention mention) {}

    private final List<MentionSpot> mentionSpots = new ArrayList<>();

    private RecipeScreen(@Nullable Screen parent, Nav nav) {
        super(Component.translatable("ryscope.recipes.title"));
        this.parent = parent;
        this.host = hostOf(parent);
        this.nav = nav;
    }

    /** Первый экран контейнера в цепочке родителей: карточка, дерево, план и сравнение ведут к нему. */
    @Nullable
    static AbstractContainerScreen<?> hostOf(@Nullable Screen screen) {
        for (int depth = 0; screen != null && depth < 32; depth++) {
            if (screen instanceof AbstractContainerScreen<?> acs) return acs;
            if (screen instanceof RecipeScreen rs) screen = rs.parent;
            else if (screen instanceof TreeScreen ts) screen = ts.backTarget();
            else if (screen instanceof PlannerScreen ps) screen = ps.backTarget();
            else if (screen instanceof CompareScreen cs) screen = cs.backTarget();
            else return null;
        }
        return null;
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
            Section s = HelpContent.isHelp(g.category()) ? Section.HELP : crafting ? Section.RECIPES : Section.SOURCES;
            out.get(s).add(g);
        }
        for (Group g : Lookup.find(stack, Lookup.Mode.USES)) {
            // страница справки и сведения JEI находятся и как рецепт, и как использование: в «Справку» один раз
            if (!HelpContent.isHelp(g.category())) out.get(Section.USES).add(g);
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
        if (stack instanceof works.repka.ryscope.runtime.RecipeMark mark) return openMark(mark, mode);
        Stack focus = stack.withAmount(1);
        Map<Section, List<Group>> sections = split(focus);
        AbstractContainerScreen<?> host = host();
        sections.put(Section.RECIPES, arrange(sections.get(Section.RECIPES), host, focus));
        sections.put(Section.USES, arrange(sections.get(Section.USES), host, null));
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
            if (section == Section.HELP && wanted == Section.RECIPES) {
                Overlay.toast(tr("ryscope.recipes.only_help_recipes"));
            } else if (section == Section.USES || wanted == Section.USES) {
                Overlay.toast(tr(mode == Lookup.Mode.RECIPES ? "ryscope.recipes.only_uses"
                    : section == Section.SOURCES ? "ryscope.recipes.only_sources"
                    : section == Section.HELP ? "ryscope.recipes.only_help" : "ryscope.recipes.only_recipes"));
            }
        }
        remember(focus, section == Section.USES ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
        Overlay.showCard();
        show(Minecraft.getInstance(), new Nav(focus, section, sections));
        return true;
    }

    public static boolean openCategory(Category category) {
        return openGroups(Lookup.category(category));
    }

    /**
     * Закладка на рецепт: карточка результата на странице с этим рецептом, рядом остальные рецепты.
     * Если среди рецептов результата его нет, открывается он один.
     */
    private static boolean openMark(works.repka.ryscope.runtime.RecipeMark mark, Lookup.Mode mode) {
        if (mode == Lookup.Mode.USES) return open(mark.output, mode);
        Minecraft mc = Minecraft.getInstance();
        if (open(mark.output, Lookup.Mode.RECIPES) && mc.screen instanceof RecipeScreen rs && rs.reveal(mark.ref)) return true;
        Group g = mark.group();
        if (g == null) {
            Overlay.toast(tr("ryscope.recipe_mark.gone"));
            return false;
        }
        return openGroups(List.of(g));
    }

    /** Перейти к разделу, категории и странице с рецептом. @return false, если в карточке его нет */
    private boolean reveal(works.repka.ryscope.runtime.RecipeRef ref) {
        for (Section s : Section.values()) {
            List<Group> groups = nav.sections.get(s);
            for (int gi = 0; gi < groups.size(); gi++) {
                for (RecipeView r : groups.get(gi).recipes()) {
                    if (!ref.matches(groups.get(gi).category(), r)) continue;
                    if (nav.section != s) {
                        nav.section = s;
                        nav.groups = groups;
                    }
                    nav.group = gi;
                    nav.page = 0;
                    nav.reveal = ref;
                    clearCaches();
                    revealRail();
                    return true;
                }
            }
        }
        return false;
    }

    /** Окно с инвентарём под этой карточкой или null. */
    @Nullable
    AbstractContainerScreen<?> containerParent() {
        return parent instanceof AbstractContainerScreen<?> acs ? acs : null;
    }

    /** Окно с инвентарём, над которым открыта карточка: в него идёт перенос. */
    @Nullable
    private static AbstractContainerScreen<?> host() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof RecipeScreen rs) screen = rs.parent;
        return screen instanceof AbstractContainerScreen<?> acs ? acs : null;
    }

    /** Сколько рецептов в категории ещё сортировать по наличию: дальше порядок источника. */
    private static final int SORT_LIMIT = 120;
    private static final long SORT_BUDGET_NANOS = 30_000_000L;

    /**
     * Порядок, как в JEI. Категории, которые переносятся в открытое окно, идут первыми (из окна обогатителя
     * сразу обогащение). Внутри категории первыми рецепты из закладок и рецепт по умолчанию, затем по доле
     * имеющихся ингредиентов. Считается один раз до показа, поэтому страница под руками не перестраивается.
     */
    private static List<Group> arrange(List<Group> groups, @Nullable AbstractContainerScreen<?> host, @Nullable Stack focus) {
        if (groups.isEmpty()) return groups;
        java.util.Set<net.minecraft.resources.ResourceLocation> pinned = new java.util.HashSet<>();
        for (Bookmarks.Folder folder : Bookmarks.folders()) {
            for (Stack s : folder.items) if (s instanceof works.repka.ryscope.runtime.RecipeMark m) pinned.add(m.ref.recipe());
        }
        works.repka.ryscope.runtime.RecipeRef preferred = focus == null ? null : works.repka.ryscope.runtime.RecipeDefaults.get(focus);
        if (preferred != null) pinned.add(preferred.recipe());
        boolean supply = works.repka.ryscope.runtime.Supply.tracking();
        long deadline = System.nanoTime() + SORT_BUDGET_NANOS;
        List<Group> out = new ArrayList<>(groups.size());
        for (Group g : groups) {
            boolean byStock = supply && g.recipes().size() > 1 && g.recipes().size() <= SORT_LIMIT && System.nanoTime() < deadline;
            out.add(sortRecipes(g, pinned, byStock));
        }
        if (host != null && out.size() > 1) {
            Map<Group, Boolean> fits = new IdentityHashMap<>();
            for (int i = 0; i < out.size() && i < 64; i++) fits.put(out.get(i), transfersTo(host, out.get(i)));
            out.sort(java.util.Comparator.comparing(g -> !fits.getOrDefault(g, false)));
        }
        return out;
    }

    private static Group sortRecipes(Group g, java.util.Set<net.minecraft.resources.ResourceLocation> pinned, boolean byStock) {
        List<RecipeView> recipes = g.recipes();
        boolean anyPinned = false;
        for (RecipeView r : recipes) anyPinned |= r.id() != null && pinned.contains(r.id());
        if (!anyPinned && !byStock) return g;
        Map<RecipeView, Integer> score = new IdentityHashMap<>();
        for (RecipeView r : recipes) {
            int value = r.id() != null && pinned.contains(r.id()) ? -1000 : 0;
            if (byStock) {
                try {
                    value -= matchPercent(r);
                } catch (Throwable ignored) {
                    // чужой рецепт
                }
            }
            score.put(r, value);
        }
        List<RecipeView> sorted = new ArrayList<>(recipes);
        sorted.sort(java.util.Comparator.comparingInt(score::get));
        return new Group(g.category(), sorted);
    }

    /** Доля ингредиентов из предметов, которые уже есть, в процентах; как сортировка JEI по наличию. */
    private static int matchPercent(RecipeView r) {
        if (!r.isCrafting()) return 0;
        List<Ing> inputs = r.inputs();
        works.repka.ryscope.runtime.Ae2Terminal.State[] states = works.repka.ryscope.runtime.Supply.states(inputs);
        int total = 0, have = 0;
        for (int i = 0; i < inputs.size(); i++) {
            Ing in = inputs.get(i);
            if (in.isEmpty() || !(in.options.get(0).key() instanceof net.minecraft.world.item.Item)) continue;
            total++;
            if (states[i] != works.repka.ryscope.runtime.Ae2Terminal.State.MISSING) have++;
        }
        return total == 0 ? 0 : have * 100 / total;
    }

    private static boolean transfersTo(AbstractContainerScreen<?> host, Group g) {
        if (g.recipes().isEmpty()) return false;
        RecipeView first = g.recipes().get(0);
        works.repka.ryscope.runtime.Foreign.Source foreign = works.repka.ryscope.runtime.Foreign.get();
        if (!(first instanceof works.repka.ryscope.content.NativeRecipe) && foreign != null) {
            try {
                Boolean known = foreign.transfersTo(host.getMenu(), g.category());
                if (known != null) return known;
            } catch (Throwable ignored) {
                // спросим сам рецепт
            }
        }
        try {
            return first.transfer(host, false, true).status() != Transfer.Status.UNSUPPORTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Несколько категорий одной карточкой, вкладками на рейке: так моды просят показать рецепты машины
     * (печь отдаёт «Плавку» и «Топливо»), а клик по стрелке в окне открывает все её категории.
     */
    public static boolean openGroups(List<Group> all) {
        List<Group> groups = new ArrayList<>();
        java.util.Set<Category> seen = new java.util.HashSet<>();
        for (Group g : all) if (!g.recipes().isEmpty() && seen.add(g.category())) groups.add(g);
        if (groups.isEmpty()) return false;
        groups = arrange(groups, host(), null);
        Map<Section, List<Group>> sections = new EnumMap<>(Section.class);
        for (Section s : Section.values()) sections.put(s, s == Section.RECIPES ? groups : List.of());
        Overlay.showCard();
        show(Minecraft.getInstance(), new Nav(null, Section.RECIPES, sections));
        return true;
    }

    private static void show(Minecraft mc, Nav nav) {
        if (mc.screen instanceof RecipeScreen rs) {
            rs.pushHistory(rs.nav);
            rs.forward.clear();
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
        widthFor = null;
    }

    /** Сколько переходов назад помнит карточка: у станции раздел держит тысячи рецептов. */
    private static final int HISTORY_LIMIT = 32;

    /**
     * Прежний раздел уходит в историю. Его рецепты больше не на экране: раскладки JEI отпускаются и при возврате
     * соберутся заново. Самые старые переходы забываются.
     */
    private void pushHistory(Nav old) {
        history.push(old);
        while (history.size() > HISTORY_LIMIT) history.removeLast();
        for (Group g : old.groups) for (RecipeView r : g.recipes()) r.release();
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

    /** Для автотеста: показать в текущем разделе группу первой подходящей категории. */
    public boolean selectGroupForTest(java.util.function.Predicate<Category> pick) {
        for (int i = 0; i < nav.groups.size(); i++) {
            if (!pick.test(nav.groups.get(i).category())) continue;
            setGroup(i);
            return true;
        }
        return false;
    }

    /** Для автотеста: строка категории последнего кадра (счётчик, название, «+N», значки станций). */
    public record StripForTest(String pager, String title, int titleX, int titleY, int[] chip, List<int[]> stations) {}

    public StripForTest stripForTest() {
        List<int[]> spots = new ArrayList<>();
        for (StationSpot s : stations) spots.add(new int[] {s.x, s.y, s.size});
        return new StripForTest(pagerText(group().recipes().size()), stripTitle, stripTitleAt[0], stripTitleAt[1], stationsChip.clone(), spots);
    }

    /** Для автотеста: открытый раздел, рецептов в каждом разделе, есть ли сведения JEI вне «Справки». */
    public record SectionsForTest(String current, Map<String, Integer> counts, boolean infoOutsideHelp, List<RecipeView> help) {}

    public SectionsForTest sectionsForTest() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        boolean stray = false;
        for (Section s : Section.values()) {
            counts.put(s.name().toLowerCase(java.util.Locale.ROOT), total(nav.sections.get(s)));
            if (s == Section.HELP) continue;
            for (Group g : nav.sections.get(s)) {
                String path = g.category().id.getPath();
                stray |= path.equals("information") || path.equals("info");
                for (RecipeView r : g.recipes()) stray |= r.getClass().getSimpleName().equals("JeiInfoView");
            }
        }
        List<RecipeView> help = new ArrayList<>();
        for (Group g : nav.sections.get(Section.HELP)) help.addAll(g.recipes());
        return new SectionsForTest(nav.section.name().toLowerCase(java.util.Locale.ROOT), counts, stray, help);
    }

    /** Для автотеста: перейти на раздел по имени («help»), как кликом по вкладке. */
    public void switchSectionForTest(String name) {
        switchSection(Section.valueOf(name.toUpperCase(java.util.Locale.ROOT)));
    }

    /** Для автотеста: прокрутка страницы и её предел. */
    public int[] scrollForTest() {
        return new int[] {pageScroll, maxScroll()};
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
        // вызывается каждый кадр дважды, а у станции в разделе тысячи рецептов: ширина считается раз на раздел
        Group current = group();
        if (widthFor != current) {
            int need = 0;
            for (RecipeView r : current.recipes()) {
                int plate = r.needsPlate() ? 3 : 0;
                // карточка поставщика растягивается на блок сама: в ширину окна идёт её наименьшая ширина
                int w = r instanceof works.repka.ryscope.content.LinkCardView ? works.repka.ryscope.content.LinkCardView.MIN_W : r.width();
                need = Math.max(need, w + plate * 2);
            }
            widthFor = current;
            widthNeed = need;
        }
        int need = widthNeed;
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
        if (nav.reveal != null) {
            List<RecipeView> recipes = group().recipes();
            for (int i = 0; i < recipes.size(); i++) {
                if (!nav.reveal.matches(group().category(), recipes.get(i))) continue;
                for (int p = 0; p < pages.size(); p++) if (i >= pages.get(p).start && i < pages.get(p).end) nav.page = p;
                break;
            }
            nav.reveal = null;
        }
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
        hintHover = false;
        placed.clear();
        stations.clear();
        historySpots.clear();
        chipAt = new int[0][];
        refreshLinks();
        relayout();
        renderBackground(g, mx, my, partialTick);
        Overlay.renderBackground(this, g, mx, my, partialTick);
        if (Overlay.cardCollapsed()) {
            closeMentions();
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
        renderPopup(g, t, mx, my);
        Overlay.renderTop(this, g, mx, my, partialTick);
        renderMentions(g, t, mx, my);
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
            Category category = nav.groups.get(i).category();
            // способ показан своей главной станцией, предметом в полный размер, как в строке над рецептом; значок категории, если станций нет
            try {
                if (!category.stations.isEmpty()) category.stations.get(0).render(g, x + 2, y + 2);
                else category.renderIcon(g, x + 2, y + 2);
            } catch (Throwable ignored) {
                // значок чужой категории
            }
            if (works.repka.ryscope.runtime.Stations.state(category) == works.repka.ryscope.runtime.Stations.State.MISSING) {
                // станции нет: значок приглушён, место в рейке то же (порядок под рукой не меняется)
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                Draw.round(g, x + 1, y + 1, RAIL_SLOT - 2, RAIL_SLOT - 2, 3.5f, t.c("slot.dim"));
                g.pose().popPose();
            }
            if (hot) hovered = category;
            hits.add(x, y, RAIL_SLOT, RAIL_SLOT, b -> {
                if (b == 1 && !(category instanceof works.repka.ryscope.runtime.Links.LinkCategory)) {
                    // правый клик: «у меня есть» → «нет» → определять самому
                    works.repka.ryscope.runtime.Stations.cycleMark(category);
                    Boolean mark = works.repka.ryscope.runtime.Stations.mark(category);
                    String key = mark == null ? "ryscope.stations.auto" : mark ? "ryscope.stations.owned" : "ryscope.stations.missing";
                    Overlay.toast(Component.translatable(key, TreeScreen.stationName(category)).getString());
                } else {
                    setGroup(index);
                }
            });
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
            hits.add(starX, buttonY, 15, 15, b -> Bookmarks.toggle(nav.focus));
        }
        // клавиши карточки: значок с подсказкой вместо строки внизу окна
        int infoX = shown != null ? starX - 14 : starX + 3, infoY = buttonY + 2;
        hintHover = mx >= infoX - 1 && mx < infoX + 11 && my >= infoY - 1 && my < infoY + 11;
        Draw.info(g, infoX, infoY, hintHover ? t.c("accent") : t.c("text.dim"));
        float textX = left + 20 + 6;
        int chipRight = renderChips(g, t, infoX - 6, buttonY + 1, textX, mx, my);
        float room = chipRight - 6 - textX;
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
        // значки 12 единиц, текст и стрелки по их середине
        int y = rowTop + ROW_ICON_TOP;
        float cy = y + ROW_ICON / 2f;
        int textTop = Math.round(cy - 5.5f);
        int x = cardX + railWidth() + PAD_X;
        List<Stack> all = group.category().stations;
        Stack mainStation = all.isEmpty() ? null : all.get(0);
        // у категории мода семьи (магазин книги) станций нет: её значок, картинка, а не предмет
        boolean linkIcon = mainStation == null && group.category() instanceof works.repka.ryscope.runtime.Links.LinkCategory lc && lc.icon != null;
        if (mainStation != null) {
            renderStation(g, mainStation, x, y);
            stations.add(new StationSpot(mainStation, x, y, ROW_ICON));
        } else if (linkIcon) {
            renderStation(g, group.category().icon, x, y);
        }
        float textX = x + (mainStation != null || linkIcon ? ROW_ICON + 3 : 0);
        int right = cardX + cardW - PAD_X;
        // счётчик: на одной странице сколько всего рецептов, без стрелок; на нескольких номера на странице и стрелки
        float pagerLeft = right;
        String pageText = pagerText(group.recipes().size());
        float pageW = Draw.labelWidth(pageText, 11.5f, Weight.REGULAR);
        if (pageCount() > 1) {
            int nextX = right - 6, prevX = nextX - 10;
            int prevC = over(mx, my, prevX - 2, textTop, 10, 11) ? t.c("accent") : t.c("button.fg");
            int nextC = over(mx, my, nextX - 2, textTop, 10, 11) ? t.c("accent") : t.c("button.fg");
            Icons.draw(g, Icons.Shape.CHEVRON_LEFT, prevX, cy - 3, 6f, prevC);
            Icons.draw(g, Icons.Shape.CHEVRON_RIGHT, nextX, cy - 3, 6f, nextC);
            hits.add(prevX - 2, textTop, 10, 11, b -> setPage(nav.page - 1));
            hits.add(nextX - 2, textTop, 10, 11, b -> setPage(nav.page + 1));
            pagerLeft = prevX - 4;
        }
        pagerLeft -= pageW;
        Draw.label(g, pageText, pagerLeft, cy, 11.5f, Weight.REGULAR, t.c("text.muted"));
        // остальные станции, на которых делается то же самое; длинное название уступает место хотя бы «+N»
        int rest = Math.max(0, all.size() - 1);
        float limit = pagerLeft - 5;
        float reserve = rest > 0 ? 5 + Draw.labelWidth("+" + rest, 11.5f, Weight.REGULAR) + 4 : 0;
        String fullTitle = group.category().title.getString();
        float titleRoom = Math.max(0, limit - 1 - textX - reserve);
        String title = Draw.ellipsize(fullTitle, titleRoom, 12.5f, Weight.REGULAR);
        // заголовок открывает все рецепты категории, как в JEI
        float titleW = Draw.labelWidth(title, 12.5f, Weight.REGULAR);
        // в самом просмотре категории ссылка не нужна, у справки тоже: все страницы игры разом ни к чему
        // категория мода семьи живёт только в карточке предмета: отдельного просмотра у неё нет
        boolean titleLink = nav.focus != null && nav.section != Section.HELP && !(group.category() instanceof works.repka.ryscope.runtime.Links.LinkCategory);
        boolean titleOver = over(mx, my, (int) textX, textTop, (int) Math.ceil(titleW), 11);
        boolean titleHot = titleLink && titleOver;
        Draw.label(g, title, textX, cy, 12.5f, Weight.REGULAR, titleHot ? t.c("accent") : t.c("text.primary"));
        stripTitle = title;
        stripTitleAt[0] = (int) textX;
        stripTitleAt[1] = Math.round(cy);
        Category shownCategory = group.category();
        if (titleLink) {
            hits.add((int) textX, textTop, (int) Math.ceil(titleW), 11, b -> {
                if (b == 0) openCategory(shownCategory);
            });
        }
        if (titleOver) {
            // обрезанное название показывает себя целиком
            boolean cut = !title.equals(fullTitle);
            if (titleLink) pendingTip = cut ? fullTitle + "\n" + tr("ryscope.recipes.category_all") : tr("ryscope.recipes.category_all");
            else if (cut) pendingTip = fullTitle;
        }
        // целая координата: значок ложится на пиксели без сдвига
        float stx = Math.round(textX + titleW + 5);
        int fit = stationsFit(rest, stx, limit, rest > 0 ? Draw.labelWidth("+" + rest, 11.5f, Weight.REGULAR) + 4 : 0);
        for (int i = 1; i <= fit; i++) {
            Stack station = all.get(i);
            renderStation(g, station, stx, y);
            stations.add(new StationSpot(station, (int) stx, y, ROW_ICON));
            stx += ROW_STEP;
        }
        stationsChip[2] = 0;
        if (fit < rest) {
            String more = "+" + (rest - fit);
            float moreW = Draw.labelWidth(more, 11.5f, Weight.REGULAR);
            if (stx + moreW <= limit + 5) {
                // цель наведения во всю высоту значков
                stationsChip[0] = (int) stx;
                stationsChip[1] = y;
                stationsChip[2] = (int) Math.ceil(moreW) + 4;
                stationsChip[3] = ROW_ICON;
                boolean hot = (stationsOpen && popupStacks == all) || over(mx, my, stationsChip[0], stationsChip[1], stationsChip[2], stationsChip[3]);
                Draw.label(g, more, stx + 2, cy, 11.5f, Weight.REGULAR, hot ? t.c("accent") : t.c("text.muted"));
            }
        }
    }

    /** Текст счётчика: «5 рецептов» на единственной странице, «1–2 из 5» при нескольких. */
    private String pagerText(int total) {
        if (pageCount() <= 1) {
            // поставщик считает своими словами: «3 сделки»
            String own = works.repka.ryscope.runtime.Links.count(group().category(), total);
            return own != null ? own : Overlay.plural("ryscope.recipes.total", total, Integer.toString(total));
        }
        Page page = pages.isEmpty() ? new Page(0, 0) : pages.get(nav.page);
        String range = page.end - page.start > 1 ? (page.start + 1) + "–" + page.end : Integer.toString(page.start + 1);
        return tr("ryscope.recipes.range", range, total);
    }

    /**
     * Сколько дополнительных станций встаёт в строку после названия: сколько позволяет ширина, но не больше {@link #ROW_MAX_STATIONS},
     * при нехватке места рядом остаётся «+N» шириной {@code chipW}.
     */
    static int stationsFit(int rest, float stx, float limit, float chipW) {
        if (rest <= 0) return 0;
        if (rest <= ROW_MAX_STATIONS && stx + rest * ROW_STEP - 1 <= limit) return rest;
        return Math.max(0, Math.min(ROW_MAX_STATIONS, (int) ((limit - stx - chipW) / ROW_STEP)));
    }

    /** Станция строки категории: 12 единиц, масштаб 0.75 (исключение из правила 16 или 8, решение автора). */
    private static void renderStation(GuiGraphics g, Stack stack, float x, float y) {
        renderScaled(g, stack, x, y, ROW_ICON / 16f);
    }

    private void openPopup(List<Stack> stacks, int x, int y, int w, int h, String title) {
        popupStacks = stacks;
        popupTitle = title;
        popupAnchor[0] = x;
        popupAnchor[1] = y;
        popupAnchor[2] = w;
        popupAnchor[3] = h;
    }

    /**
     * Плашка со значками в полный размер: все станции категории по наведению на «+N» или все варианты ячейки
     * по Ctrl+клику. Открыта, пока курсор над местом, откуда она открыта, или над ней; по значкам работают
     * клики и клавиши, как по любому предмету карточки.
     */
    private void renderPopup(GuiGraphics g, Theme t, int mx, int my) {
        popupSpots.clear();
        List<Stack> stations = group().category().stations;
        if (stationsChip[2] > 0 && over(mx, my, stationsChip[0], stationsChip[1], stationsChip[2], stationsChip[3]) && popupStacks != stations) {
            openPopup(stations, stationsChip[0], stationsChip[1], stationsChip[2], stationsChip[3], tr("ryscope.recipes.stations", stations.size()));
        }
        boolean overAnchor = over(mx, my, popupAnchor[0], popupAnchor[1], popupAnchor[2], popupAnchor[3]);
        boolean overPopup = stationsOpen && over(mx, my, stationsPopup[0], stationsPopup[1], stationsPopup[2], stationsPopup[3]);
        stationsOpen = !popupStacks.isEmpty() && (overAnchor || overPopup);
        if (!stationsOpen) {
            popupStacks = List.of();
            return;
        }
        List<Stack> all = popupStacks;
        int shown = Math.min(all.size(), POPUP_MAX);
        int cell = 18, cols = Math.min(9, shown), rows = (shown + cols - 1) / cols, titleH = 12;
        int w = Math.max(cols * cell + 8, (int) Math.ceil(Draw.labelWidth(popupTitle, 11.5f, Weight.REGULAR)) + 10);
        int h = rows * cell + 8 + titleH;
        int x = Math.max(4, Math.min(popupAnchor[0], width - 4 - w));
        int below = popupAnchor[1] + popupAnchor[3] + 1;
        int y = below + h <= height - 4 ? below : Math.max(4, popupAnchor[1] - 1 - h);
        // область «над плашкой» перекрывает и щель до места, откуда она открыта
        int top = Math.min(y, popupAnchor[1] + popupAnchor[3]), bottom = Math.max(y + h, popupAnchor[1]);
        stationsPopup[0] = x;
        stationsPopup[1] = top;
        stationsPopup[2] = w;
        stationsPopup[3] = bottom - top;
        g.pose().pushPose();
        g.pose().translate(0, 0, 350);
        Draw.roundBox(g, x, y, w, h, 5f, t.c("panel.bg"), t.c("panel.border"));
        Draw.label(g, popupTitle, x + 5, y + 7, 11.5f, Weight.REGULAR, t.c("text.muted"));
        for (int i = 0; i < shown; i++) {
            int sx = x + 4 + (i % cols) * cell, sy = y + 4 + titleH + (i / cols) * cell;
            t.slot(g, sx, sy, cell, over(mx, my, sx, sy, cell, cell));
            all.get(i).render(g, sx + 1, sy + 1);
            popupSpots.add(new StationSpot(all.get(i), sx, sy, cell));
        }
        g.pose().popPose();
    }

    // --- сведения модов семьи: чип «N квестов» и его список ---

    /**
     * Раз в кадр: если поставщик поднял версию, перечитать его категории (только их, рецепты JEI не трогаются)
     * и упоминания для чипа. Сравнение двух чисел, пока ничего не менялось.
     */
    private void refreshLinks() {
        int v = works.repka.ryscope.runtime.Links.version();
        if (nav.links != v) {
            nav.links = v;
            if (nav.focus != null) relinkSections();
        }
        if (mentionsFor != nav.focus || mentionsVersion != v) {
            mentionsFor = nav.focus;
            mentionsVersion = v;
            mentions = nav.focus == null ? List.of() : works.repka.ryscope.runtime.Links.mentions(nav.focus);
            if (mentionsOpen >= mentions.size()) closeMentions();
        }
    }

    private void relinkSections() {
        List<Group> gives = works.repka.ryscope.runtime.Links.groups(nav.focus, Lookup.Mode.RECIPES);
        List<Group> takes = works.repka.ryscope.runtime.Links.groups(nav.focus, Lookup.Mode.USES);
        java.util.function.Predicate<Group> isLink = g -> g.category() instanceof works.repka.ryscope.runtime.Links.LinkCategory;
        Map<Section, List<Group>> next = new EnumMap<>(nav.sections);
        next.put(Section.SOURCES, works.repka.ryscope.runtime.LinkMath.replace(nav.sections.get(Section.SOURCES), gives, Group::category, isLink));
        next.put(Section.USES, works.repka.ryscope.runtime.LinkMath.replace(nav.sections.get(Section.USES), takes, Group::category, isLink));
        Section section = nav.section;
        if (next.get(section).isEmpty()) {
            // открытый раздел опустел (сделка исчезла): соседний непустой; если пусто всё, остаётся прежний вид
            section = null;
            for (Section s : new Section[] {Section.RECIPES, Section.SOURCES, Section.USES, Section.HELP}) {
                if (!next.get(s).isEmpty()) {
                    section = s;
                    break;
                }
            }
            if (section == null) return;
        }
        Category current = nav.groups.isEmpty() ? null : group().category();
        nav.sections.putAll(next);
        nav.section = section;
        nav.groups = nav.sections.get(section);
        int index = -1;
        for (int i = 0; i < nav.groups.size(); i++) if (nav.groups.get(i).category().equals(current)) index = i;
        nav.group = index >= 0 ? index : Math.min(nav.group, nav.groups.size() - 1);
        // страница и прокрутка на месте: обновились числа на карточке, а не её место
        pagesFor = null;
        widthFor = null;
        transfers.clear();
        statuses.clear();
    }

    /**
     * Чипы поставщиков справа налево от {@code right}; название предмета сохраняет хотя бы 48 единиц.
     * @return левый край крайнего чипа или {@code right + 6}, если чипов нет
     */
    private int renderChips(GuiGraphics g, Theme t, int right, int y, float textX, int mx, int my) {
        chipAt = new int[mentions.size()][];
        int x = right, left = right + 6;
        for (int i = mentions.size() - 1; i >= 0; i--) {
            String label = mentions.get(i).mentions().label().getString();
            String shown = Draw.ellipsize(label, 80, 11.5f, Weight.REGULAR);
            int w = (int) Math.ceil(3.5f + 7 + 3 + Draw.labelWidth(shown, 11.5f, Weight.REGULAR) + 3 + 5 + 4);
            int cx = x - w;
            if (cx - 6 - textX < 48) break;
            boolean over = over(mx, my, cx, y, w, CHIP_H), hot = over || mentionsOpen == i;
            if (t.beveled()) {
                t.button(g, cx, y, w, CHIP_H, hot, false);
            } else {
                Draw.roundBox(g, cx, y, w, CHIP_H, 3.5f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), t.c("button.border"));
            }
            Icons.draw(g, Icons.Shape.BOOK, cx + 3.5f, y + 3f, 7f, t.c("link"));
            Draw.label(g, shown, cx + 13.5f, y + CHIP_H / 2f, 11.5f, Weight.REGULAR, t.c("text.primary"));
            Icons.draw(g, Icons.Shape.CHEVRON_DOWN, cx + w - 9f, y + 4f, 5f, t.c("button.fg"));
            if (over && !shown.equals(label)) pendingTip = label;
            chipAt[i] = new int[] {cx, y, w, CHIP_H};
            left = cx;
            x = cx - 4;
        }
        return left;
    }

    private int[][] chipAt = new int[0][];

    private boolean overMentions(double mx, double my) {
        return mentionsOpen >= 0 && over(mx, my, mentionsBox[0], mentionsBox[1], mentionsBox[2], mentionsBox[3]);
    }

    private void closeMentions() {
        mentionsOpen = -1;
        mentionsPinned = false;
        mentionsBox = new int[4];
    }

    /**
     * Список упоминаний под чипом: открыт, пока курсор над чипом, щелью или самим списком, а после клика по чипу
     * закреплён до клика мимо или Esc. Группы «Нужен в задачах», «Даётся в награду», «Магазин» от поставщика,
     * в строке значок, название, пояснение и состояние; клик по строке выполняет действие поставщика.
     */
    private void renderMentions(GuiGraphics g, Theme t, int mx, int my) {
        mentionSpots.clear();
        int hover = -1;
        for (int i = 0; i < chipAt.length; i++) if (chipAt[i] != null && over(mx, my, chipAt[i][0], chipAt[i][1], chipAt[i][2], chipAt[i][3])) hover = i;
        if (!mentionsPinned) {
            boolean keep = false;
            if (mentionsOpen >= 0 && mentionsOpen < chipAt.length && chipAt[mentionsOpen] != null) {
                int[] chip = chipAt[mentionsOpen];
                int top = Math.min(chip[1], mentionsBox[1]), bottom = Math.max(chip[1] + chip[3], mentionsBox[1] + mentionsBox[3]);
                keep = overMentions(mx, my) || over(mx, my, chip[0], top, chip[2], bottom - top);
            }
            int open = hover >= 0 ? hover : keep ? mentionsOpen : -1;
            if (open != mentionsOpen) mentionsScroll.reset();
            mentionsOpen = open;
        }
        if (mentionsOpen < 0 || mentionsOpen >= chipAt.length || chipAt[mentionsOpen] == null || Overlay.cardCollapsed()) {
            closeMentions();
            return;
        }
        works.repka.ryscore.links.ItemLinks.Mentions shown = mentions.get(mentionsOpen).mentions();
        Map<String, List<works.repka.ryscore.links.ItemLinks.Mention>> sections =
            works.repka.ryscope.runtime.LinkMath.byGroup(shown.items(), m -> m.section().getString());
        int content = 0;
        for (List<works.repka.ryscore.links.ItemLinks.Mention> list : sections.values()) content += MENTION_RUBRIC + list.size() * MENTION_ROW;
        int[] window = works.repka.ryscope.runtime.LinkMath.window(content, Math.min(MENTION_MAX, height - 8 - MENTION_HEAD - 9 - (chipAt[mentionsOpen][1] + CHIP_H)));
        int listH = window[0], maxScroll = window[1];
        int h = 5 + MENTION_HEAD + listH + 4;
        int[] chip = chipAt[mentionsOpen];
        String title = shown.title().getString();
        int fmx = mx, fmy = my, fcontent = content;
        mentionsBox = Kit.popover(g, t, chip[0] + chip[2] - MENTION_W, chip[1], MENTION_W, chip[3], MENTION_W, h, Kit.Side.BELOW, null, (gg, bx, by, bw, bh) -> {
            Draw.label(gg, Draw.ellipsize(title, bw - 16, 12, Weight.MEDIUM), bx + 8, by + 5 + 4.5f, 12, Weight.MEDIUM, t.c("text.primary"));
            int top = by + 5 + MENTION_HEAD, bottom = Math.min(by + bh - 4, top + listH);
            float pos = mentionsScroll.update(maxScroll);
            Draw.scissor(gg, bx, top, bx + bw, bottom);
            float y = top - pos;
            for (Map.Entry<String, List<works.repka.ryscore.links.ItemLinks.Mention>> e : sections.entrySet()) {
                Kit.caps(gg, t, e.getKey() + " · " + e.getValue().size(), bx + 8, y + 7.5f, 11, 0.44f);
                y += MENTION_RUBRIC;
                for (works.repka.ryscore.links.ItemLinks.Mention m : e.getValue()) {
                    int ry = Math.round(y);
                    boolean visible = ry + MENTION_ROW > top && ry < bottom;
                    boolean hot = visible && m.open() != null && fmy >= top && fmy < bottom && over(fmx, fmy, bx + 4, ry, bw - 8, MENTION_ROW);
                    if (visible) renderMention(gg, t, m, bx, ry, bw, hot, fmx, fmy);
                    if (visible) mentionSpots.add(new MentionSpot(bx + 4, Math.max(ry, top), bw - 8, Math.min(ry + MENTION_ROW, bottom) - Math.max(ry, top), m));
                    y += MENTION_ROW;
                }
            }
            gg.disableScissor();
            if (maxScroll > 0) {
                int areaH = bottom - top, thumb = Math.max(10, areaH * areaH / fcontent);
                int thumbY = top + Math.round((areaH - thumb) * Math.max(0, Math.min(1, pos / maxScroll)));
                Draw.round(gg, bx + bw - 4, thumbY, 2, thumb, 1f, Draw.alpha(t.c("text.dim"), 0.7f));
            }
        });
    }

    private void renderMention(GuiGraphics g, Theme t, works.repka.ryscore.links.ItemLinks.Mention m, int bx, int y, int bw, boolean hot, int mx, int my) {
        if (hot) Draw.round(g, bx + 4, y, bw - 8, MENTION_ROW, 4f, t.c("slot.hover"));
        int sx = bx + 8, sy = y + 3;
        Draw.roundBox(g, sx, sy, 18, 18, 3.5f, t.c("slot.bg"), t.c("slot.border"));
        if (!m.icon().isEmpty()) {
            try {
                g.renderFakeItem(m.icon(), sx + 1, sy + 1);
            } catch (Throwable ignored) {
                // чужой рендер значка
            }
        } else {
            Icons.draw(g, Icons.Shape.BOOK, sx + 4, sy + 4, 10f, t.c("link"));
        }
        float tx = sx + 18 + 5, room = bx + bw - 16 - tx;
        String title = m.title().getString(), detail = m.detail() == null ? "" : m.detail().getString();
        String titleShown = Draw.ellipsize(title, room, 12.5f, Weight.REGULAR), detailShown = Draw.ellipsize(detail, room, 11, Weight.REGULAR);
        Draw.label(g, titleShown, tx, y + 8.5f, 12.5f, Weight.REGULAR, t.c("text.primary"));
        if (!detail.isEmpty()) Draw.label(g, detailShown, tx, y + 16.5f, 11, Weight.REGULAR, t.c("text.muted"));
        float ix = bx + bw - 13, iy = y + (MENTION_ROW - 5) / 2f;
        switch (m.state()) {
            case DONE -> Kit.indicator(g, t, ix, iy, 5f, Kit.State.FULL, -1, -1);
            case ACTIVE -> Kit.indicator(g, t, ix, iy, 5f, Kit.State.PARTIAL, -1, -1);
            case OPEN -> Draw.ring(g, ix + 2.5f, iy + 2.5f, 2.5f, 0.75f, t.c("text.muted"));
            case LOCKED -> Draw.lock(g, Math.round(ix) - 1, Math.round(iy) - 1, t.c("text.dim"));
        }
        // обрезанное многоточием показывает себя целиком
        if (over(mx, my, (int) tx, y, (int) room, MENTION_ROW) && (!titleShown.equals(title) || !detailShown.equals(detail))) {
            pendingTip = detail.isEmpty() ? title : title + "\n" + detail;
        }
    }

    /** Нажатие по чипу (закрепить или снять список) и по строке списка. */
    private boolean mentionsClick(double mx, double my, int button) {
        for (int i = 0; i < chipAt.length; i++) {
            int[] c = chipAt[i];
            if (c == null || !over(mx, my, c[0], c[1], c[2], c[3])) continue;
            if (button == 0) {
                if (mentionsOpen == i && mentionsPinned) {
                    closeMentions();
                } else {
                    if (mentionsOpen != i) mentionsScroll.reset();
                    mentionsOpen = i;
                    mentionsPinned = true;
                }
            }
            return true;
        }
        if (overMentions(mx, my)) {
            for (MentionSpot s : mentionSpots) {
                if (button != 0 || s.mention.open() == null || !over(mx, my, s.x, s.y, s.w, s.h)) continue;
                works.repka.ryscore.links.ItemLinks.Action open = s.mention.open();
                closeMentions();
                works.repka.ryscope.runtime.Links.run(open);
                break;
            }
            return true;
        }
        if (mentionsPinned) {
            // клик мимо закреплённого списка только закрывает его
            closeMentions();
            return true;
        }
        return false;
    }

    /** Для автотеста: чипы, раскрытый список и его строки последнего кадра. */
    public record LinksForTest(List<String> chips, List<int[]> chipRects, int open, int[] box, List<String> rows, List<int[]> rowRects) {}

    public LinksForTest linksForTest() {
        List<String> labels = new ArrayList<>();
        List<int[]> rects = new ArrayList<>();
        for (int i = 0; i < mentions.size(); i++) {
            labels.add(mentions.get(i).mentions().label().getString());
            rects.add(i < chipAt.length && chipAt[i] != null ? chipAt[i].clone() : null);
        }
        List<String> rows = new ArrayList<>();
        List<int[]> rowRects = new ArrayList<>();
        for (MentionSpot s : mentionSpots) {
            rows.add(s.mention.title().getString());
            rowRects.add(new int[] {s.x, s.y, s.w, s.h});
        }
        return new LinksForTest(labels, rects, mentionsOpen, mentionsBox.clone(), rows, rowRects);
    }

    /** Для автотеста: раскрыть список чипа, как кликом. */
    public void openMentionsForTest(int index) {
        mentionsOpen = index;
        mentionsPinned = index >= 0;
        mentionsScroll.reset();
    }

    /** Для автотеста: нажатие в координатах карточки (единицы интерфейса), как мышью. */
    public boolean clickForTest(double x, double y, int button) {
        return onClick(x, y, button);
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
        HelpContent.setFocus(nav.focus);
        works.repka.ryscope.runtime.Ae2Terminal.setActive(host != null ? host.getMenu() : null);
        // блок рецепта не шире, чем нужно самому широкому рецепту раздела; на широкой карточке стоит по центру
        int areaW = cardW - railWidth() - PAD_X * 2;
        int blockW = Math.min(areaW, widestCard - railWidth() - PAD_X * 2);
        int blockX = cardX + railWidth() + PAD_X + (areaW - blockW) / 2;
        // упор у края постраничного списка: рецепты чуть сдвигаются и возвращаются
        double scale = Draw.scale();
        float push = (float) (Math.round(edge.update(0) * scale) / scale);
        Draw.scissor(g, cardX, top, cardX + cardW, bottom);
        // подсказки, которые мод рисует внутри рецепта, иначе обрезаются этой рамкой
        TooltipDefer.begin();
        g.pose().pushPose();
        g.pose().translate(0, -push, 0);
        int y = top + BLOCK_MARGIN - pageScroll;
        for (int i = page.start; i < page.end; i++) {
            RecipeView r = recipes.get(i);
            int h = blockHeight(r);
            // кнопок справа у карточки поставщика нет: она занимает весь блок
            if (r instanceof works.repka.ryscope.content.LinkCardView link) link.stretch(blockW - BLOCK_PAD * 2);
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
            Placed row = new Placed(r, rx, ry, innerX, rowY, innerW, rowH);
            // подсветка нехватки в ячейках только пока курсор над «+» этого рецепта, как в JEI
            works.repka.ryscope.content.NativeRecipe.missingBadges = overPlus(row, mx, my);
            try {
                r.render(g, t, rx, ry, mx, my, partialTick);
            } catch (Throwable th) {
                Draw.text(g, "?", rx, ry, t.c("state.bad"));
            }
            works.repka.ryscope.content.NativeRecipe.missingBadges = true;
            placed.add(row);
            renderLock(g, t, r, blockX, y, blockW, h);
            // кнопки только целиком видимые: срезанные краем не должны нажиматься сквозь шапку
            if (rowY >= top && rowY + BUTTON * 2 + BUTTON_GAP <= bottom) renderButtons(g, t, row, mx, my);
            renderExtras(g, t, row, mx, my);
            if (r.isCrafting()) renderStatus(g, t, r, innerX, innerW, rowY + rowH + STATUS_GAP, mx, my);
            y += h + BLOCK_MARGIN * 2;
        }
        g.pose().popPose();
        TooltipDefer.end();
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
        return r.showsTransfer();
    }

    /** Стадия, которая не даёт выполнить рецепт на сервере (блокирующая, enforce), или null. */
    @Nullable
    private works.repka.ryscope.runtime.Stages.Stage blockingStage(RecipeView r) {
        works.repka.ryscope.runtime.Stages.Stage lock = works.repka.ryscope.runtime.Stages.lockOf(r, group().category());
        return lock != null && lock.enforce ? lock : null;
    }

    /** Перенос можно нажать: окно над станцией, проверка готова и рецепт туда раскладывается. */
    private boolean transferEnabled(RecipeView r) {
        if (host == null || blockingStage(r) != null) return false;
        Transfer tr = transfers.get(r);
        return tr != null && tr.status() != Transfer.Status.UNSUPPORTED;
    }

    /** Курсор над «+» рецепта или над значком состояния слева от него. */
    private boolean overPlus(Placed row, double mx, double my) {
        if (!showsTransfer(row.recipe) || !inRecipes(my)) return false;
        int x = row.rowX + row.rowW - BUTTON, y = plusY(row);
        return over(mx, my, x - 9, y - 2, BUTTON + 11, BUTTON + 4);
    }

    /** Булавка цели: правый верхний угол блока рецепта. */
    private int pinY(Placed row) {
        return row.rowY;
    }

    /** «+» по вертикальному центру у той же стенки; под булавкой, если строка низкая. */
    private int plusY(Placed row) {
        int centered = row.rowY + (row.rowH - BUTTON) / 2;
        return canGoal(row.recipe) ? Math.max(centered, row.rowY + BUTTON + BUTTON_GAP) : centered;
    }

    private static Kit.State stateOf(@Nullable Transfer tr) {
        if (tr == null || !tr.applicable()) return Kit.State.NONE;
        return tr.status() == Transfer.Status.PARTIAL ? Kit.State.PARTIAL : Kit.State.FULL;
    }

    /** Состояние «+»: как перенос, но если не хватает только того, что сеть AE2 умеет сделать, то «можно заказать». */
    private Kit.State buttonState(RecipeView r, Transfer tr) {
        Kit.State state = stateOf(tr);
        if (state == Kit.State.FULL || !works.repka.ryscope.runtime.Ae2Terminal.active()) return state;
        boolean order = false;
        for (works.repka.ryscope.runtime.Ae2Terminal.State s : works.repka.ryscope.runtime.Supply.states(r.inputs())) {
            if (s == works.repka.ryscope.runtime.Ae2Terminal.State.MISSING) return state;
            order |= s == works.repka.ryscope.runtime.Ae2Terminal.State.CRAFTABLE;
        }
        return order ? Kit.State.ORDER : state;
    }

    private static String stateToken(Kit.State state) {
        return switch (state) {
            case FULL -> "state.ok";
            case PARTIAL -> "state.warn";
            case NONE -> "state.bad";
            case ORDER -> "state.order";
        };
    }

    private void renderButtons(GuiGraphics g, Theme t, Placed row, int mx, int my) {
        RecipeView r = row.recipe;
        int x = row.rowX + row.rowW - BUTTON;
        if (showsTransfer(r)) {
            int y = plusY(row);
            Transfer tr = transfers.get(r);
            if (transferEnabled(r)) {
                // заливка и рамка цветом состояния, значок состояния слева от кнопки
                Kit.State state = buttonState(r, tr);
                int color = t.c(stateToken(state));
                boolean hot = over(mx, my, x, y, BUTTON, BUTTON);
                Draw.roundBox(g, x, y, BUTTON, BUTTON, 3.5f, Draw.alpha(color, hot ? 0.32f : 0.2f), color);
                Icons.draw(g, Icons.Shape.PLUS, x + 3, y + 3, 8, color);
                Kit.indicator(g, t, x - 7f, y + (BUTTON - 4.5f) / 2f, 4.5f, state, -1, -1);
                hits.add(x, y, BUTTON, BUTTON, b -> {
                    if (b == 0 && host != null) doTransfer(host, r, hasShiftDown());
                });
            } else {
                smallBox(g, t, x, y, false, 0);
                Icons.draw(g, Icons.Shape.PLUS, x + 3, y + 3, 8, Draw.alpha(t.c("text.dim"), 0.6f));
            }
        }
        if (canGoal(r)) {
            int y = pinY(row);
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

    private enum Extra { MARK, DEFAULT }

    /** Дополнительные кнопки рецепта: закладка на сам рецепт и рецепт по умолчанию для предмета карточки. */
    private List<Extra> extras(RecipeView r) {
        List<Extra> out = new ArrayList<>(2);
        // закладка на страницу справки не нужна: страница и так всегда у предмета
        if (r.id() == null || nav.section == Section.HELP) return out;
        boolean itemOut = false;
        for (Stack s : r.outputs()) itemOut |= !s.item().isEmpty();
        if (itemOut) out.add(Extra.MARK);
        if (nav.focus != null && nav.section == Section.RECIPES && r.isCrafting()) out.add(Extra.DEFAULT);
        return out;
    }

    /**
     * Места под дополнительные кнопки: под «+» в той же колонке, затем колонкой левее, если рецепт её не задевает.
     * Кнопок, которым не нашлось места, нет: размеры карточки из макета не меняются.
     */
    private List<int[]> extraSpots(Placed row, int count) {
        List<int[]> out = new ArrayList<>(count);
        if (count == 0) return out;
        int x0 = row.rowX + row.rowW - BUTTON, bottom = row.rowY + row.rowH, step = BUTTON + BUTTON_GAP;
        int free = row.rowY;
        if (canGoal(row.recipe)) free = Math.max(free, pinY(row) + step);
        if (showsTransfer(row.recipe)) free = Math.max(free, plusY(row) + step);
        for (int y = free; out.size() < count && y + BUTTON <= bottom; y += step) out.add(new int[] {x0, y});
        int x1 = x0 - step, plate = row.recipe.needsPlate() ? 3 : 0;
        if (out.size() < count && row.x + row.recipe.width() + plate + 2 <= x1) {
            for (int y = row.rowY; out.size() < count && y + BUTTON <= bottom; y += step) {
                // слева от «+» стоит значок состояния переноса
                if (showsTransfer(row.recipe) && y < plusY(row) + BUTTON + 2 && y + BUTTON > plusY(row) - 2) continue;
                out.add(new int[] {x1, y});
            }
        }
        return out;
    }

    private boolean extraActive(Extra extra, RecipeView r) {
        return switch (extra) {
            case MARK -> {
                works.repka.ryscope.runtime.RecipeMark mark = Bookmarks.markOf(group().category(), r);
                yield mark != null && Bookmarks.contains(mark);
            }
            case DEFAULT -> nav.focus != null && works.repka.ryscope.runtime.RecipeDefaults.isDefault(nav.focus, group().category(), r);
        };
    }

    private void renderExtras(GuiGraphics g, Theme t, Placed row, int mx, int my) {
        List<Extra> extras = extras(row.recipe);
        List<int[]> spots = extraSpots(row, extras.size());
        for (int i = 0; i < spots.size(); i++) {
            int x = spots.get(i)[0], y = spots.get(i)[1];
            if (y < recipeTop || y + BUTTON > recipeBottom) continue;
            Extra extra = extras.get(i);
            boolean on = extraActive(extra, row.recipe), hot = over(mx, my, x, y, BUTTON, BUTTON);
            smallBox(g, t, x, y, hot, on ? Draw.alpha(t.c("accent"), 0.18f) : 0);
            Icons.draw(g, extra == Extra.MARK ? Icons.Shape.STAR : Icons.Shape.CHECK, x + 3, y + 3, 8, on || hot ? t.c("accent") : t.c("button.fg"));
            RecipeView r = row.recipe;
            Category category = group().category();
            hits.add(x, y, BUTTON, BUTTON, b -> {
                if (b != 0) return;
                if (extra == Extra.MARK) {
                    works.repka.ryscope.runtime.RecipeMark mark = Bookmarks.markOf(category, r);
                    if (mark != null) Overlay.toast(tr(Bookmarks.toggle(mark) ? "ryscope.recipe_mark.added" : "ryscope.recipe_mark.removed"));
                } else if (nav.focus != null) {
                    boolean set = works.repka.ryscope.runtime.RecipeDefaults.toggle(nav.focus, category, r);
                    Overlay.toast(tr(set ? "ryscope.recipe_default.set" : "ryscope.recipe_default.cleared", nav.focus.name().getString()));
                }
            });
        }
    }

    private void extraTooltip(GuiGraphics g, Theme t, Placed p, int mx, int my) {
        List<Extra> extras = extras(p.recipe);
        List<int[]> spots = extraSpots(p, extras.size());
        for (int i = 0; i < spots.size(); i++) {
            if (!over(mx, my, spots.get(i)[0], spots.get(i)[1], BUTTON, BUTTON)) continue;
            boolean on = extraActive(extras.get(i), p.recipe);
            String key = extras.get(i) == Extra.MARK ? (on ? "ryscope.recipe_mark.in" : "ryscope.recipe_mark.add")
                : (on ? "ryscope.recipe_default.is" : "ryscope.recipe_default.make");
            Kit.tooltip(g, t, List.of(tr(key)), mx, my);
            return;
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
                case ORDER -> "ryscope.status.state.order";
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
        Map<String, Integer> orderable = new LinkedHashMap<>();
        List<Ing> inputs = recipe.inputs();
        works.repka.ryscope.runtime.Ae2Terminal.State[] states = works.repka.ryscope.runtime.Supply.states(inputs);
        for (int i = 0; i < inputs.size(); i++) {
            Ing in = inputs.get(i);
            if (in.isEmpty()) continue;
            boolean items = false;
            for (Stack option : in.options) items |= option.key() instanceof net.minecraft.world.item.Item;
            if (!items) continue;
            total++;
            if (states[i] == works.repka.ryscope.runtime.Ae2Terminal.State.CRAFTABLE) {
                orderable.merge(in.options.get(0).name().getString(), 1, Integer::sum);
                continue;
            }
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
        if (missing.isEmpty() && orderable.isEmpty()) return new Status(tr("ryscope.status.all." + place), "", Kit.State.FULL);
        if (missing.isEmpty()) {
            // своего не хватает, но всё недостающее сеть AE2 умеет сделать
            return new Status(tr("ryscope.status.head", have, total), tr("ryscope.status.have." + place) + " · "
                + tr("ryscope.status.order", names(orderable)), Kit.State.ORDER);
        }
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

    /** «Имя ×2, Имя ×1 и ещё 3» для строки статуса. */
    private static String names(Map<String, Integer> counts) {
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (shown == 2) break;
            if (shown++ > 0) names.append(", ");
            names.append(e.getKey()).append(" ×").append(e.getValue());
        }
        if (counts.size() > 2) names.append(' ').append(tr("ryscope.status.more", counts.size() - 2));
        return names.toString();
    }

    private void renderTooltips(GuiGraphics g, Theme t, int mx, int my, @Nullable Category hoveredCategory) {
        if (Overlay.isOver(mx, my)) {
            TooltipDefer.drop();
            return;
        }
        Font font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        Stack hovered = recipeStackAt(mx, my);
        boolean byRecipe = false;
        if (hovered != null && !Lens.held() && popupStackAt(mx, my) == null) {
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
            List<String> lines = new java.util.ArrayList<>();
            lines.add(hoveredCategory.title.getString());
            if (hoveredCategory instanceof works.repka.ryscope.runtime.Links.LinkCategory) {
                // у сделок и наград мода семьи нет станции: только название
                Kit.tooltip(g, t, lines, mx, my);
                TooltipDefer.flush();
                g.pose().popPose();
                return;
            }
            works.repka.ryscope.runtime.Stations.State station = works.repka.ryscope.runtime.Stations.state(hoveredCategory);
            boolean manual = works.repka.ryscope.runtime.Stations.mark(hoveredCategory) != null;
            if (station == works.repka.ryscope.runtime.Stations.State.OWNED) lines.add(tr(manual ? "ryscope.stations.tip.owned_mark" : "ryscope.stations.tip.owned"));
            else if (station == works.repka.ryscope.runtime.Stations.State.MISSING) lines.add(tr(manual ? "ryscope.stations.tip.missing_mark" : "ryscope.stations.tip.missing"));
            lines.add(tr("ryscope.stations.tip.click"));
            Kit.tooltip(g, t, lines, mx, my);
        } else if (hintHover) {
            Kit.tooltip(g, t, List.of(tr("ryscope.recipes.hint").split(" · ")), mx, my);
        } else if (pendingTip != null) {
            Kit.tooltip(g, t, List.of(pendingTip.split("\n")), mx, my);
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
                for (Placed p : placed) extraTooltip(g, t, p, mx, my);
            }
        }
        TooltipDefer.flush();
        g.pose().popPose();
    }

    private void buttonTooltip(GuiGraphics g, Theme t, Font font, Placed p, int mx, int my) {
        int x = p.rowX + p.rowW - BUTTON, y = plusY(p);
        if (showsTransfer(p.recipe)) {
            Transfer tr = transfers.get(p.recipe);
            boolean overPlus = over(mx, my, x - 9, y - 2, BUTTON + 11, BUTTON + 4);
            works.repka.ryscope.runtime.Stages.Stage blocking = overPlus ? blockingStage(p.recipe) : null;
            if (blocking != null) {
                Kit.tooltip(g, t, List.of(tr("ryscope.stage.blocked", blocking.title().getString())), mx, my);
            } else if (overPlus && host == null) {
                Kit.tooltip(g, t, List.of(tr(p.recipe.transferUnavailable())), mx, my);
            } else if (overPlus && tr == null) {
                Kit.tooltip(g, t, List.of(tr("ryscope.transfer.checking")), mx, my);
            } else if (overPlus && tr.status() == Transfer.Status.UNSUPPORTED) {
                Kit.tooltip(g, t, List.of(tr(p.recipe.transferUnavailable())), mx, my);
            } else if (tr != null && overPlus) {
                if (!tr.lines().isEmpty()) {
                    g.renderComponentTooltip(font, tr.lines(), mx, my);
                } else {
                    String key = tr.applicable() ? "ryscope.transfer.hint" : tr.message();
                    Kit.tooltip(g, t, List.of(tr(key == null ? "ryscope.transfer.missing" : key)), mx, my);
                }
            }
        }
        y = pinY(p);
        if (canGoal(p.recipe) && over(mx, my, x, y, BUTTON, BUTTON)) {
            boolean set = works.repka.ryscope.runtime.Board.contains(p.recipe.outputs().get(0));
            Kit.tooltip(g, t, List.of(tr(set ? "ryscope.goals.in" : "ryscope.goals.add")), mx, my);
        }
    }

    @Nullable
    private Stack popupStackAt(double mx, double my) {
        for (StationSpot s : popupSpots) if (over(mx, my, s.x, s.y, s.size, s.size)) return s.stack;
        return null;
    }

    @Nullable
    private Stack recipeStackAt(int mx, int my) {
        Stack popup = popupStackAt(mx, my);
        if (popup != null) return popup;
        // значки строк списка квестов это картинки, и сквозь панельку предметы карточки не видны
        if (overMentions(mx, my)) return null;
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
        // пересчёт сразу, как изменились инвентарь или содержимое сети AE2, и раз в секунду на всякий случай
        // (у чужих окон состояние может зависеть от другого); statuses пуст сразу после смены страницы
        int inputs = works.repka.ryscope.runtime.InventoryView.version() * 31 + works.repka.ryscope.runtime.Ae2Terminal.networkVersion();
        boolean changed = inputs != transferInputs;
        transferInputs = inputs;
        if (transferAge++ % 20 != 0 && !changed && !statuses.isEmpty()) return;
        for (Placed p : placed) {
            try {
                statuses.put(p.recipe, statusOf(p.recipe));
            } catch (Throwable t) {
                statuses.remove(p.recipe);
            }
            if (host != null) {
                AbstractContainerScreen<?> acs = host;
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
        forward.push(nav);
        nav = history.pop();
        clearCaches();
        return true;
    }

    private boolean goForward() {
        if (forward.isEmpty()) return false;
        history.push(nav);
        nav = forward.pop();
        clearCaches();
        return true;
    }

    private void switchSection(Section section) {
        if (nav.focus == null || nav.section == section || nav.sections.get(section).isEmpty()) return;
        pushHistory(nav);
        forward.clear();
        nav = new Nav(nav.focus, section, nav.sections);
        remember(nav.focus, section == Section.USES ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
        rail.reset();
        clearCaches();
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (Overlay.mouseClicked(this, mx, my, button)) return true;
        if (mentionsClick(mx, my, button)) return true;
        if (stationsOpen && over(mx, my, stationsPopup[0], stationsPopup[1], stationsPopup[2], stationsPopup[3])) {
            Stack station = popupStackAt(mx, my);
            if (station != null && button == 2) toggleMark(station);
            else if (station != null) open(station, button == 1 ? Lookup.Mode.USES : Lookup.Mode.RECIPES);
            return true;
        }
        if (hits.click(mx, my, button)) return true;
        if (Overlay.cardCollapsed()) return true;
        for (Placed p : placed) {
            if (inRecipes(my) && p.recipe.clickBeforeStack(mx, my, button)) return true;
        }
        if (button == 0 && hasControlDown() && inRecipes(my)) {
            // Ctrl+клик по ячейке: все её варианты плашкой, по каждому работают клики и клавиши
            for (Placed p : placed) {
                if (!over(mx, my, p.x, p.y, p.recipe.width(), p.recipe.height())) continue;
                Ing ing;
                try {
                    ing = p.recipe.ingAt(p.x, p.y, (int) mx, (int) my);
                } catch (Throwable t) {
                    ing = null;
                }
                if (ing == null || ing.options.size() < 2) break;
                String tag = ing.tag();
                String title = tag != null ? tr("ryscope.ing.tag", "#" + tag, ing.options.size()) : tr("ryscope.ing.options", ing.options.size());
                openPopup(ing.options, (int) mx - 9, (int) my - 9, 18, 18, title);
                return true;
            }
        }
        Stack stack = recipeStackAt((int) mx, (int) my);
        if (stack != null) {
            if (button == 2) {
                toggleMark(stack);
            } else if (button == 0 && Overlay.cheating() && !stack.item().isEmpty()) {
                // режим читов, как в JEI: клик по предмету рецепта выдаёт его; курсора над карточкой не видно, поэтому в инвентарь
                Overlay.give(stack, hasShiftDown(), false);
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

    private static void toggleMark(Stack stack) {
        Bookmarks.toggle(stack);
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
        if (mentionsOpen >= 0 && over(mx, my, mentionsBox[0], mentionsBox[1], mentionsBox[2], mentionsBox[3])) {
            mentionsScroll.by(-(float) scrollY * MENTION_ROW);
            return true;
        }
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
        if (key == GLFW.GLFW_KEY_ESCAPE && mentionsOpen >= 0) {
            closeMentions();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (Keys.matches(Keys.BACK, key, scan)) {
            if (!goBack()) onClose();
            return true;
        }
        if (Keys.matches(Keys.FORWARD, key, scan)) {
            goForward();
            return true;
        }
        if (Keys.matches(Keys.NEXT_PAGE, key, scan)) {
            setPage(nav.page + 1);
            return true;
        }
        if (Keys.matches(Keys.PREV_PAGE, key, scan)) {
            setPage(nav.page - 1);
            return true;
        }
        if (Keys.matches(Keys.NEXT_CATEGORY, key, scan)) {
            setGroup(nav.group + 1);
            return true;
        }
        if (Keys.matches(Keys.PREV_CATEGORY, key, scan)) {
            setGroup(nav.group - 1);
            return true;
        }
        if (Keys.matches(Keys.SHOT, key, scan)) {
            double cx = Overlay.mouseX(), cy = Overlay.mouseY();
            for (Placed p : placed) {
                if (over(cx, cy, p.rowX, p.rowY, p.rowW, p.rowH)) {
                    RecipeShot.take(p.recipe, group().category());
                    return true;
                }
            }
        }
        if (Keys.matches(Keys.COPY_ID, key, scan)) {
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
        if (Keys.matches(Keys.NEXT_TAB, key, scan)) {
            cycleTab(hasShiftDown() ? -1 : 1);
            return true;
        }
        if (Keys.matches(Keys.CLOSE_TAB, key, scan)) {
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
            // как в JEI: PgUp/PgDn листают рецепты, с Shift переключают категорию
            case GLFW.GLFW_KEY_PAGE_UP -> {
                if (hasShiftDown()) setGroup(nav.group - 1);
                else setPage(nav.page - 1);
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                if (hasShiftDown()) setGroup(nav.group + 1);
                else setPage(nav.page + 1);
            }
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
