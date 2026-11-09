package works.repka.ryscope.ui;

import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Scroller;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.model.Stack;
import works.repka.ryscore.theme.Theme;

/**
 * Прокручиваемая сетка ресурсов: общая основа списка предметов и закладок.
 * Рисуются только видимые ячейки, прокрутка плавная.
 */
public final class GridPanel {
    public int x, y, w, h;
    public int gridX, gridY, gridW, gridH;
    public int cols, cell;
    private float scroll, push;
    private final Scroller scroller = new Scroller();
    /** Упор у края в страничном режиме. */
    private final Scroller edge = new Scroller();
    private List<Stack> items = List.of();
    /** Страничный режим: колесо и стрелки листают экранами, полосы прокрутки нет. */
    public boolean paged;
    /** Плашки с лёгким оттенком акцента: так в макете отличаются закладки. */
    public boolean accentTiles;
    private int[] tileXy = new int[0];
    private int page;

    public boolean visible() {
        return cols > 0 && h > 0;
    }

    public void hide() {
        cols = 0;
    }

    public void setItems(List<Stack> list) {
        if (list != items) {
            items = list;
            clampScroll();
        }
    }

    public List<Stack> items() {
        return items;
    }

    public void resetScroll() {
        scroller.reset();
        scroll = 0;
        page = 0;
    }

    private int rowsPerPage() {
        return Math.max(1, cell <= 0 ? 1 : gridH / cell);
    }

    public int pages() {
        return Math.max(1, (rows() + rowsPerPage() - 1) / rowsPerPage());
    }

    public int page() {
        return page;
    }

    public void setPage(int value) {
        page = Mth.clamp(value, 0, pages() - 1);
        scroll = page * rowsPerPage() * cell;
    }

    public int rows() {
        return cols <= 0 ? 0 : (items.size() + cols - 1) / cols;
    }

    private float maxScroll() {
        if (paged) return (pages() - 1) * rowsPerPage() * cell;
        return Math.max(0, rows() * cell - gridH);
    }

    private void clampScroll() {
        if (paged) return;
        scroller.limit(maxScroll());
        scroll = scroller.position();
    }

    public void scrollBy(double wheel) {
        if (paged) {
            int next = page + (wheel > 0 ? -1 : 1);
            if (next >= 0 && next < pages()) setPage(next);
            else edge.by(wheel > 0 ? -1 : 1);
            return;
        }
        scroller.by(-(float) wheel * cell * 2.5f);
    }

    /** Прокручивает так, чтобы элемент с этим номером оказался в верхней строке. */
    public void reveal(int index) {
        if (cols <= 0) return;
        if (paged) {
            setPage((index / cols) / rowsPerPage());
            return;
        }
        scroller.limit(maxScroll());
        scroller.jump((index / cols) * cell);
        scroll = scroller.position();
    }

    public void pageBy(int pages) {
        if (paged) {
            setPage(page + pages);
            return;
        }
        scroller.by(pages * Math.max(cell, gridH - cell));
    }

    public boolean contains(double mx, double my) {
        return visible() && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public boolean inGrid(double mx, double my) {
        return visible() && mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH;
    }

    public int indexAt(double mx, double my) {
        if (!inGrid(mx, my)) return -1;
        int col = (int) ((mx - gridX) / cell);
        if (paged && (int) ((my - gridY) / cell) >= rowsPerPage()) return -1;
        int row = (int) ((my - gridY + scroll) / cell);
        if (col < 0 || col >= cols) return -1;
        int idx = row * cols + col;
        return idx >= 0 && idx < items.size() ? idx : -1;
    }

    @Nullable
    public Stack at(double mx, double my) {
        int i = indexAt(mx, my);
        return i < 0 ? null : items.get(i);
    }

    public void render(GuiGraphics g, Theme theme, int mx, int my, boolean animate) {
        if (!visible()) return;
        if (paged) {
            setPage(page);
            push = edge.update(0);
        } else {
            push = 0;
            float max = maxScroll();
            if (animate) {
                scroll = scroller.update(max);
            } else {
                scroller.limit(max);
                scroller.jump(scroller.position());
                scroll = scroller.position();
            }
        }

        int hovered = indexAt(mx, my);
        int first = (int) (scroll / cell);
        int last = paged ? first + rowsPerPage() - 1 : (int) ((scroll + gridH) / cell);
        boolean tiled = cell >= 18;
        // ячейка из макета: 41 пиксель при шаге 46
        float tile = cell == 23 ? 20.5f : cell - 2f;
        int pad = (int) ((tile - 16) / 2);
        float itemScale = cell > 24 ? (cell - 7) / 16f : 1f;
        float scaledPad = (tile - 16 * itemScale) / 2f;
        g.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        if (tiled) {
            int count = 0;
            int capacity = (last - first + 1) * cols * 2;
            if (tileXy.length < capacity) tileXy = new int[capacity];
            for (int row = first; row <= last; row++) {
                int cy = gridY + row * cell - Math.round(scroll + push);
                for (int col = 0; col < cols; col++) {
                    if (row * cols + col >= items.size()) break;
                    tileXy[count * 2] = gridX + col * cell;
                    tileXy[count * 2 + 1] = cy;
                    count++;
                }
            }
            theme.cells(g, tile, tileXy, count, accentTiles ? theme.c("cell.mark.border") : theme.c("cell.border"));
        }
        for (int row = first; row <= last; row++) {
            int cy = gridY + row * cell - Math.round(scroll + push);
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                if (idx >= items.size()) break;
                int cx = gridX + col * cell;
                if (idx == hovered) {
                    if (tiled) Draw.roundBox(g, cx, cy, tile, tile, theme.m("cell.radius", 7f) / 2f, theme.c("cell.bg"), theme.c("accent"));
                    else theme.hover(g, cx, cy, cell);
                }
                Stack s = items.get(idx);
                try {
                    if (itemScale != 1f) {
                        g.pose().pushPose();
                        g.pose().translate(cx + scaledPad, cy + scaledPad, 0);
                        g.pose().scale(itemScale, itemScale, 1f);
                        s.render(g, 0, 0);
                        g.pose().popPose();
                    } else {
                        s.render(g, cx + pad, cy + pad);
                    }
                } catch (Throwable ignored) {
                    // чужой рендер не должен ронять интерфейс
                }
                if (works.repka.ryscope.runtime.Stages.any() && works.repka.ryscope.runtime.Stages.lockOf(s) != null) {
                    // закрытая стадия: приглушаем и ставим замок, а не только меняем цвет
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 220);
                    Draw.rect(g, cx + 1, cy + 1, cell - 2, cell - 2, Draw.alpha(theme.c("panel.bg"), 0.6f));
                    Draw.lock(g, cx + cell - 9, cy + cell - 10, theme.c("state.warn"));
                    g.pose().popPose();
                }
            }
        }
        g.disableScissor();

        // полоса прокрутки
        float max = maxScroll();
        if (max > 0 && !paged) {
            int trackX = gridX + gridW + 1;
            float ratio = gridH / (float) (rows() * cell);
            int thumbH = Math.max(12, Math.round(gridH * ratio));
            int thumbY = gridY + Math.round((gridH - thumbH) * Mth.clamp(scroll / max, 0f, 1f));
            Draw.round(g, trackX, thumbY, 2, thumbH, 1f, theme.c("scroll.thumb"));
        }
    }

    /** Перетаскивание полосы прокрутки и клики по ней. */
    public boolean scrollbarDrag(double mx, double my) {
        float max = maxScroll();
        if (max <= 0 || paged) return false;
        if (mx < gridX + gridW || mx >= x + w) return false;
        float t = Mth.clamp((float) (my - gridY) / Math.max(1, gridH), 0f, 1f);
        scroller.limit(max);
        scroller.jump(t * max);
        scroll = scroller.position();
        return true;
    }
}
