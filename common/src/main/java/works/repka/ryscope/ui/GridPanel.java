package works.repka.ryscope.ui;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.ui.theme.Theme;

/**
 * Прокручиваемая сетка ресурсов: общая основа списка предметов и закладок.
 * Рисуются только видимые ячейки, прокрутка плавная.
 */
public final class GridPanel {
    public int x, y, w, h;
    public int gridX, gridY, gridW, gridH;
    public int cols, cell;
    private float scroll, target;
    private List<Stack> items = List.of();
    private long lastFrame;

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
        scroll = target = 0;
    }

    public int rows() {
        return cols <= 0 ? 0 : (items.size() + cols - 1) / cols;
    }

    private float maxScroll() {
        return Math.max(0, rows() * cell - gridH);
    }

    private void clampScroll() {
        target = Mth.clamp(target, 0, maxScroll());
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    public void scrollBy(double wheel) {
        target = Mth.clamp(target - (float) wheel * cell * 2.5f, 0, maxScroll());
    }

    /** Прокручивает так, чтобы элемент с этим номером оказался в верхней строке. */
    public void reveal(int index) {
        if (cols <= 0) return;
        target = scroll = Mth.clamp((index / cols) * cell, 0, maxScroll());
    }

    public void pageBy(int pages) {
        target = Mth.clamp(target + pages * Math.max(cell, gridH - cell), 0, maxScroll());
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
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        clampScroll();
        if (animate) {
            scroll += (target - scroll) * Math.min(1f, dt * 18f);
            if (Math.abs(target - scroll) < 0.3f) scroll = target;
        } else {
            scroll = target;
        }

        int hovered = indexAt(mx, my);
        int first = (int) (scroll / cell);
        int last = (int) ((scroll + gridH) / cell);
        int pad = (cell - 16) / 2;
        float itemScale = cell > 20 ? (cell - 4) / 16f : 1f;
        g.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        for (int row = first; row <= last; row++) {
            int cy = gridY + row * cell - Math.round(scroll);
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                if (idx >= items.size()) break;
                int cx = gridX + col * cell;
                if (idx == hovered) theme.hover(g, cx, cy, cell);
                Stack s = items.get(idx);
                try {
                    if (itemScale != 1f) {
                        g.pose().pushPose();
                        g.pose().translate(cx + 2, cy + 2, 0);
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
        if (max > 0) {
            int trackX = gridX + gridW + 1;
            float ratio = gridH / (float) (rows() * cell);
            int thumbH = Math.max(12, Math.round(gridH * ratio));
            int thumbY = gridY + Math.round((gridH - thumbH) * (scroll / max));
            Draw.round(g, trackX, thumbY, 2, thumbH, 1f, theme.c("scroll.thumb"));
        }
    }

    /** Перетаскивание полосы прокрутки и клики по ней. */
    public boolean scrollbarDrag(double mx, double my) {
        float max = maxScroll();
        if (max <= 0) return false;
        if (mx < gridX + gridW || mx >= x + w) return false;
        float t = Mth.clamp((float) (my - gridY) / Math.max(1, gridH), 0f, 1f);
        target = scroll = t * max;
        return true;
    }
}
