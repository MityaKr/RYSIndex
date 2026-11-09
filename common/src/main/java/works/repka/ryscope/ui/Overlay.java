package works.repka.ryscope.ui;

import java.util.ArrayList;
import java.util.List;
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
import works.repka.ryscope.runtime.Indexer;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.SearchService;
import works.repka.ryscope.ui.theme.Theme;
import works.repka.ryscope.ui.theme.ThemeManager;

/**
 * Панели поверх экранов с инвентарём: список ресурсов с поиском справа, закладки слева.
 * Ничего не считает сам: список приходит из фонового поиска, здесь только раскладка, отрисовка и ввод.
 */
public final class Overlay {
    private static final int MARGIN = 4;
    private static final int PAD = 5;
    private static final int SEARCH_H = 14;
    private static final int FOOTER_H = 10;

    private static final GridPanel LIST = new GridPanel();
    private static final GridPanel MARKS = new GridPanel();
    private static final GridPanel RECENT = new GridPanel();
    private static EditBox search;
    private static final int[] searchRect = new int[4];
    private static boolean draggingScroll;
    private static String toast;
    private static long toastUntil;
    private static String lastQuery = "";
    private static int dragIndex = -1;
    private static boolean highlight;
    private static long lastSearchClick;
    private static SearchService.Results highlightFor;
    private static java.util.Set<Object> highlightKeys = java.util.Set.of();
    private static String acBase, acLast;
    private static int acStart, acIndex;
    private static java.util.List<String> tagCache;
    private static works.repka.ryscope.runtime.Snapshot tagCacheFor;
    private static final int[] gearRect = new int[4];

    private Overlay() {}

    public static boolean active(@Nullable Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.get().overlayEnabled || mc.level == null || mc.player == null) return false;
        return screen instanceof AbstractContainerScreen<?> || screen instanceof RecipeScreen;
    }

    public static boolean searchFocused() {
        return search != null && search.isFocused();
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
            search = new EditBox(mc.font, 0, 0, 100, 10, Component.empty());
            search.setBordered(false);
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
        if (screen instanceof AbstractContainerScreen<?> acs) return Platform.get().screenBounds(acs);
        if (screen instanceof RecipeScreen rs) return rs.cardBounds();
        return new int[] {0, 0, screen.width, screen.height};
    }

    private static void layout(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        Config cfg = Config.get();
        int sw = screen.width, sh = screen.height;
        int[] b = hostBounds(screen);
        int left = b[0], right = b[0] + b[2];
        Foreign.Source foreign = Foreign.get();
        if (foreign != null && screen instanceof AbstractContainerScreen<?>) {
            try {
                for (int[] area : foreign.exclusionAreas(screen)) {
                    // боковые вкладки чужих экранов расширяют занятую область
                    if (area[0] + area[2] > right && area[0] < right + 80) right = area[0] + area[2];
                    if (area[0] < left && area[0] + area[2] > left - 80) left = area[0];
                }
            } catch (Throwable ignored) {
                // чужой обработчик областей не должен мешать раскладке
            }
        }
        if (screen instanceof EffectRenderingInventoryScreen<?> && mc.player != null && !mc.player.getActiveEffects().isEmpty()) {
            right += 34;
        }
        if (screen instanceof RecipeUpdateListener rul && rul.getRecipeBookComponent().isVisible()) {
            left = Math.min(left, b[0] - 150);
        }
        int cell = cfg.slotSize();
        int maxCols = cfg.maxColumns > 0 ? cfg.maxColumns : 99;

        // список справа
        int avail = sw - MARGIN - (right + MARGIN);
        int cols = Math.min(maxCols, (avail - PAD * 2 - 4) / cell);
        if (cols < 3) {
            LIST.hide();
        } else {
            LIST.cell = cell;
            LIST.cols = cols;
            LIST.w = cols * cell + PAD * 2 + 4;
            LIST.x = sw - MARGIN - LIST.w;
            LIST.y = MARGIN;
            LIST.h = sh - MARGIN * 2;
            searchRect[0] = LIST.x + PAD;
            searchRect[1] = LIST.y + PAD;
            searchRect[2] = LIST.w - PAD * 2;
            searchRect[3] = SEARCH_H;
            LIST.gridX = LIST.x + PAD;
            LIST.gridY = LIST.y + PAD + SEARCH_H + 4;
            LIST.gridW = cols * cell;
            LIST.gridH = LIST.y + LIST.h - PAD - FOOTER_H - 2 - LIST.gridY;
            EditBox box = search();
            box.setX(searchRect[0] + 5);
            box.setY(searchRect[1] + 3);
            box.setWidth(searchRect[2] - 22);
        }

        // закладки слева
        List<Stack> marks = Bookmarks.list();
        int availL = left - MARGIN - MARGIN;
        int colsL = Math.min(maxCols, (availL - PAD * 2 - 4) / cell);
        if (!cfg.bookmarksPanel || marks.isEmpty() || colsL < 2) {
            MARKS.hide();
        } else {
            MARKS.cell = cell;
            MARKS.cols = colsL;
            MARKS.w = colsL * cell + PAD * 2 + 4;
            MARKS.x = MARGIN;
            MARKS.y = MARGIN;
            int rows = (marks.size() + colsL - 1) / colsL;
            MARKS.h = Math.min(sh - MARGIN * 2, PAD * 2 + 12 + rows * cell);
            MARKS.gridX = MARKS.x + PAD;
            MARKS.gridY = MARKS.y + PAD + 12;
            MARKS.gridW = colsL * cell;
            MARKS.gridH = MARKS.y + MARKS.h - PAD - MARKS.gridY;
            MARKS.setItems(marks);
        }

        // недавние: под закладками, не больше двух рядов
        List<Stack> recent = works.repka.ryscope.runtime.History.list();
        int top = MARKS.visible() ? MARKS.y + MARKS.h + MARGIN : MARGIN;
        if (!cfg.recentPanel || recent.isEmpty() || colsL < 2 || top + PAD * 2 + 12 + cell > sh - MARGIN) {
            RECENT.hide();
        } else {
            RECENT.cell = cell;
            RECENT.cols = colsL;
            RECENT.w = colsL * cell + PAD * 2 + 4;
            RECENT.x = MARGIN;
            RECENT.y = top;
            int rows = Math.min(2, (recent.size() + colsL - 1) / colsL);
            RECENT.h = PAD * 2 + 12 + rows * cell;
            RECENT.gridX = RECENT.x + PAD;
            RECENT.gridY = RECENT.y + PAD + 12;
            RECENT.gridW = colsL * cell;
            RECENT.gridH = rows * cell;
            RECENT.setItems(recent);
        }
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
        Font font = Minecraft.getInstance().font;

        if (LIST.visible()) {
            SearchService.Results results = SearchService.results();
            LIST.setItems(results.stacks);
            theme.panel(g, LIST.x, LIST.y, LIST.w, LIST.h);
            EditBox box = search();
            theme.input(g, searchRect[0], searchRect[1], searchRect[2], searchRect[3], box.isFocused() || highlight);
            box.setTextColor(theme.c("input.fg") & 0xFFFFFF);
            box.render(g, mx, my, partialTick);
            if (!box.getValue().isEmpty()) {
                int cx = searchRect[0] + searchRect[2] - 10, cy = searchRect[1] + 4;
                boolean ch = mx >= cx - 2 && mx < cx + 8 && my >= cy - 2 && my < cy + 8;
                int cc = ch ? theme.c("accent") : theme.c("text.dim");
                for (int i = 0; i < 6; i++) {
                    Draw.rect(g, cx + i, cy + i, 1, 1, cc);
                    Draw.rect(g, cx + 5 - i, cy + i, 1, 1, cc);
                }
            }
            LIST.render(g, theme, mx, my, animate);

            int fy = LIST.y + LIST.h - PAD - FOOTER_H + 1;
            if (Indexer.state() == Indexer.State.BUILDING) {
                int bw = LIST.w - PAD * 2;
                Draw.round(g, LIST.x + PAD, fy + 7, bw, 2, 1f, theme.c("slot.bg"));
                Draw.round(g, LIST.x + PAD, fy + 7, Math.max(2, bw * Indexer.progress()), 2, 1f, theme.c("accent"));
                Draw.textScaled(g, Component.translatable(Indexer.step()).getString(), LIST.x + PAD, fy - 1, 0.75f, theme.c("text.muted"));
            } else {
                String count = String.format("%,d", results.stacks.size()).replace(',', ' ');
                if (results.exactCount < results.stacks.size() && !results.query.isEmpty()) {
                    count = Component.translatable("ryscope.search.fuzzy", count).getString();
                }
                if (ownStackAt(mx, my) != null) {
                    // пока курсор над предметом, вместо счётчика показываем, что с ним можно сделать
                    count = Component.translatable("ryscope.list.keys", Keys.label(Keys.RECIPES), Keys.label(Keys.USES),
                        Keys.label(Keys.BOOKMARK), Keys.label(Keys.TREE)).getString();
                    count = Draw.ellipsize(count, (int) ((LIST.w - PAD * 2 - 30) / 0.75f));
                }
                Draw.textScaled(g, count, LIST.x + PAD, fy + 2, 0.75f, theme.c("text.dim"));
            }
            // кнопка настроек: три «ползунка»
            gearRect[0] = LIST.x + LIST.w - PAD - 10;
            gearRect[1] = fy;
            gearRect[2] = 10;
            gearRect[3] = 10;
            // кнопка «только то, что можно скрафтить»: значок сетки 2×2
            int cbx = gearRect[0] - 15;
            boolean craftOn = box.getValue().contains("craftable");
            boolean craftHover = mx >= cbx - 2 && mx < cbx + 11 && my >= fy - 2 && my < fy + 12;
            if (Indexer.state() != Indexer.State.BUILDING) {
                int cc = craftOn ? theme.c("accent") : craftHover ? theme.c("text.body") : theme.c("text.dim");
                Draw.rect(g, cbx + 1, fy + 2, 3, 3, cc);
                Draw.rect(g, cbx + 5, fy + 2, 3, 3, cc);
                Draw.rect(g, cbx + 1, fy + 6, 3, 3, cc);
                Draw.rect(g, cbx + 5, fy + 6, 3, 3, cc);
            }
            boolean gearHover = mx >= gearRect[0] - 2 && mx < gearRect[0] + 12 && my >= gearRect[1] - 2 && my < gearRect[1] + 12;
            int gc = gearHover ? theme.c("accent") : theme.c("text.dim");
            if (Indexer.state() != Indexer.State.BUILDING) {
                for (int i = 0; i < 3; i++) {
                    Draw.rect(g, gearRect[0], fy + 2 + i * 3, 9, 1, gc);
                    Draw.rect(g, gearRect[0] + (i == 1 ? 5 : 2), fy + 1 + i * 3, 2, 3, gc);
                }
            }
        }

        if (MARKS.visible()) {
            theme.panel(g, MARKS.x, MARKS.y, MARKS.w, MARKS.h);
            Draw.textScaled(g, Component.translatable("ryscope.bookmarks").getString(), MARKS.x + PAD + 1, MARKS.y + PAD + 2, 0.75f, theme.c("text.muted"));
            MARKS.render(g, theme, mx, my, animate);
        }
        if (RECENT.visible()) {
            theme.panel(g, RECENT.x, RECENT.y, RECENT.w, RECENT.h);
            Draw.textScaled(g, Component.translatable("ryscope.recent").getString(), RECENT.x + PAD + 1, RECENT.y + PAD + 2, 0.75f, theme.c("text.muted"));
            RECENT.render(g, theme, mx, my, false);
        }
    }

    /** Подсказки, линза и уведомления: поверх всего экрана. */
    public static void renderTop(Screen screen, GuiGraphics g, int mx, int my, float partialTick) {
        if (!active(screen)) return;
        Theme theme = ThemeManager.get();
        if (screen instanceof AbstractContainerScreen<?> acs) renderHighlight(acs, g);
        Stack own = ownStackAt(mx, my);
        if (Lens.held()) {
            Stack target = own != null ? own : hovered(screen, mx, my);
            if (target != null) Lens.renderCard(g, theme, target, mx, my, screen.width, screen.height);
        } else if (own != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            own.renderTooltip(g, mx, my);
            g.pose().popPose();
        }
        renderHelp(g, theme);
        renderToast(screen, g);
    }

    /** Шпаргалка по языку поиска: пока поле в фокусе и пустое. */
    private static void renderHelp(GuiGraphics g, Theme theme) {
        if (search == null || !search.isFocused() || !search.getValue().isEmpty() || !LIST.visible()) return;
        Font font = Minecraft.getInstance().font;
        List<String[]> lines = new ArrayList<>();
        int keyW = 0, textW = 0;
        for (int i = 1; i <= 10; i++) {
            String raw = Component.translatable("ryscope.help." + i).getString();
            int split = raw.indexOf(" — ");
            String[] parts = split < 0 ? new String[] {raw, ""} : new String[] {raw.substring(0, split), raw.substring(split + 3)};
            lines.add(parts);
            keyW = Math.max(keyW, Math.round(font.width(parts[0]) * 0.75f));
            textW = Math.max(textW, Math.round(font.width(parts[1]) * 0.75f));
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
        int maxText = Math.max(80, screen.width - 40);
        List<FormattedCharSequence> lines = font.split(Component.literal(toast), maxText);
        if (lines.size() > 3) lines = lines.subList(0, 3);
        int textW = 0;
        for (FormattedCharSequence line : lines) textW = Math.max(textW, font.width(line));
        int w = textW + 16, h = 6 + lines.size() * 10;
        int x = (screen.width - w) / 2, y = screen.height - 18 - h;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        theme.tooltipBox(g, x, y, w, h);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), x + 8, y + 4 + i * 10, theme.c("text.primary"), false);
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

    @Nullable
    private static Stack ownStackAt(double mx, double my) {
        Stack s = LIST.at(mx, my);
        if (s == null) s = MARKS.at(mx, my);
        if (s == null) s = RECENT.at(mx, my);
        return s;
    }

    /** Ресурс под курсором где угодно на экране: в наших панелях, в слоте контейнера, в виджете чужого мода. */
    @Nullable
    public static Stack hovered(Screen screen, double mx, double my) {
        Stack s = ownStackAt(mx, my);
        if (s != null) return s;
        if (screen instanceof RecipeScreen rs) return rs.hoveredStack((int) mx, (int) my);
        if (screen instanceof AbstractContainerScreen<?> acs) {
            Slot slot = Platform.get().hoveredSlot(acs);
            if (slot != null && slot.hasItem()) return new ItemRes(slot.getItem());
        }
        Foreign.Source foreign = Foreign.get();
        if (foreign != null) {
            try {
                return foreign.stackUnderMouse(screen, mx, my);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** Ресурс под курсором в панели списка (для модов, которые спрашивают об этом через JEI). */
    @Nullable
    public static Stack listStackUnderMouse() {
        return LIST.at(mouseX(), mouseY());
    }

    /** Показать в списке элемент с этим номером. */
    public static void revealInList(int index) {
        LIST.reveal(index);
    }

    public static boolean isOver(double mx, double my) {
        return LIST.contains(mx, my) || MARKS.contains(mx, my) || RECENT.contains(mx, my);
    }

    public static boolean mouseClicked(Screen screen, double mx, double my, int button) {
        if (!active(screen)) return false;
        EditBox box = search();
        boolean inSearch = LIST.visible() && mx >= searchRect[0] && mx < searchRect[0] + searchRect[2]
            && my >= searchRect[1] && my < searchRect[1] + searchRect[3];
        if (inSearch && button == 0 && !box.getValue().isEmpty() && mx >= searchRect[0] + searchRect[2] - 13) {
            box.setValue("");
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
            if (button == 1) box.setValue("");
            box.setFocused(true);
            if (button == 0) box.mouseClicked(Math.max(mx, box.getX()), box.getY() + 2, 0);
            return true;
        }
        if (box.isFocused()) {
            box.setFocused(false);
            saveSearch();
        }
        if (!isOver(mx, my)) return false;
        if (LIST.visible() && mx >= gearRect[0] - 2 && mx < gearRect[0] + 12 && my >= gearRect[1] - 2 && my < gearRect[1] + 12) {
            Minecraft.getInstance().setScreen(new SettingsScreen(screen));
            return true;
        }
        if (LIST.visible() && mx >= gearRect[0] - 17 && mx < gearRect[0] - 4 && my >= gearRect[1] - 2 && my < gearRect[1] + 12) {
            toggleCraftable();
            return true;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ItemStack carried = player == null ? ItemStack.EMPTY : player.containerMenu.getCarried();
        if (LIST.contains(mx, my) && !LIST.inGrid(mx, my) && LIST.scrollbarDrag(mx, my)) {
            draggingScroll = true;
            return true;
        }
        if (!carried.isEmpty() && screen instanceof AbstractContainerScreen<?>) {
            // предмет на курсоре: бросок на закладки закрепляет его, на список ничего не делает (и не выбрасывает)
            if (MARKS.contains(mx, my) || (LIST.contains(mx, my) && !MARKS.visible())) {
                if (Bookmarks.toggle(new ItemRes(carried))) toast(Component.translatable("ryscope.bookmark.added").getString());
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
        if (button == 0 && Config.get().cheatMode && player != null && player.isCreative() && LIST.at(mx, my) != null) {
            give(stack, Screen.hasShiftDown());
            return true;
        }
        if (button == 0 || button == 1) {
            RecipeScreen.open(stack, button == 0 ? Lookup.Mode.RECIPES : Lookup.Mode.USES);
        }
        return true;
    }

    public static boolean mouseReleased(Screen screen, double mx, double my, int button) {
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
            return true;
        }
        if (!active(screen)) return false;
        EditBox box = search();
        if (key == GLFW.GLFW_KEY_H && Screen.hasControlDown() && LIST.visible() && (box.isFocused() || !typingElsewhere(screen))) {
            hideCurrent(box);
            return true;
        }
        if (box.isFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                box.setFocused(false);
                saveSearch();
                return true;
            }
            if (key == GLFW.GLFW_KEY_TAB) autocomplete(box);
            else if (key == GLFW.GLFW_KEY_PAGE_DOWN) LIST.pageBy(1);
            else if (key == GLFW.GLFW_KEY_PAGE_UP) LIST.pageBy(-1);
            else box.keyPressed(key, scan, mods);
            // пока поле в фокусе, клавиши не должны доходить до экрана (иначе «E» закроет инвентарь)
            return true;
        }
        if (Keys.matches(Keys.SEARCH, key, scan) && LIST.visible()) {
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
        if (key == GLFW.GLFW_KEY_PAGE_DOWN && LIST.visible()) {
            LIST.pageBy(1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP && LIST.visible()) {
            LIST.pageBy(-1);
            return true;
        }
        double mx = mouseX(), my = mouseY();
        Stack stack = hovered(screen, mx, my);
        if (stack == null) return false;
        if (key == GLFW.GLFW_KEY_C && Screen.hasControlDown()) {
            copy(stack.id().toString());
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
        EditBox box = search();
        if (box.isFocused()) {
            box.charTyped(c, mods);
            return true;
        }
        return false;
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

    private static void toggleCraftable() {
        EditBox box = search();
        String v = box.getValue();
        if (v.contains("craftable")) box.setValue(v.replace("craftable", "").replaceAll("\\s+", " ").trim());
        else box.setValue((v.trim() + " craftable").trim());
    }

    public static void copy(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        toast(Component.translatable("ryscope.copied", text).getString());
    }

    private static void toggleBookmark(Stack stack) {
        boolean added = Bookmarks.toggle(stack);
        toast(Component.translatable(added ? "ryscope.bookmark.added" : "ryscope.bookmark.removed").getString());
    }

    private static void saveSearch() {
        String value = search == null ? "" : search.getValue();
        if (!value.equals(lastQuery)) {
            lastQuery = value;
            Config.get().lastSearch = value;
            Config.save();
        }
    }

    public static void onScreenClosed() {
        if (search != null) search.setFocused(false);
        draggingScroll = false;
        dragIndex = -1;
        saveSearch();
    }

    public static void setSearch(String text) {
        search().setValue(text);
    }

    private static void give(Stack stack, boolean full) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ItemStack item = stack.item();
        if (player == null || mc.gameMode == null || item.isEmpty()) return;
        ItemStack copy = item.copyWithCount(full ? item.getMaxStackSize() : 1);
        int free = player.getInventory().getFreeSlot();
        if (free < 0 || free >= 36) {
            toast(Component.translatable("ryscope.cheat.full").getString());
            return;
        }
        player.getInventory().setItem(free, copy);
        mc.gameMode.handleCreativeModeItemAdd(copy, free < 9 ? 36 + free : free);
    }

    static double mouseX() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
    }

    static double mouseY() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
    }
}
