package works.repka.ryscope.platform;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

/** Тонкий слой платформы: всё, чего нет в чистом Minecraft. Реализуется модулем загрузчика. */
public interface Platform {
    boolean isModLoaded(String modId);

    /** Отображаемое имя мода по namespace; если мод не найден, возвращается namespace. */
    String modName(String namespace);

    Path configDir();

    /** Границы окна контейнера: left, top, width, height. */
    int[] screenBounds(AbstractContainerScreen<?> screen);

    @Nullable
    Slot hoveredSlot(AbstractContainerScreen<?> screen);

    /** Идентификаторы всех загруженных модов (для автодополнения @mod). */
    List<String> modIds();

    boolean isDevEnvironment();

    /** Имена наборов миксинов, изменивших класс (для диагностики: какой мод сломал чужой класс). */
    List<String> mixinsOn(String className);

    /** Иконка жидкости 16×16 с её цветом. */
    void renderFluid(net.minecraft.client.gui.GuiGraphics g, net.minecraft.world.level.material.Fluid fluid, int x, int y);

    net.minecraft.network.chat.Component fluidName(net.minecraft.world.level.material.Fluid fluid);

    final class Holder {
        private static Platform instance;

        private Holder() {}

        public static void set(Platform platform) {
            instance = platform;
        }
    }

    static Platform get() {
        if (Holder.instance == null) {
            throw new IllegalStateException("RYScope platform is not initialised");
        }
        return Holder.instance;
    }
}
