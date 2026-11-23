package works.repka.rysindex.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.runtime.Config;
import works.repka.rysindex.runtime.Foreign;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.UiScale;

/**
 * Нажимаемые места окон, которые моды объявили для JEI: стрелка печи, верстака, варочной стойки, машин модов.
 * Интерфейс JEI выключен, поэтому клик и подсказку обслуживаем сами.
 */
public final class ClickAreas {
    private ClickAreas() {}

    /** Свой интерфейс JEI тогда работает сам, и клик обработался бы дважды. */
    private static boolean enabled(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        return Config.get().replaceJeiInterface && screen instanceof AbstractContainerScreen<?> acs
            && mc.player != null && acs.getMenu().getCarried().isEmpty();
    }

    /** Клик в единицах GUI игры. @return true, если клик пришёлся на такое место и открыл рецепты */
    public static boolean click(Screen screen, double x, double y, int button) {
        if (button != 0 || !enabled(screen)) return false;
        Foreign.Source foreign = Foreign.get();
        if (foreign == null) return false;
        try {
            Foreign.ClickArea area = foreign.clickArea(screen, x, y);
            if (area == null) return false;
            area.click();
            return true;
        } catch (Throwable t) {
            RYSIndex.LOG.debug("A clickable window area failed: {}", t.toString());
            return false;
        }
    }

    /** Подсказка «Показать рецепты» над таким местом. Координаты мыши в единицах интерфейса. */
    public static void renderTooltip(Screen screen, GuiGraphics g, int mx, int my) {
        if (!enabled(screen) || Overlay.isOver(mx, my) || Lens.held()) return;
        if (screen instanceof AbstractContainerScreen<?> acs && works.repka.rysindex.platform.Platform.get().hoveredSlot(acs) != null) return;
        Foreign.Source foreign = Foreign.get();
        if (foreign == null) return;
        List<Component> lines;
        try {
            Foreign.ClickArea area = foreign.clickArea(screen, UiScale.toGui(mx), UiScale.toGui(my));
            if (area == null) return;
            lines = area.tooltip();
        } catch (Throwable t) {
            return;
        }
        if (lines.isEmpty()) return;
        List<String> text = new ArrayList<>(lines.size());
        for (Component line : lines) text.add(line.getString());
        Kit.tooltip(g, ThemeManager.get(), text, mx, my);
    }
}
