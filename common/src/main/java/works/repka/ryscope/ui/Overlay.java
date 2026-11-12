package works.repka.ryscope.ui;

import works.repka.ryscore.ui.Draw;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscope.client.Keys;
import works.repka.ryscope.core.search.Query;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.platform.Platform;
import works.repka.ryscope.runtime.Bookmarks;
import works.repka.ryscope.runtime.Config;
import works.repka.ryscope.runtime.Foreign;
import works.repka.ryscope.runtime.Give;
import works.repka.ryscope.runtime.Indexer;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.SearchService;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Field;
import works.repka.ryscore.ui.WindowLayout;
import works.repka.ryscore.ui.UiScale;
import works.repka.ryscore.ui.Type.Weight;

/**
 * Панели поверх экранов с инвентарём: список ресурсов с поиском справа, закладки слева.
 * Ничего не считает сам: список приходит из фонового поиска, здесь только раскладка, отрисовка и ввод.
 */
public final class Overlay {
    private static final int MARGIN = 4;
    private static final int PAD = 5;
    private static final int SEARCH_H = 20;
    private static final int SIDE_PAD = 9;
    private static final int SIDE_BOTTOM = 8;
    private static final int FILTER_H = 14;
    private static final int FILTER_ALL = 0, FILTER_CRAFTABLE = 1, FILTER_BOOKMARKS = 2;
    private static int filter = FILTER_ALL;
    private static int footerY, filterRowY, marksTop = -1;
    private static final int[][] filterRects = new int[3][2];
    private static final int[] showAllRect = new int[2];
    private static final int FOOTER_H = 10;

    /** Ширина свёрнутой панели или карточки: полоска с кнопкой раскрытия. */
    public static final int STRIP_W = 16;
    private static final int AUTO_COLUMNS_MIN = 8, AUTO_COLUMNS_MAX = 20;
    /** Ячеек в ряду у откреплённых закладок слева. */
    private static final int LEFT_COLUMNS = 8;
    /** Боковая панель свёрнута, пока открыта карточка: места на обоих не хватает. */
    private static boolean sidebarCollapsed;
    /** Игрок раскрыл панель вместо карточки. */
    private static boolean sidebarWanted;
    private static boolean cardCollapsed;
    private static final int[] stripRect = new int[4];

    private static final GridPanel LIST = new GridPanel();
    /** Через сколько мс ожидания поиска вместо списка показывается загрузка. */
    private static final long SEARCH_LOADING_MS = 200;
    private static final GridPanel MARKS = new GridPanel();

    static {
        MARKS.accentTiles = true;
    }
    /** Закладки сейчас стоят колонкой слева, а не полосой внизу панели. */
    private static boolean marksLeft;
    private static final int[] marksPanel = new int[4];
    /** Кнопка «открепить» или «закрепить» в шапке закладок: x, y, или x = -1, если её нет. */
    private static final int[] detachRect = {-1, 0};
    private static Field search;
    /** Последняя стрелка листала историю запросов: тогда и стрелка вниз идёт по истории, а не в сохранённые. */
    private static boolean browsingHistory;
    private static final int[] searchRect = new int[4];
    private static boolean draggingScroll;
    private static String toast;
    private static long toastUntil;
    private static String lastQuery = "";
    private static int dragIndex = -1;
    /** Нажатие на предмет: клик или начало перетаскивания, решится движением мыши. */
    @Nullable
    private static Stack pressStack;
    private static double pressX, pressY;
    /** Предмет тащат в призрачный слот чужого окна (фильтр) или в закладки. */
    @Nullable
    private static Stack ghostStack;
    private static final int GHOST_THRESHOLD = 4;
    private static boolean highlight;
    private static long lastSearchClick;
    private static SearchService.Results highlightFor;
    private static java.util.Set<Object> highlightKeys = java.util.Set.of();
    private static String acBase, acLast;
    private static int acStart, acIndex;
    private static java.util.List<String> tagCache;
    private static works.repka.ryscope.runtime.Snapshot tagCacheFor;
    private static final int[] gearRect = new int[4];
    /** Поле имени папки закладок, пока идёт переименование. */
    private static Field folderEdit;
    private static final int[] helpIcon = new int[2];
    private static String listCount = "";
    private static final int[] listInfo = new int[2];
    private static final int[] pageArrows = new int[3];
    private static long folderDeleteArmed;
    private static final List<String> savedShown = new ArrayList<>();
    private static int savedIndex = -1;

    private Overlay() {}

    public static boolean active(@Nullable Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.get().overlayEnabled || mc.level == null || mc.player == null) return false;
        return screen instanceof AbstractContainerScreen<?> || screen instanceof RecipeScreen;
    }

    public static boolean searchFocused() {
        return (search != null && search.isFocused()) || folderEdit != null;
    }

    public static void toast(String message) {
        toast(message, 1800);
    }

    public static void toast(String message, int millis) {
        toast = message;
        toastUntil = System.currentTimeMillis() + millis;
    }

    private static EditBox search() {
        if (search == null) {
            Minecraft mc = Minecraft.getInstance();
            search = new Field(100, 10);
            search.setMaxLength(256);
            search.setHint(Component.translatable("ryscope.search.hint"));
            search.setFormatter(Overlay::highlight);
            if (Config.get().keepSearch && !Config.get().lastSearch.isEmpty()) {
                search.setValue(Config.get().lastSearch);
                SearchService.submit(search.getValue());
            }
            search.setResponder(text -> {
                SearchService.submit(text);
                LIST.resetScroll();
                savedIndex = -1;
            });
        }
        return search;
    }

    /** Подсветка синтаксиса запроса: префиксы и операторы окрашены, как чипы. */
    private static FormattedCharSequence highlight(String visible, int offset) {
        Theme theme = ThemeManager.get();
        String full = search == null ? visible : search.getValue();
        int base = theme.c("input.fg") & 0xFFFFFF;
        if (full.isEmpty()) return FormattedCharSequence.forward(visible, Style.EMPTY.withColor(base));
        int[] colors = new int[full.length()];
        java.util.Arrays.fill(colors, base);
        for (Query.Term t : Query.parse(full).terms()) {
            int color = switch (t.kind) {
                case MOD -> theme.c("chip.mod.fg");
                case TAG -> theme.c("chip.tag.fg");
                case TOOLTIP, ID, OPERATOR -> theme.c("chip.fn.fg");
                default -> t.negated ? theme.c("state.bad") : theme.c("input.fg");
            } & 0xFFFFFF;
            for (int i = t.start; i < Math.min(t.end, colors.length); i++) colors[i] = color;
        }
        List<FormattedCharSequence> parts = new ArrayList<>();
        int i = 0;
        while (i < visible.length()) {
            int ci = Math.min(colors.length - 1, offset + i);
            int j = i + 1;
            while (j < visible.length() && colors[Math.min(colors.length - 1, offset + j)] == colors[ci]) j++;
            parts.add(FormattedCharSequence.forward(visible.substring(i, j), Style.EMPTY.withColor(colors[ci])));
            i = j;
        }
        return FormattedCharSequence.composite(parts);
    }

    /** Границы «хозяина» панелей: окно контейнера или карточка рецептов. left, top, width, height. */
    private static int[] hostBounds(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?> acs) return toUi(Platform.get().screenBounds(acs));
        if (screen instanceof RecipeScreen rs) return rs.cardBounds();
        return new int[] {0, 0, UiScale.width(), UiScale.height()};
    }

    /** Прямоугольник из единиц GUI игры в единицы интерфейса, с округлением наружу. */
    private static int[] toUi(int[] rect) {
        int left = (int) Math.floor(UiScale.fromGui(rect[0])), top = (int) Math.floor(UiScale.fromGui(rect[1]));
        int right = (int) Math.ceil(UiScale.fromGui(rect[0] + rect[2])), bottom = (int) Math.ceil(UiScale.fromGui(rect[1] + rect[3]));
        return new int[] {left, top, right - left, bottom - top};
    }

    private static void layout(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        Config cfg = Config.get();
        int sw = UiScale.width(), sh = UiScale.height();
        int[] b = hostBounds(screen);
        int left = b[0], right = b[0] + b[2];
        Foreign.Source foreign = Foreign.get();
        int leftTop = MARGIN, leftBottom = sh - MARGIN, rightTop = MARGIN, rightBottom = sh - MARGIN;
        int areaRight = right;
        List<int[]> attachedRight = new ArrayList<>();
        if (foreign != null && screen instanceof AbstractContainerScreen<?>) {
            try {
                for (int[] raw : foreign.exclusionAreas(screen)) {
                    // пустые и лежащие за краем экрана области не занимают места: терминал AE2 отдаёт
                    // скрытую панель с x = -491, и колонка слева из-за неё уезжала в середину
                    if (raw[2] <= 0 || raw[3] <= 0 || raw[0] + raw[2] <= 0 || raw[1] + raw[3] <= 0
                        || raw[0] >= screen.width || raw[1] >= screen.height) continue;
                    if (Dock.isFtbArea(screen, raw)) continue;
                    int[] area = toUi(raw);
                    boolean beside = area[1] < b[1] + b[3] && area[1] + area[3] > b[1];
                    if (beside && area[0] + area[2] > right && area[0] <= right + 12) {
                        // боковая вкладка, прилипшая к окну справа, расширяет занятую область (если панель ещё влезет)
                        areaRight = Math.max(areaRight, area[0] + area[2]);
                        attachedRight.add(area);
                    } else if (beside && area[0] < left && area[0] + area[2] >= left - 12) {
                        left = area[0];
                    } else if (area[0] + area[2] <= b[0]) {
                        // левая колонка (кнопки FTB, закладки) всегда прижата к верху экрана: чужие области
                        // слева её не сдвигают, иначе на части экранов AE2 она уезжала в середину
                    } else if (area[0] >= b[0] + b[2]) {
                        if (area[1] < sh / 2) rightTop = Math.max(rightTop, area[1] + area[3] + MARGIN);
                        else rightBottom = Math.min(rightBottom, area[1] - MARGIN);
                    }
                }
            } catch (Throwable ignored) {
                // чужой обработчик областей не должен мешать раскладке
            }
        }
        if (screen instanceof EffectRenderingInventoryScreen<?> && mc.player != null && !mc.player.getActiveEffects().isEmpty()) {
            right += (int) Math.ceil(UiScale.fromGui(34));
        }
        if (screen instanceof RecipeUpdateListener rul && rul.getRecipeBookComponent().isVisible()) {
            left = Math.min(left, b[0] - (int) Math.ceil(UiScale.fromGui(150)));
        }
        int cell = cfg.slotSize();
        LIST.paged = cfg.listPaged;
        // на большом экране панель показывает больше столбцов, а не растягивает пустоту
        int maxCols = cfg.maxColumns > 0 ? cfg.maxColumns : Math.max(AUTO_COLUMNS_MIN, Math.min(AUTO_COLUMNS_MAX, (sw / 3 - 16) / cell));

        // панель справа: размеры из макета, половина пикселя на единицу GUI
        boolean flush = ThemeManager.get().flushSidebar;
        int edge = flush ? 0 : MARGIN;
        // прилипшая справа картинка чужого окна (большой значок фильтра Create) не должна прятать панель:
        // если после неё не остаётся трёх столбцов, панель важнее, как у JEI
        if (areaRight > right && (sw - edge - (areaRight + MARGIN) - 16) / cell >= 3) {
            right = areaRight;
        } else {
            // рядом не влезает: панель обходит картинку сверху или снизу, как чужие кнопки справа
            for (int[] area : attachedRight) {
                if (area[1] + area[3] / 2 < sh / 2) rightTop = Math.max(rightTop, area[1] + area[3] + MARGIN);
                else rightBottom = Math.min(rightBottom, area[1] - MARGIN);
            }
        }
        // карточка рецептов сама встаёт левее панели, панель из-за неё не сжимается
        sidebarCollapsed = false;
        cardCollapsed = false;
        if (screen instanceof RecipeScreen rs) {
            right = Math.min(right, sw - edge - MARGIN - maxCols * cell - 16);
            Map<String, WindowLayout.Placement> placement = WindowLayout.arrange(sw - edge - MARGIN * 2, MARGIN,
                new WindowLayout.Window("card", rs.preferredWidth(), STRIP_W, sidebarWanted ? 1 : 2),
                new WindowLayout.Window("sidebar", maxCols * cell + 16, STRIP_W, sidebarWanted ? 2 : 1));
            sidebarCollapsed = placement.get("sidebar").collapsed();
            cardCollapsed = placement.get("card").collapsed();
            if (sidebarCollapsed) {
                stripRect[0] = sw - edge - STRIP_W;
                stripRect[1] = Math.max(MARGIN, rightTop);
                stripRect[2] = STRIP_W;
                stripRect[3] = rightBottom - stripRect[1];
            }
        }
        int avail = sw - edge - (right + MARGIN);
        int cols = Math.min(maxCols, (avail - 16) / cell);
        List<Stack> marks = Bookmarks.list();
        if (sidebarCollapsed || cols < 3 || rightBottom - rightTop < 80) {
            LIST.hide();
            MARKS.hide();
        } else {
            LIST.cell = cell;
            LIST.cols = cols;
            LIST.w = cols * cell + 16;
            LIST.x = sw - edge - LIST.w;
            LIST.y = flush && rightTop == MARGIN ? 0 : rightTop;
            LIST.h = (flush && rightBottom == sh - MARGIN ? sh : rightBottom) - LIST.y;
            int innerX = LIST.x + SIDE_PAD, top = LIST.y + SIDE_PAD;
            int bottom = LIST.y + LIST.h - SIDE_BOTTOM;
            boolean anyMarks = cfg.bookmarksPanel && !cfg.bookmarksDetached
                && (!marks.isEmpty() || Bookmarks.folders().size() > 1 || folderEdit != null);
            if (anyMarks) {
                int gridY = bottom - 21;
                int header = gridY - 4 - 7;
                marksTop = header - 6;
                MARKS.cell = cell;
                MARKS.cols = cols;
                MARKS.w = LIST.w - 8;
                MARKS.x = LIST.x + 4;
                MARKS.y = header - 2 - PAD;
                MARKS.h = bottom - MARKS.y;
                MARKS.gridX = innerX;
                MARKS.gridY = gridY;
                MARKS.gridW = cols * cell;
                MARKS.gridH = 23;
                MARKS.setItems(marks);
                bottom = marksTop - 6;
            } else {
                MARKS.hide();
                marksTop = -1;
            }
            footerY = bottom - 8;
            int filterY = cfg.searchBottom ? top : top + SEARCH_H + 6;
            filterRowY = filterY;
            int gridTop = filterY + FILTER_H + 6;
            searchRect[0] = innerX;
            searchRect[1] = cfg.searchBottom ? footerY - 6 - SEARCH_H : top;
            searchRect[2] = LIST.w - SIDE_PAD * 2;
            searchRect[3] = SEARCH_H;
            LIST.gridX = innerX;
            LIST.gridY = gridTop;
            LIST.gridW = cols * cell;
            LIST.gridH = (cfg.searchBottom ? searchRect[1] - 6 : footerY - 6) - gridTop + 2;
            EditBox box = search();
            box.setX(searchRect[0] + 18);
            box.setY(searchRect[1] + 5);
            box.setWidth(searchRect[2] - 44);
        }

        // панель кнопок FTB занимает верх левой колонки
        int availL = left - MARGIN - MARGIN;
        // левая колонка одной ширины: кнопки FTB и откреплённые закладки по восемь ячеек
        int leftW = Math.min(availL, LEFT_COLUMNS * cell + 16);
        int freeTop = Dock.layout(screen, MARGIN, leftW, leftTop, leftBottom);
        layoutLeftMarks(screen, cfg, cell, freeTop, leftBottom, leftW, marks);
    }

    /** Откреплённые закладки: колонка слева под кнопками FTB, на всю свободную высоту и ширину до окна. */
    private static void layoutLeftMarks(Screen screen, Config cfg, int cell, int top, int bottom, int width, List<Stack> marks) {
        marksLeft = false;
        if (!cfg.bookmarksDetached) return;
        if (filter == FILTER_BOOKMARKS) setFilter(FILTER_ALL);
        // ряд стикеров целей внизу экрана остаётся свободным
        if (screen instanceof AbstractContainerScreen<?> && Stickers.rowHeight() > 0) {
            bottom = Math.min(bottom, UiScale.height() - 14 - Stickers.rowHeight() - MARGIN);
        }
        int cols = (width - 16) / cell;
        if (cols < 2 || bottom - top < 60) {
            MARKS.hide();
            marksTop = -1;
            return;
        }
        // высота по содержимому: колонка растёт с закладками до свободного края, дальше прокрутка
        int rows = marks.isEmpty() ? 2 : (marks.size() + cols - 1) / cols;
        int px = MARGIN, pw = cols * cell + 16, py = top;
        int ph = Math.min(bottom - top, 4 + 18 + rows * cell + SIDE_BOTTOM);
        marksPanel[0] = px;
        marksPanel[1] = py;
        marksPanel[2] = pw;
        marksPanel[3] = ph;
        MARKS.cell = cell;
        MARKS.cols = cols;
        MARKS.x = px + 4;
        MARKS.w = pw - 8;
        MARKS.y = py + 4;
        MARKS.h = ph - 4;
        marksTop = MARKS.y + 1;
        MARKS.gridX = px + SIDE_PAD;
        MARKS.gridY = MARKS.y + 18;
        MARKS.gridW = cols * cell;
        MARKS.gridH = py + ph - SIDE_BOTTOM - MARKS.gridY;
        MARKS.setItems(marks);
        marksLeft = true;
    }

    /** Отметки на слотах контейнера: они в координатах самого экрана, поэтому вне масштаба интерфейса. */
    public static void renderContainerMarks(Screen screen, GuiGraphics g) {
        if (active(screen) && screen instanceof AbstractContainerScreen<?> acs) renderHighlight(acs, g);
    }

    /** Панели и их содержимое. Рисуется до слотов экрана, чтобы подсказки игры оставались сверху. */
    public static void renderBackground(Screen screen, GuiGraphics g, int mx, int my, float partialTick) {
        if (!active(screen)) return;
        long frameStart = System.nanoTime();
        try {
            renderPanels(screen, g, mx, my, partialTick);
        } finally {
            frameNanos += System.nanoTime() - frameStart;
            frames++;
        }
    }

    /** Среднее время отрисовки панелей за кадр в микросекундах: для экрана отладки и автотеста. */
    public static long averageFrameMicros() {
        return frames == 0 ? 0 : frameNanos / frames / 1000;
    }

    private static long frameNanos;
    private static long frames;

    private static void renderPanels(Screen screen, GuiGraphics g, int mx, int my, float partialTick) {
        layout(screen);
        Theme theme = ThemeManager.get();
        boolean animate = Config.get().animations && !theme.reducedMotion;
        if (LIST.visible()) renderSidebar(g, theme, mx, my, partialTick, animate);
        if (sidebarCollapsed) {
            theme.panel(g, stripRect[0], stripRect[1], stripRect[2], stripRect[3]);
            boolean hot = mx >= stripRect[0] && mx < stripRect[0] + STRIP_W && my >= stripRect[1] && my < stripRect[1] + stripRect[3];
            works.repka.ryscore.ui.Icons.draw(g, works.repka.ryscore.ui.Icons.Shape.CHEVRON_LEFT, stripRect[0] + (STRIP_W - 8) / 2f, stripRect[1] + (stripRect[3] - 8) / 2f, 8f, hot ? theme.c("accent") : theme.c("button.fg"));
        }
        Dock.render(g, theme, mx, my);
        if (marksLeft && MARKS.visible()) {
            theme.sidebar(g, marksPanel[0], marksPanel[1], marksPanel[2], marksPanel[3]);
            renderMarks(g, theme, mx, my, animate);
        }
        if (screen instanceof AbstractContainerScreen<?>) {
            Stickers.drawRow(g, theme, 4, UiScale.height() - 14, LIST.visible() ? LIST.x - 6 : UiScale.width() - 4, hostBounds(screen));
        } else {
            Stickers.forget();
        }
    }

    private static void renderSidebar(GuiGraphics g, Theme t, int mx, int my, float partialTick, boolean animate) {
        SearchService.updateCraftable();
        SearchService.Results results = SearchService.results();
        LIST.setItems(filter == FILTER_BOOKMARKS ? Bookmarks.list() : results.stacks);
        t.sidebar(g, LIST.x, LIST.y, LIST.w, LIST.h);

        // поиск
        Field box = (Field) search();
        boolean active = box.isFocused() || highlight;
        if (t.beveled()) t.input(g, searchRect[0], searchRect[1], searchRect[2], searchRect[3], active);
        else Draw.roundBox(g, searchRect[0], searchRect[1], searchRect[2], searchRect[3], 5f, t.c("input.bg"), active ? t.c("accent") : t.c("input.border"));
        Draw.magnifier(g, searchRect[0] + 6, searchRect[1] + 6, t.c("text.muted"));
        applySpans(box, t);
        box.setTextColor(t.c("input.fg") & 0xFFFFFF);
        box.render(g, mx, my, partialTick);
        int fieldRight = searchRect[0] + searchRect[2] - 6;
        float cy = searchRect[1] + SEARCH_H / 2f;
        if (box.getValue().isEmpty()) {
            String key = Keys.label(Keys.SEARCH).replace(" + ", "+");
            float keyW = Draw.labelWidth(key, 11, Weight.REGULAR);
            if (keyW < searchRect[2] / 3f) Draw.label(g, key, fieldRight - keyW, cy, 11, Weight.REGULAR, t.c("text.dim"));
        } else {
            int cx = searchRect[0] + searchRect[2] - 11, ccy = searchRect[1] + 6;
            boolean hot = mx >= cx - 2 && mx < cx + 9 && my >= ccy - 2 && my < ccy + 9;
            Draw.cross(g, cx, ccy, hot ? t.c("accent") : t.c("text.dim"));
        }

        renderFilters(g, t, mx, my);
        // быстрый ответ показывается сразу; долгий — загрузкой вместо прежнего списка, чтобы было видно, что поиск идёт
        boolean searching = SearchService.busyMillis() > SEARCH_LOADING_MS && Indexer.state() != Indexer.State.BUILDING;
        if (searching) {
            works.repka.ryscore.ui.Loading.render(g, t, LIST.gridX, LIST.gridY, LIST.gridW, LIST.gridH, "ryscope.search.loading", SearchService.examined());
        } else {
            LIST.render(g, t, mx, my, animate);
        }

        // подвал: счётчик и страницы
        float fy = footerY + 4f;
        int right = LIST.x + LIST.w - SIDE_PAD;
        if (searching) {
            // счётчик прежнего запроса сбивал бы с толку
            listCount = "";
        } else if (Indexer.state() == Indexer.State.BUILDING) {
            int bw = LIST.w - SIDE_PAD * 2;
            Draw.round(g, LIST.x + SIDE_PAD, footerY + 7, bw, 2, 1f, t.c("slot.bg"));
            Draw.round(g, LIST.x + SIDE_PAD, footerY + 7, Math.max(2, bw * Indexer.progress()), 2, 1f, t.c("accent"));
            Draw.label(g, Component.translatable(Indexer.step()).getString(), LIST.x + SIDE_PAD, footerY + 2, 11, Weight.REGULAR, t.c("text.muted"));
        } else {
            int n = LIST.items().size();
            String number = String.format("%,d", n).replace(',', ' ');
            String count = plural("ryscope.count.items", n, number);
            if (results.exactCount < results.stacks.size() && !results.query.isEmpty()) count = Component.translatable("ryscope.search.fuzzy", count).getString();
            listCount = count;
            boolean many = LIST.paged && LIST.pages() > 1;
            String footer = many ? count + " · " + Component.translatable("ryscope.list.page", LIST.page() + 1, LIST.pages()).getString() : count;
            int textEnd = right;
            if (many) {
                pageArrows[1] = right - 3;
                pageArrows[0] = pageArrows[1] - 8;
                pageArrows[2] = footerY;
                textEnd = pageArrows[0] - 4;
                boolean prevHot = mx >= pageArrows[0] - 2 && mx < pageArrows[0] + 6 && my >= footerY - 2 && my < footerY + 12;
                boolean nextHot = mx >= pageArrows[1] - 2 && mx < pageArrows[1] + 6 && my >= footerY - 2 && my < footerY + 12;
                works.repka.ryscore.ui.Icons.draw(g, works.repka.ryscore.ui.Icons.Shape.CHEVRON_LEFT, pageArrows[0], footerY + 1, 6f, prevHot ? t.c("accent") : t.c("button.fg"));
                works.repka.ryscore.ui.Icons.draw(g, works.repka.ryscore.ui.Icons.Shape.CHEVRON_RIGHT, pageArrows[1], footerY + 1, 6f, nextHot ? t.c("accent") : t.c("button.fg"));
            }
            Draw.label(g, Draw.ellipsize(footer, textEnd - LIST.x - SIDE_PAD, 12, Weight.REGULAR), LIST.x + SIDE_PAD, fy, 12, Weight.REGULAR, t.c("text.muted"));
        }

        if (MARKS.visible() && !marksLeft) renderMarks(g, t, mx, my, animate);
    }

    private static String plural(String key, int n, String shown) {
        int a = Math.abs(n) % 100, b = a % 10;
        String form = a >= 11 && a <= 14 ? "many" : b == 1 ? "one" : b >= 2 && b <= 4 ? "few" : "many";
        return Component.translatable(key + "." + form, shown).getString();
    }

    private static void renderFilters(GuiGraphics g, Theme t, int mx, int my) {
        Config cfg = Config.get();
        float cy = filterRowY + FILTER_H / 2f;
        int right = LIST.x + LIST.w - SIDE_PAD;
        // настройки и подсказки в правом конце ряда
        gearRect[0] = right - 12;
        gearRect[1] = filterRowY + 2;
        gearRect[2] = 10;
        gearRect[3] = 10;
        boolean gearHot = mx >= gearRect[0] - 2 && mx < gearRect[0] + 12 && my >= gearRect[1] - 2 && my < gearRect[1] + 12;
        works.repka.ryscore.ui.Icons.draw(g, works.repka.ryscore.ui.Icons.Shape.GEAR, gearRect[0], filterRowY + 3, 8f, gearHot ? t.c("accent") : t.c("text.muted"));
        listInfo[0] = gearRect[0] - 14;
        listInfo[1] = filterRowY + 2;
        helpIcon[0] = listInfo[0] - 12;
        helpIcon[1] = filterRowY + 2;
        Draw.info(g, listInfo[0], listInfo[1], overInfo(listInfo, mx, my) ? t.c("accent") : t.c("text.dim"));
        Draw.question(g, helpIcon[0], helpIcon[1], overInfo(helpIcon, mx, my) ? t.c("accent") : t.c("text.dim"));
        int limit = helpIcon[0] - 4;
        String[] keys = {"ryscope.filter.all", "ryscope.filter.craftable", "ryscope.filter.bookmarks"};
        String[] counts = {"", Integer.toString(SearchService.craftable()), Integer.toString(Bookmarks.list().size())};
        // откреплённые закладки живут в своей колонке слева, вкладка им не нужна
        int tabs = cfg.bookmarksDetached ? 2 : 3;
        filterRects[FILTER_BOOKMARKS][1] = 0;
        int x = LIST.x + SIDE_PAD;
        boolean withCounts = true;
        for (int pass = 0; pass < 2; pass++) {
            float total = 0;
            for (int i = 0; i < tabs; i++) total += tabWidth(Component.translatable(keys[i]).getString(), withCounts ? counts[i] : "", i) + (i > 0 ? 3 : 0);
            if (x + total <= limit) break;
            withCounts = false;
        }
        for (int i = 0; i < tabs; i++) {
            String label = Component.translatable(keys[i]).getString();
            String count = withCounts ? counts[i] : "";
            int w = (int) Math.ceil(tabWidth(label, count, i));
            filterRects[i][0] = x;
            filterRects[i][1] = w;
            if (x + w > limit) {
                filterRects[i][1] = 0;
                continue;
            }
            boolean active = filter == i;
            boolean hot = mx >= x && mx < x + w && my >= filterRowY && my < filterRowY + FILTER_H;
            if (active) Draw.round(g, x, filterRowY, w, FILTER_H, 4f, t.c("filter.active.bg"));
            else if (hot) Draw.round(g, x, filterRowY, w, FILTER_H, 4f, Draw.alpha(t.c("filter.active.bg"), 0.5f));
            Weight weight = active ? Weight.MEDIUM : Weight.REGULAR;
            Draw.label(g, label, x + 6, cy, 12.5f, weight, active ? t.c("text.primary") : t.c("text.muted"));
            if (!count.isEmpty()) {
                float tw = Draw.labelWidth(label + " ", 12.5f, weight);
                Draw.label(g, count, x + 6 + tw, cy, 12.5f, Weight.REGULAR, i == FILTER_CRAFTABLE ? t.c("state.ok") : t.c("text.muted"));
            }
            x += w + 3;
        }
        if (!cfg.overlayEnabled) return;
    }

    private static float tabWidth(String label, String count, int index) {
        Weight weight = filter == index ? Weight.MEDIUM : Weight.REGULAR;
        float w = Draw.labelWidth(label, 12.5f, weight) + 12;
        if (!count.isEmpty()) w += Draw.labelWidth(" " + count, 12.5f, Weight.REGULAR);
        return w;
    }

    private static void setFilter(int index) {
        filter = index;
        SearchService.setFilter(index == FILTER_CRAFTABLE ? "craftable" : "");
        LIST.resetScroll();
    }

    private static void renderMarks(GuiGraphics g, Theme t, int mx, int my, boolean animate) {
        int left = MARKS.x + PAD, right = MARKS.x + MARKS.w - PAD;
        if (!marksLeft) Draw.round(g, left - 1, marksTop, right - left + 2, 0.5f, 0.02f, t.c("panel.border"));
        float cy = marksTop + 6 + 4;
        // открепить или закрепить: булавка в правом конце шапки, у откреплённых она наклонена
        detachRect[0] = right - 10;
        detachRect[1] = (int) cy - 5;
        boolean detachHot = overInfo(detachRect, mx, my);
        int pinColor = detachHot ? t.c("accent") : t.c("text.dim");
        if (marksLeft) works.repka.ryscore.ui.Icons.drawRotated(g, works.repka.ryscore.ui.Icons.Shape.PIN, detachRect[0] + 1, detachRect[1] + 1, 8f, 45f, pinColor);
        else works.repka.ryscore.ui.Icons.draw(g, works.repka.ryscore.ui.Icons.Shape.PIN, detachRect[0] + 1, detachRect[1] + 1, 8f, pinColor);
        showAllRect[1] = 0;
        if (Bookmarks.folders().size() > 1 || folderEdit != null) {
            renderFolderTabs(g, t, mx, my);
        } else {
            Draw.label(g, Component.translatable("ryscope.bookmarks").getString(), left, cy, 12, Weight.REGULAR, t.c("text.muted"));
            if (!marksLeft) {
                String all = Component.translatable("ryscope.bookmarks.show_all").getString();
                float allW = Draw.labelWidth(all, 12, Weight.REGULAR);
                float ax = detachRect[0] - 4 - allW;
                showAllRect[0] = (int) ax;
                showAllRect[1] = (int) Math.ceil(allW);
                boolean hot = mx >= ax && mx < ax + allW && my >= cy - 5 && my < cy + 5;
                Draw.label(g, all, ax, cy, 12, Weight.REGULAR, hot ? t.c("accent") : t.c("text.muted"));
            }
        }
        MARKS.render(g, t, mx, my, animate);
        if (marksLeft && MARKS.items().isEmpty()) {
            String hint = Component.translatable("ryscope.bookmarks.empty", Keys.label(Keys.BOOKMARK)).getString();
            List<String> lines = Draw.wrapLabel(hint, MARKS.gridW, 11, Weight.REGULAR, 3);
            for (int i = 0; i < lines.size(); i++) {
                Draw.label(g, lines.get(i), MARKS.gridX, MARKS.gridY + 6 + i * 11, 11, Weight.REGULAR, t.c("text.dim"));
            }
        }
    }

    private static boolean overDetach(double mx, double my) {
        return MARKS.visible() && detachRect[0] >= 0 && overInfo(detachRect, mx, my);
    }

    private static void toggleDetached() {
        commitFolderName();
        Config cfg = Config.get();
        cfg.bookmarksDetached = !cfg.bookmarksDetached;
        Config.save();
        if (cfg.bookmarksDetached && filter == FILTER_BOOKMARKS) setFilter(FILTER_ALL);
        MARKS.resetScroll();
        detachRect[0] = -1;
    }

    /** Чипы и цвета запроса в поле поиска. */
    private static void applySpans(Field box, Theme t) {
        String value = box.getValue();
        List<Field.Span> spans = new ArrayList<>();
        int[] colors = new int[value.length()];
        if (!value.isEmpty()) {
            for (Query.Term term : Query.parse(value).terms()) {
                String kind = switch (term.kind) {
                    case MOD -> "mod";
                    case TAG -> "tag";
                    case TOOLTIP, ID, OPERATOR -> "fn";
                    default -> null;
                };
                int end = Math.min(term.end, value.length());
                if (kind != null && term.start < end) spans.add(new Field.Span(term.start, end, kind));
                int color = kind != null ? t.c("chip." + kind + ".fg") : term.negated ? t.c("state.bad") : 0;
                for (int i = term.start; i < end; i++) colors[i] = color;
            }
        }
        box.setSpans(spans, colors);
    }

    private static boolean overInfo(int[] rect, double mx, double my) {
        return mx >= rect[0] - 1 && mx < rect[0] + 11 && my >= rect[1] - 1 && my < rect[1] + 11;
    }

    /** Строки подсказки: в файлах перевода разделены точкой посередине. */
    private static List<Component> infoLines(String text) {
        List<Component> lines = new ArrayList<>();
        for (String part : text.split(" · ")) lines.add(Component.literal(part));
        return lines;
    }

    private static void renderInfoTooltips(Screen screen, GuiGraphics g, int mx, int my) {
        Font font = Minecraft.getInstance().font;
        String text = null;
        if (overDetach(mx, my)) {
            text = Component.translatable(marksLeft ? "ryscope.bookmarks.attach" : "ryscope.bookmarks.detach").getString();
        }
        if (LIST.visible() && overInfo(listInfo, mx, my)) {
            text = (LIST.paged ? Component.translatable("ryscope.list.count", listCount).getString() + " · " : "")
                + Component.translatable("ryscope.list.keys", Keys.label(Keys.RECIPES), Keys.label(Keys.USES),
                    Keys.label(Keys.BOOKMARK), Keys.label(Keys.TREE)).getString();
        }
        if (text == null) return;
        List<String> tip = new ArrayList<>();
        for (Component line : infoLines(text)) tip.add(line.getString());
        works.repka.ryscore.ui.Kit.tooltip(g, ThemeManager.get(), tip, mx, my);
    }

    /** Подсказки, линза и уведомления: поверх всего экрана. */
    public static void renderTop(Screen screen, GuiGraphics g, int mx, int my, float partialTick) {
        if (!active(screen)) return;
        Theme theme = ThemeManager.get();
        Stack own = ownStackAt(mx, my);
        if (Lens.held()) {
            Stack target = own != null ? own : hovered(screen, mx, my);
            if (target != null) Lens.renderCard(g, theme, target, mx, my, UiScale.width(), UiScale.height());
        } else if (own != null && LIST.at(mx, my) != null && cheating() && carriedToDelete(own)) {
            works.repka.ryscore.ui.Kit.tooltip(g, theme, List.of(Component.translatable("ryscope.cheat.delete").getString()), mx, my);
        } else if (own != null && savedRowAt(mx, my) < 0) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            own.renderTooltip(g, mx, my);
            g.pose().popPose();
        }
        Dock.renderTooltip(g, mx, my);
        renderGhost(g, theme, mx, my);
        renderInfoTooltips(screen, g, mx, my);
        renderHelp(g, theme);
        renderSaved(g, theme, mx, my);
        renderToast(screen, g);
    }

    /** На курсоре предмет, который клик по этой ячейке списка уберёт. */
    private static boolean carriedToDelete(Stack under) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>)) return false;
        ItemStack carried = player.containerMenu.getCarried();
        return !carried.isEmpty() && !ItemStack.isSameItemSameComponents(under.item(), carried) && Give.serverSupports.getAsBoolean();
    }

    /** Перетаскиваемый предмет у курсора и подсветка фильтров, которые его примут. */
    private static void renderGhost(GuiGraphics g, Theme theme, int mx, int my) {
        if (ghostStack == null) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 450);
        works.repka.ryscope.runtime.Foreign.Source foreign = works.repka.ryscope.runtime.Foreign.get();
        if (foreign != null) {
            for (int[] a : foreign.ghostAreas()) {
                float x = (float) UiScale.fromGui(a[0]), y = (float) UiScale.fromGui(a[1]);
                float w = (float) UiScale.fromGui(a[2]), h = (float) UiScale.fromGui(a[3]);
                boolean hot = mx >= x && mx < x + w && my >= y && my < y + h;
                Draw.roundBox(g, x, y, w, h, 2f, Draw.alpha(theme.c("accent"), hot ? 0.45f : 0.22f), theme.c("accent"));
            }
        }
        if (MARKS.visible() && MARKS.contains(mx, my)) {
            Draw.roundBorder(g, MARKS.x, MARKS.y, MARKS.w, MARKS.h, 6f, theme.c("accent"));
        }
        g.pose().translate(0, 0, 50);
        ghostStack.render(g, mx - 8, my - 8);
        g.pose().popPose();
    }

    /** Шпаргалка по языку поиска: при наведении на значок «?» внизу списка. */
    private static void renderHelp(GuiGraphics g, Theme theme) {
        if (!LIST.visible() || !overInfo(helpIcon, Overlay.mouseX(), Overlay.mouseY())) return;
        Font font = Minecraft.getInstance().font;
        List<String[]> lines = new ArrayList<>();
        int keyW = 0, textW = 0;
        for (int i = 1; i <= 10; i++) {
            String raw = Component.translatable("ryscope.help." + i).getString();
            int split = raw.indexOf(" — ");
            String[] parts = split < 0 ? new String[] {raw, ""} : new String[] {raw.substring(0, split), raw.substring(split + 3)};
            lines.add(parts);
            keyW = Math.max(keyW, Math.round(Draw.widthScaled(parts[0], 0.75f)));
            textW = Math.max(textW, Math.round(Draw.widthScaled(parts[1], 0.75f)));
        }
        int w = keyW + textW + 20, h = lines.size() * 9 + 10;
        int x = Math.max(4, LIST.x - w - 4), y = LIST.y;
        g.pose().pushPose();
        g.pose().translate(0, 0, 380);
        theme.tooltipBox(g, x, y, w, h);
        int ly = y + 6;
        for (String[] parts : lines) {
            Draw.textScaled(g, parts[0], x + 6, ly, 0.75f, theme.c("accent"));
            Draw.textScaled(g, parts[1], x + 12 + keyW, ly, 0.75f, theme.c("text.body"));
            ly += 9;
        }
        g.pose().popPose();
    }

    /** Короткое уведомление внизу экрана. Работает на любом экране мода. */
    public static void renderToast(Screen screen, GuiGraphics g) {
        if (toast == null || System.currentTimeMillis() >= toastUntil) return;
        Theme theme = ThemeManager.get();
        Font font = Minecraft.getInstance().font;
        int maxText = Math.max(80, UiScale.width() - 40);
        List<String> lines = Draw.wrap(toast, maxText);
        if (lines.size() > 3) lines = lines.subList(0, 3);
        int textW = 0;
        for (String line : lines) textW = Math.max(textW, Draw.width(line));
        int w = textW + 16, h = 6 + lines.size() * 10;
        int x = (UiScale.width() - w) / 2, y = UiScale.height() - 18 - h;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        theme.tooltipBox(g, x, y, w, h);
        for (int i = 0; i < lines.size(); i++) {
            Draw.text(g, lines.get(i), x + 8, y + 4 + i * 10, theme.c("text.primary"));
        }
        g.pose().popPose();
    }

    /** Затемняет в открытом контейнере слоты, не подходящие под запрос: быстро найти нужное в сундуке. */
    private static void renderHighlight(AbstractContainerScreen<?> screen, GuiGraphics g) {
        SearchService.Results results = SearchService.results();
        if (!highlight || results.query.isEmpty()) return;
        if (highlightFor != results) {
            java.util.Set<Object> keys = new java.util.HashSet<>();
            for (Stack s : results.stacks) keys.add(s.key());
            highlightKeys = keys;
            highlightFor = results;
        }
        int[] b = Platform.get().screenBounds(screen);
        Theme theme = ThemeManager.get();
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.isActive()) continue;
            int x = b[0] + slot.x, y = b[1] + slot.y;
            if (slot.hasItem() && highlightKeys.contains(slot.getItem().getItem())) {
                Draw.rect(g, x - 1, y - 1, 18, 1, theme.c("accent"));
                Draw.rect(g, x - 1, y + 16, 18, 1, theme.c("accent"));
                Draw.rect(g, x - 1, y, 1, 16, theme.c("accent"));
                Draw.rect(g, x + 16, y, 1, 16, theme.c("accent"));
            } else {
                Draw.rect(g, x, y, 16, 16, 0xA0000000);
            }
        }
        g.pose().popPose();
    }

    /** Tab в поле поиска: дополняет @мод и #тег, повторный Tab перебирает варианты. */
    private static void autocomplete(EditBox box) {
        String v = box.getValue();
        int cur = box.getCursorPosition();
        boolean repeat = acLast != null && acLast.equals(v);
        int start = cur;
        if (repeat) {
            start = acStart;
        } else {
            while (start > 0 && !Character.isWhitespace(v.charAt(start - 1)) && v.charAt(start - 1) != '|') start--;
        }
        if (start >= cur) return;
        String token = v.substring(start, cur);
        String neg = token.startsWith("-") ? "-" : "";
        String rest = token.substring(neg.length());
        if (rest.isEmpty()) return;
        char kind = rest.charAt(0);
        if (kind != '@' && kind != '#') return;
        String typed = repeat ? acBase : rest.substring(1).toLowerCase(java.util.Locale.ROOT);
        if (!repeat) acIndex = 0;
        List<String> pool;
        if (kind == '@') {
            pool = new ArrayList<>(Platform.get().modIds());
            java.util.Collections.sort(pool);
        } else {
            works.repka.ryscope.runtime.Snapshot snap = Indexer.snapshot();
            if (tagCacheFor != snap) {
                java.util.TreeSet<String> set = new java.util.TreeSet<>();
                for (int i = 0; i < snap.index.size(); i++) java.util.Collections.addAll(set, snap.index.entry(i).tags);
                tagCache = new ArrayList<>(set);
                tagCacheFor = snap;
            }
            pool = tagCache;
        }
        List<String> candidates = new ArrayList<>();
        for (String c : pool) if (c.startsWith(typed) || c.substring(c.indexOf(':') + 1).startsWith(typed)) candidates.add(c);
        if (candidates.isEmpty()) for (String c : pool) if (c.contains(typed)) candidates.add(c);
        if (candidates.isEmpty()) return;
        String chosen = candidates.get(acIndex++ % candidates.size());
        String value = v.substring(0, start) + neg + kind + chosen + v.substring(cur);
        box.setValue(value);
        int pos = start + neg.length() + 1 + chosen.length();
        box.setCursorPosition(pos);
        box.setHighlightPos(pos);
        acBase = typed;
        acStart = start;
        acLast = value;
    }


    // --- папки закладок: вкладки, как в браузере ---

    private static final int FOLDER_TAB_H = 12;
    private static final int FOLDER_TAB_MIN = 20;
    private static final int FOLDER_TAB_MAX = 64;
    /** Видимые вкладки: номер папки, левый край и ширина. */
    private static final List<int[]> FOLDER_TABS = new ArrayList<>();

    private static boolean overMarksHeader(double mx, double my) {
        return MARKS.visible() && mx >= MARKS.x && mx < MARKS.x + MARKS.w && my >= MARKS.y && my < MARKS.gridY;
    }

    /** Плюс новой папки стоит левее булавки «открепить». */
    private static int folderPlusX() {
        return MARKS.x + MARKS.w - PAD - 10 - 13;
    }

    private static int folderNatural(int index) {
        int w = Math.round(Draw.widthScaled(folderLabel(index), 0.75f)) + 10;
        return Math.max(FOLDER_TAB_MIN, Math.min(FOLDER_TAB_MAX, w));
    }

    private static void layoutFolderTabs() {
        FOLDER_TABS.clear();
        int n = Bookmarks.folders().size();
        int left = MARKS.x + PAD;
        int area = folderPlusX() - 2 - left;
        int active = Bookmarks.active();
        int total = 0;
        for (int i = 0; i < n; i++) total += folderNatural(i);
        if (total <= area) {
            int x = left;
            for (int i = 0; i < n; i++) {
                int w = folderNatural(i);
                FOLDER_TABS.add(new int[] {i, x, w});
                x += w;
            }
            return;
        }
        // не помещаются: открытая остаётся читаемой, остальные сжимаются, лишние уходят за край и листаются колесом
        int activeW = Math.min(folderNatural(active), area);
        int others = Math.max(0, n - 1);
        int rest = others == 0 ? 0 : Math.max(FOLDER_TAB_MIN, (area - activeW) / others);
        int fit = rest == 0 ? 0 : Math.min(others, (area - activeW) / rest);
        int before = Math.min(active, fit / 2);
        int after = Math.min(n - 1 - active, fit - before);
        before = Math.min(active, fit - after);
        int x = left;
        for (int i = active - before; i <= active + after; i++) {
            int w = i == active ? activeW : rest;
            FOLDER_TABS.add(new int[] {i, x, w});
            x += w;
        }
    }

    private static int folderTabAt(double mx, double my) {
        if (!overMarksHeader(mx, my) || my < MARKS.y + PAD || my >= MARKS.y + PAD + FOLDER_TAB_H) return -1;
        for (int[] tab : FOLDER_TABS) if (mx >= tab[1] && mx < tab[1] + tab[2]) return tab[0];
        return -1;
    }

    private static String folderLabel(int index) {
        List<Bookmarks.Folder> folders = Bookmarks.folders();
        String name = folders.get(index).name;
        if (!name.isEmpty()) return name;
        return folders.size() == 1 ? Component.translatable("ryscope.bookmarks").getString() : Integer.toString(index + 1);
    }

    private static void renderFolderTabs(GuiGraphics g, Theme theme, int mx, int my) {
        layoutFolderTabs();
        int top = MARKS.y + PAD;
        int active = Bookmarks.active();
        int hover = folderTabAt(mx, my);
        for (int[] tab : FOLDER_TABS) {
            int index = tab[0];
            int tx = tab[1], tw = tab[2] - 1;
            boolean current = index == active;
            theme.tab(g, tx, top, tw, FOLDER_TAB_H, current, index == hover);
            if (current && folderEdit != null) {
                folderEdit.setX(tx + 3);
                folderEdit.setY(top + 2);
                folderEdit.setWidth(tw - 6);
                folderEdit.setTextColor(theme.c("tab.active.fg") & 0xFFFFFF);
                folderEdit.render(g, mx, my, 0);
                continue;
            }
            String label = Draw.ellipsize(folderLabel(index), (int) ((tw - 6) / 0.75f));
            float textW = Draw.widthScaled(label, 0.75f);
            Draw.textScaled(g, label, tx + (tw - textW) / 2f, top + 3, 0.75f, current ? theme.c("tab.active.fg") : theme.c("tab.inactive.fg"));
        }
        int plusX = folderPlusX();
        boolean plusHot = overMarksHeader(mx, my) && mx >= plusX - 1 && mx < plusX + 11;
        works.repka.ryscore.ui.Icons.draw(g, works.repka.ryscore.ui.Icons.Shape.PLUS, plusX + 2, top + 2, 8f, plusHot ? theme.c("accent") : theme.c("text.dim"));
    }

    private static boolean marksHeaderClicked(double mx, double my, int button) {
        if (mx >= folderPlusX() - 1 && mx < folderPlusX() + 11) {
            commitFolderName();
            if (Bookmarks.addFolder("")) startFolderRename();
            else toast(Component.translatable("ryscope.bookmarks.folder.limit").getString());
            return true;
        }
        int index = folderTabAt(mx, my);
        if (index < 0) return true;
        if (button == 0) {
            if (index != Bookmarks.active()) {
                commitFolderName();
                Bookmarks.setActive(index);
                MARKS.resetScroll();
            } else if (folderEdit == null) {
                startFolderRename();
            }
        } else if (button == 1) {
            commitFolderName();
            if (index != Bookmarks.active()) {
                Bookmarks.setActive(index);
                MARKS.resetScroll();
                folderDeleteArmed = 0;
            }
            long now = System.currentTimeMillis();
            int size = Bookmarks.list().size();
            if (size == 0 || now < folderDeleteArmed) {
                Bookmarks.removeActive();
                MARKS.resetScroll();
                folderDeleteArmed = 0;
                toast(Component.translatable("ryscope.bookmarks.folder.removed").getString());
            } else {
                folderDeleteArmed = now + 3000;
                toast(Component.translatable("ryscope.bookmarks.folder.confirm", size).getString(), 3000);
            }
        }
        return true;
    }

    private static void startFolderRename() {
        if (search != null) search.setFocused(false);
        folderEdit = new Field(40, 10);
        folderEdit.setMaxLength(24);
        folderEdit.setValue(Bookmarks.current().name);
        takeFocus(Minecraft.getInstance().screen);
        folderEdit.setFocused(true);
    }

    private static void commitFolderName() {
        if (folderEdit == null) return;
        Bookmarks.renameActive(folderEdit.getValue());
        folderEdit = null;
    }

    // --- сохранённые запросы ---

    /** Сохранённые запросы, подходящие под набранное. Список пересобирается каждый кадр, пока поле в фокусе. */
    private static List<String> savedCandidates() {
        savedShown.clear();
        if (search == null || !search.isFocused() || !LIST.visible()) return savedShown;
        String typed = search.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        for (String s : Config.get().savedSearches) {
            if (savedShown.size() >= 6) break;
            String low = s.toLowerCase(java.util.Locale.ROOT);
            if (typed.isEmpty() || (low.contains(typed) && !low.equals(typed))) savedShown.add(s);
        }
        if (savedIndex >= savedShown.size()) savedIndex = savedShown.size() - 1;
        return savedShown;
    }

    private static int savedListY() {
        if (!Config.get().searchBottom) return searchRect[1] + searchRect[3] + 1;
        return searchRect[1] - 1 - (savedShown.size() * 10 + 4);
    }

    private static int savedRowAt(double mx, double my) {
        if (savedShown.isEmpty() || search == null || !search.isFocused()) return -1;
        int x = searchRect[0], y = savedListY();
        if (mx < x || mx >= x + searchRect[2] || my < y + 2 || my >= y + 2 + savedShown.size() * 10) return -1;
        return (int) ((my - y - 2) / 10);
    }

    private static void renderSaved(GuiGraphics g, Theme theme, int mx, int my) {
        List<String> rows = savedCandidates();
        if (rows.isEmpty()) return;
        int x = searchRect[0], y = savedListY(), w = searchRect[2], h = rows.size() * 10 + 4;
        int hover = savedRowAt(mx, my);
        g.pose().pushPose();
        g.pose().translate(0, 0, 390);
        theme.tooltipBox(g, x, y, w, h);
        for (int i = 0; i < rows.size(); i++) {
            int ry = y + 2 + i * 10;
            if (i == hover || i == savedIndex) Draw.rect(g, x + 1, ry, w - 2, 10, (theme.c("accent") & 0xFFFFFF) | 0x38000000);
            Draw.textScaled(g, Draw.ellipsize(rows.get(i), (int) ((w - 8) / 0.75f)), x + 4, ry + 2, 0.75f, theme.c("text.body"));
        }
        g.pose().popPose();
    }

    /** Ctrl+S в поле поиска: сохранить запрос или убрать его из сохранённых. */
    private static void toggleSaved(String query) {
        if (query.isEmpty()) {
            toast(Component.translatable("ryscope.search.saved.empty").getString());
            return;
        }
        List<String> saved = Config.get().savedSearches;
        if (saved.remove(query)) {
            toast(Component.translatable("ryscope.search.saved.removed").getString());
        } else {
            saved.add(0, query);
            while (saved.size() > 24) saved.remove(saved.size() - 1);
            toast(Component.translatable("ryscope.search.saved.added").getString());
        }
        Config.save();
    }

    @Nullable
    private static Stack ownStackAt(double mx, double my) {
        Stack s = LIST.at(mx, my);
        if (s == null) s = MARKS.at(mx, my);
        if (s == null) s = Stickers.stackAt(mx, my);
        return s;
    }

    /** Ресурс под курсором где угодно на экране: в наших панелях, в слоте контейнера, в виджете чужого мода. */
    @Nullable
    public static Stack hovered(Screen screen, double mx, double my) {
        Stack s = ownStackAt(mx, my);
        if (s != null) return s;
        if (screen instanceof RecipeScreen rs) return rs.hoveredStack((int) mx, (int) my);
        if (screen instanceof AbstractContainerScreen<?> acs) {
            // в терминале AE2 слоты сети показывают обёртки: предмет или жидкость точнее назовёт JEI
            if (works.repka.ryscope.runtime.Ae2Terminal.isTerminal(acs.getMenu()) && Foreign.get() != null) {
                try {
                    Stack typed = Foreign.get().stackUnderMouse(screen, UiScale.toGui(mx), UiScale.toGui(my));
                    if (typed != null) return typed;
                } catch (Throwable ignored) {
                    // чужой обработчик
                }
            }
            Slot slot = Platform.get().hoveredSlot(acs);
            if (slot != null && slot.hasItem()) return new ItemRes(slot.getItem());
        }
        Foreign.Source foreign = Foreign.get();
        if (foreign != null) {
            try {
                return foreign.stackUnderMouse(screen, UiScale.toGui(mx), UiScale.toGui(my));
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    // --- экраны чужих модов (квесты FTB, справочники) ---

    /**
     * Ресурс под курсором на экране чужого мода. Работает там, где мод сообщает JEI о предметах под курсором:
     * так делает FTB Library (экраны квестов FTB Quests), и тем же путём у игроков работают клавиши самого JEI.
     */
    @Nullable
    private static Stack foreignStack(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        Foreign.Source foreign = Foreign.get();
        if (foreign == null || mc.level == null || mc.player == null) return null;
        try {
            return foreign.stackUnderMouse(screen, UiScale.toGui(mouseX()), UiScale.toGui(mouseY()));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Наши клавиши над предметом на чужом экране: рецепты, использования, закладка, дерево крафта. */
    public static boolean foreignKey(Screen screen, int key, int scan) {
        boolean recipes = Keys.matches(Keys.RECIPES, key, scan), uses = Keys.matches(Keys.USES, key, scan);
        boolean bookmark = Keys.matches(Keys.BOOKMARK, key, scan), tree = Keys.matches(Keys.TREE, key, scan);
        boolean share = Keys.matches(Keys.SHARE, key, scan);
        if (!recipes && !uses && !bookmark && !tree && !share) return false;
        if (typingElsewhere(screen)) return false;
        Stack stack = foreignStack(screen);
        if (stack == null) return false;
        if (recipes) RecipeScreen.open(stack, Lookup.Mode.RECIPES);
        else if (uses) RecipeScreen.open(stack, Lookup.Mode.USES);
        else if (bookmark) toggleBookmark(stack);
        else if (share) Share.toChat(stack);
        else TreeScreen.open(stack);
        return true;
    }

    /** На чужом экране рисуем только линзу над предметом и уведомления. */
    public static void renderForeign(Screen screen, GuiGraphics g, int mx, int my) {
        if (Lens.held()) {
            Stack target = foreignStack(screen);
            if (target != null) Lens.renderCard(g, ThemeManager.get(), target, mx, my, UiScale.width(), UiScale.height());
        }
        renderToast(screen, g);
    }

    /** Для автотеста. */
    public static void nextPageForTest() {
        LIST.pageBy(1);
    }

    /** Для автотеста: центр первой ячейки панели целей. */
    @Nullable
    public static int[] goalCenterForTest() {
        return Stickers.firstCenter();
    }

    /** Ресурс под курсором в панели списка (для модов, которые спрашивают об этом через JEI). */
    @Nullable
    public static Stack listStackUnderMouse() {
        return LIST.at(mouseX(), mouseY());
    }

    /** Ресурс под курсором в закладках или в целях (клавиши Botania и других модов над закладками JEI). */
    @Nullable
    public static Stack markStackUnderMouse() {
        if (!active(Minecraft.getInstance().screen)) return null;
        Stack s = MARKS.at(mouseX(), mouseY());
        if (s instanceof works.repka.ryscope.runtime.RecipeMark mark) return mark.output;
        return s != null ? s : Stickers.stackAt(mouseX(), mouseY());
    }

    /** Видимая страница списка. */
    public static List<Stack> visibleListStacks() {
        return active(Minecraft.getInstance().screen) ? LIST.visibleItems() : List.of();
    }

    /**
     * Shift+клик по списку кладёт предмет в фильтр окна без перетаскивания, как quickMove в JEI.
     * @return true, если предмет положен
     */
    private static boolean quickMove(Screen screen, Stack stack) {
        Foreign.Source foreign = Foreign.get();
        if (foreign == null) return false;
        try {
            return foreign.quickMove(screen, stack);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Левый край правой панели: карточка рецептов встаёт перед ним. */
    public static int sidebarLeft(Screen screen) {
        layout(screen);
        if (sidebarCollapsed) return stripRect[0];
        return LIST.visible() ? LIST.x : UiScale.width();
    }

    /** Карточка уступила место панели по желанию игрока. */
    public static boolean cardCollapsed() {
        return cardCollapsed;
    }

    /** Вернуть приоритет карточке: панель снова свернётся, если места на обе не хватает. */
    public static void showCard() {
        sidebarWanted = false;
    }

    /** Показать в списке элемент с этим номером. */
    public static void revealInList(int index) {
        LIST.reveal(index);
    }

    private static boolean overStrip(double mx, double my) {
        return sidebarCollapsed && mx >= stripRect[0] && mx < stripRect[0] + STRIP_W && my >= stripRect[1] && my < stripRect[1] + stripRect[3];
    }

    public static boolean isOver(double mx, double my) {
        return overStrip(mx, my) || LIST.contains(mx, my) || MARKS.contains(mx, my) || Stickers.goalAt(mx, my) != null || Dock.contains(mx, my);
    }

    public static boolean mouseClicked(Screen screen, double mx, double my, int button) {
        if (!active(screen)) return false;
        if (overStrip(mx, my)) {
            sidebarWanted = true;
            return true;
        }
        EditBox box = search();
        if (!overMarksHeader(mx, my)) commitFolderName();
        int savedRow = savedRowAt(mx, my);
        if (savedRow >= 0) {
            String query = savedShown.get(savedRow);
            if (button == 1) {
                Config.get().savedSearches.remove(query);
                Config.save();
                toast(Component.translatable("ryscope.search.saved.removed").getString());
            } else {
                box.setValue(query);
            }
            return true;
        }
        boolean inSearch = LIST.visible() && mx >= searchRect[0] && mx < searchRect[0] + searchRect[2]
            && my >= searchRect[1] && my < searchRect[1] + searchRect[3];
        if (inSearch && button == 0 && !box.getValue().isEmpty() && mx >= searchRect[0] + searchRect[2] - 13) {
            box.setValue("");
            takeFocus(screen);
            box.setFocused(true);
            return true;
        }
        if (inSearch) {
            long now = System.currentTimeMillis();
            if (button == 0 && now - lastSearchClick < 300) {
                highlight = !highlight;
                toast(Component.translatable(highlight ? "ryscope.search.highlight_on" : "ryscope.search.highlight_off").getString());
            }
            lastSearchClick = now;
            if (!box.isFocused()) SearchHistory.add(box.getValue());
            if (button == 1) box.setValue("");
            takeFocus(screen);
            box.setFocused(true);
            if (button == 0) box.mouseClicked(Math.max(mx, box.getX()), box.getY() + 2, 0);
            return true;
        }
        if (box.isFocused()) {
            box.setFocused(false);
            saveSearch();
        }
        if (!isOver(mx, my)) return false;
        if (LIST.visible() && my >= filterRowY && my < filterRowY + FILTER_H) {
            for (int i = 0; i < 3; i++) {
                if (filterRects[i][1] > 0 && mx >= filterRects[i][0] && mx < filterRects[i][0] + filterRects[i][1]) {
                    setFilter(button == 0 && filter == i ? FILTER_ALL : i);
                    return true;
                }
            }
        }
        if (button == 0 && overDetach(mx, my)) {
            toggleDetached();
            return true;
        }
        if (MARKS.visible() && !marksLeft && Bookmarks.folders().size() <= 1 && folderEdit == null && my >= marksTop && my < MARKS.gridY
            && mx >= showAllRect[0] && mx < showAllRect[0] + showAllRect[1]) {
            setFilter(FILTER_BOOKMARKS);
            return true;
        }
        if (overMarksHeader(mx, my)) return marksHeaderClicked(mx, my, button);
        if (LIST.visible() && mx >= gearRect[0] - 2 && mx < gearRect[0] + 12 && my >= gearRect[1] - 2 && my < gearRect[1] + 12) {
            Minecraft.getInstance().setScreen(new SettingsScreen(screen));
            return true;
        }
        if (LIST.visible() && LIST.paged && LIST.pages() > 1 && my >= pageArrows[2] - 2 && my < pageArrows[2] + 12) {
            if (mx >= pageArrows[0] - 2 && mx < pageArrows[0] + 7) {
                LIST.pageBy(-1);
                return true;
            }
            if (mx >= pageArrows[1] - 2 && mx < pageArrows[1] + 7) {
                LIST.pageBy(1);
                return true;
            }
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ItemStack carried = player == null ? ItemStack.EMPTY : player.containerMenu.getCarried();
        if (LIST.contains(mx, my) && !LIST.inGrid(mx, my) && LIST.scrollbarDrag(mx, my)) {
            draggingScroll = true;
            return true;
        }
        if (!carried.isEmpty() && screen instanceof AbstractContainerScreen<?> && cheating() && LIST.at(mx, my) != null) {
            // режим читов, как в JEI: тот же предмет добавляется на курсор, другой предмет с курсора убирается
            Stack under = LIST.at(mx, my);
            if (button == 0 && ItemStack.isSameItemSameComponents(under.item(), carried)) give(under, Screen.hasShiftDown());
            else if (button == 0) Give.deleteCarried();
            return true;
        }
        if (!carried.isEmpty() && screen instanceof AbstractContainerScreen<?>) {
            // предмет на курсоре: бросок на закладки закрепляет его, на список ничего не делает (и не выбрасывает)
            if (MARKS.contains(mx, my) || (LIST.contains(mx, my) && !MARKS.visible())) {
                if (Bookmarks.toggle(new ItemRes(carried))) toast(Component.translatable("ryscope.bookmark.added").getString());
            }
            return true;
        }
        if (Dock.contains(mx, my)) {
            Dock.click(mx, my, button);
            return true;
        }
        if (Stickers.goalAt(mx, my) != null) {
            // левая кнопка открывает дерево крафта цели, правая снимает её с доски
            Stack goal = Stickers.stackAt(mx, my);
            if (button == 0) {
                TreeScreen.open(goal);
            } else if (button == 1) {
                works.repka.ryscope.runtime.Board.toggle(goal, 1);
                toast(Component.translatable("ryscope.goals.removed").getString());
            }
            return true;
        }
        int markIndex = MARKS.indexAt(mx, my);
        if (markIndex >= 0 && button == 0 && Screen.hasShiftDown()) {
            dragIndex = markIndex;
            return true;
        }
        Stack stack = ownStackAt(mx, my);
        if (stack == null) return true;
        if (stack instanceof works.repka.ryscope.runtime.Groups.GroupStack gs && button == 0) {
            works.repka.ryscope.runtime.Groups.toggle(gs.group);
            SearchService.submit(SearchService.text());
            return true;
        }
        if (button == 2) {
            toggleBookmark(stack);
            return true;
        }
        if (button == 0 && Screen.hasShiftDown() && LIST.at(mx, my) != null && screen instanceof AbstractContainerScreen<?> && quickMove(screen, stack)) {
            return true;
        }
        if (button == 0 && cheating() && (LIST.at(mx, my) != null || MARKS.at(mx, my) != null)) {
            give(stack, Screen.hasShiftDown());
            return true;
        }
        if (button == 0 && screen instanceof AbstractContainerScreen<?>) {
            // клик или перетаскивание в фильтр: решится при движении или отпускании
            pressStack = stack;
            pressX = mx;
            pressY = my;
            return true;
        }
        if (button == 0 || button == 1) {
            RecipeScreen.open(stack, button == 0 ? Lookup.Mode.RECIPES : Lookup.Mode.USES);
        }
        return true;
    }

    public static boolean mouseReleased(Screen screen, double mx, double my, int button) {
        if (ghostStack != null) {
            Stack dragged = ghostStack;
            ghostStack = null;
            works.repka.ryscope.runtime.Foreign.Source foreign = works.repka.ryscope.runtime.Foreign.get();
            boolean placed = false;
            if (foreign != null) {
                placed = foreign.ghostDrop(UiScale.toGui(mx), UiScale.toGui(my));
                foreign.ghostEnd();
            }
            if (!placed && MARKS.contains(mx, my) && !Bookmarks.contains(dragged)) {
                Bookmarks.toggle(dragged);
                toast(Component.translatable("ryscope.bookmark.added").getString());
            }
            return true;
        }
        if (pressStack != null && button == 0) {
            Stack clicked = pressStack;
            pressStack = null;
            RecipeScreen.open(clicked, Lookup.Mode.RECIPES);
            return true;
        }
        if (dragIndex >= 0) {
            int to = MARKS.indexAt(mx, my);
            if (to >= 0) Bookmarks.move(dragIndex, to);
            dragIndex = -1;
            return true;
        }
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return false;
    }

    public static boolean mouseDragged(Screen screen, double mx, double my, int button) {
        if (pressStack != null && Math.hypot(mx - pressX, my - pressY) > GHOST_THRESHOLD) {
            ghostStack = pressStack;
            pressStack = null;
            works.repka.ryscope.runtime.Foreign.Source foreign = works.repka.ryscope.runtime.Foreign.get();
            if (foreign != null) foreign.ghostStart(screen, ghostStack);
        }
        if (ghostStack != null || pressStack != null) return true;
        if (draggingScroll) {
            LIST.scrollbarDrag(Math.max(mx, LIST.gridX + LIST.gridW), my);
            return true;
        }
        return dragIndex >= 0;
    }

    public static boolean mouseScrolled(Screen screen, double mx, double my, double delta) {
        if (!active(screen)) return false;
        if (LIST.contains(mx, my)) {
            LIST.scrollBy(delta);
            return true;
        }
        if (overMarksHeader(mx, my) && Bookmarks.folders().size() > 1) {
            commitFolderName();
            Bookmarks.setActive(Bookmarks.active() + (delta > 0 ? -1 : 1));
            MARKS.resetScroll();
            return true;
        }
        if (MARKS.contains(mx, my)) {
            MARKS.scrollBy(delta);
            return true;
        }
        return false;
    }

    public static boolean keyPressed(Screen screen, int key, int scan, int mods) {
        boolean host = screen instanceof AbstractContainerScreen<?> || screen instanceof RecipeScreen;
        if (host && Keys.matches(Keys.TOGGLE, key, scan) && !searchFocused() && !typingElsewhere(screen)) {
            Config.get().overlayEnabled = !Config.get().overlayEnabled;
            Config.save();
            // виджет FTB убирается при создании экрана: пересоздаём, чтобы он вернулся или исчез
            if (screen instanceof AbstractContainerScreen<?> && Dock.available()) {
                screen.resize(Minecraft.getInstance(), screen.width, screen.height);
            }
            return true;
        }
        if (!active(screen)) return false;
        EditBox box = search();
        if (folderEdit != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) commitFolderName();
            else if (key == GLFW.GLFW_KEY_ESCAPE) folderEdit = null;
            else folderEdit.keyPressed(key, scan, mods);
            return true;
        }
        if (Keys.matches(Keys.HIDE_QUERY, key, scan) && LIST.visible() && (box.isFocused() || !typingElsewhere(screen))) {
            hideCurrent(box);
            return true;
        }
        if (box.isFocused()) {
            if (Keys.matches(Keys.SAVE_SEARCH, key, scan)) {
                toggleSaved(box.getValue().trim());
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP && savedIndex < 0) {
                String previous = SearchHistory.previous(box.getValue());
                if (previous != null) {
                    box.setValue(previous);
                    box.moveCursorToEnd(false);
                }
                browsingHistory = true;
                return true;
            }
            if (key == GLFW.GLFW_KEY_DOWN && savedIndex < 0 && (browsingHistory || savedShown.isEmpty())) {
                String next = SearchHistory.next(box.getValue());
                if (next != null) {
                    box.setValue(next);
                    box.moveCursorToEnd(false);
                }
                browsingHistory = next != null && !next.isEmpty();
                return true;
            }
            if (key == GLFW.GLFW_KEY_DOWN && !savedShown.isEmpty()) {
                savedIndex = Math.min(savedShown.size() - 1, savedIndex + 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP && savedIndex >= 0) {
                savedIndex--;
                return true;
            }
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && savedIndex >= 0 && savedIndex < savedShown.size()) {
                box.setValue(savedShown.get(savedIndex));
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                box.setFocused(false);
                saveSearch();
                return true;
            }
            browsingHistory = false;
            if (key == GLFW.GLFW_KEY_TAB) autocomplete(box);
            else if (Keys.matches(Keys.NEXT_PAGE, key, scan)) LIST.pageBy(1);
            else if (Keys.matches(Keys.PREV_PAGE, key, scan)) LIST.pageBy(-1);
            else box.keyPressed(key, scan, mods);
            // пока поле в фокусе, клавиши не должны доходить до экрана (иначе «E» закроет инвентарь)
            return true;
        }
        if (Keys.matches(Keys.SEARCH, key, scan) && LIST.visible()) {
            SearchHistory.add(box.getValue());
            takeFocus(screen);
            box.setFocused(true);
            box.moveCursorToEnd(false);
            box.setHighlightPos(0);
            return true;
        }
        if (Keys.matches(Keys.THEME, key, scan)) {
            toast(ThemeManager.cycle().name);
            return true;
        }
        if (typingElsewhere(screen)) return false;
        // в карточке рецептов страницы листают рецепты, а список только под курсором
        boolean pagesList = LIST.visible() && (!(screen instanceof RecipeScreen) || LIST.contains(mouseX(), mouseY()));
        if (Keys.matches(Keys.NEXT_PAGE, key, scan) && pagesList) {
            LIST.pageBy(1);
            return true;
        }
        if (Keys.matches(Keys.PREV_PAGE, key, scan) && pagesList) {
            LIST.pageBy(-1);
            return true;
        }
        double mx = mouseX(), my = mouseY();
        Stack stack = hovered(screen, mx, my);
        if (stack == null) return false;
        if (Keys.matches(Keys.HIDE, key, scan)) {
            toggleHidden(stack);
            return true;
        }
        if (Keys.matches(Keys.SHARE, key, scan)) {
            Share.toChat(stack);
            return true;
        }
        if (Keys.matches(Keys.COPY_ID, key, scan)) {
            copy(stack.id().toString());
            return true;
        }
        // Shift раскладывает максимум: клавиша проверяется без модификаторов
        if (Keys.matchesRaw(Keys.CRAFT, key, scan) && !Screen.hasControlDown() && !Screen.hasAltDown() && QuickCraft.run(screen, stack, Screen.hasShiftDown())) {
            return true;
        }
        if (Keys.matches(Keys.RECIPES, key, scan)) {
            RecipeScreen.open(stack, Lookup.Mode.RECIPES);
            return true;
        }
        if (Keys.matches(Keys.USES, key, scan)) {
            RecipeScreen.open(stack, Lookup.Mode.USES);
            return true;
        }
        if (Keys.matches(Keys.BOOKMARK, key, scan)) {
            toggleBookmark(stack);
            return true;
        }
        if (Keys.matches(Keys.TREE, key, scan)) {
            TreeScreen.open(stack);
            return true;
        }
        return false;
    }

    public static boolean charTyped(Screen screen, char c, int mods) {
        if (!active(screen)) return false;
        if (folderEdit != null) {
            folderEdit.charTyped(c, mods);
            return true;
        }
        EditBox box = search();
        if (box.isFocused()) {
            box.charTyped(c, mods);
            return true;
        }
        return false;
    }

    /**
     * Наше поле берёт фокус: поле самого экрана его отпускает. Иначе печатают оба, а поиск терминала AE2
     * с синхронизацией каждый кадр переписывает наш запрос своим текстом.
     */
    private static void takeFocus(@Nullable Screen screen) {
        if (screen == null) return;
        if (screen.getFocused() instanceof EditBox e) {
            e.setFocused(false);
            screen.setFocused(null);
        }
    }

    /** Игрок печатает в поле самого экрана (наковальня, поиск творческого режима): горячие клавиши молчат. */
    private static boolean typingElsewhere(Screen screen) {
        return screen.getFocused() instanceof EditBox e && e.isFocused() && e.isVisible();
    }

    /** Ctrl+H: скрыть из списка всё, что нашёл текущий запрос. Возвращается в настройках. */
    private static void hideCurrent(EditBox box) {
        String query = box.getValue().trim();
        if (query.isEmpty()) {
            toast(Component.translatable("ryscope.hidden.empty").getString());
            return;
        }
        int count = SearchService.results().stacks.size();
        Config.get().hiddenQueries.add(query);
        Config.save();
        box.setValue("");
        toast(Component.translatable("ryscope.hidden.added", count).getString());
    }

    public static void copy(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        toast(Component.translatable("ryscope.copied", text).getString());
    }

    private static void toggleHidden(Stack stack) {
        boolean all = Screen.hasShiftDown();
        boolean hidden = works.repka.ryscope.runtime.HiddenItems.toggle(stack, all);
        String name = stack.name().getString();
        toast(Component.translatable(!hidden ? "ryscope.hidden.item.shown" : all ? "ryscope.hidden.item.all" : "ryscope.hidden.item", name).getString());
    }

    private static void toggleBookmark(Stack stack) {
        boolean added = Bookmarks.toggle(stack);
        toast(Component.translatable(added ? "ryscope.bookmark.added" : "ryscope.bookmark.removed").getString());
    }

    private static void saveSearch() {
        String value = search == null ? "" : search.getValue();
        SearchHistory.add(value);
        browsingHistory = false;
        if (!value.equals(lastQuery)) {
            lastQuery = value;
            Config.get().lastSearch = value;
            Config.save();
        }
    }

    public static void onScreenClosed() {
        if (search != null) search.setFocused(false);
        commitFolderName();
        savedIndex = -1;
        draggingScroll = false;
        dragIndex = -1;
        pressStack = null;
        if (ghostStack != null) {
            ghostStack = null;
            works.repka.ryscope.runtime.Foreign.Source foreign = works.repka.ryscope.runtime.Foreign.get();
            if (foreign != null) foreign.ghostEnd();
        }
        saveSearch();
    }

    public static void setSearch(String text) {
        search().setValue(text);
    }

    /** Режим читов включён и выдавать можно: творческий режим или оператор при RYScope на сервере. */
    static boolean cheating() {
        return Config.get().cheatMode && Give.allowed();
    }

    /** Выдача, как в JEI: на курсор, с Alt в инвентарь; Shift даёт стак. */
    static void give(Stack stack, boolean full) {
        give(stack, full, !Screen.hasAltDown());
    }

    static void give(Stack stack, boolean full, boolean toCursor) {
        ItemStack item = stack.item();
        if (item.isEmpty()) return;
        if (!Give.give(item, full ? item.getMaxStackSize() : 1, toCursor)) {
            toast(Component.translatable("ryscope.cheat.denied").getString());
        }
    }

    static double mouseX() {
        return UiScale.mouseX();
    }

    static double mouseY() {
        return UiScale.mouseY();
    }
}
