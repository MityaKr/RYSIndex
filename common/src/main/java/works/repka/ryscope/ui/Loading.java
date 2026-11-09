package works.repka.ryscope.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import works.repka.ryscope.ui.theme.Theme;

/** Индикатор «идёт расчёт» внутри окна: окна дерева, плана и сравнения открываются сразу, а считают по частям. */
final class Loading {
    private Loading() {}

    static void render(GuiGraphics g, Theme theme, int px, int py, int pw, int ph, String titleKey, int examined) {
        Font font = Minecraft.getInstance().font;
        String title = Component.translatable(titleKey).getString();
        String progress = Component.translatable("ryscope.tree.loading.progress", examined).getString();
        int cy = py + ph / 2 - 12;
        Draw.textCentered(g, title, px + pw / 2, cy, theme.c("text.body"));
        int bw = Math.min(160, pw - 40), bx = px + (pw - bw) / 2;
        Draw.round(g, bx, cy + 14, bw, 2, 1f, theme.c("slot.bg"));
        // бегущий отрезок: сколько всего предстоит просмотреть, заранее неизвестно
        float phase = (System.currentTimeMillis() % 1200L) / 1200f;
        int segment = bw / 4, sx = bx + Math.round((bw - segment) * (phase < 0.5f ? phase * 2 : 2 - phase * 2));
        Draw.round(g, sx, cy + 14, segment, 2, 1f, theme.c("accent"));
        Draw.textScaled(g, progress, px + pw / 2f - font.width(progress) * 0.375f, cy + 21, 0.75f, theme.c("text.muted"));
    }
}
