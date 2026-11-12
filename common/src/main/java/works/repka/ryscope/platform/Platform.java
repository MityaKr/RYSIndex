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

    /** Количество в стеке жидкости загрузчика (в миллибакетах); для чужого объекта возвращает ведро. */
    long fluidAmount(Object fluidStack);

    /** Обычная жидкость без компонентов. Жидкость с компонентами (зелья Create) это отдельный ресурс на каждый набор. */
    boolean plainFluid(Object fluidStack);

    /**
     * Подсказка из готовых строк, привязанная к предмету: события подсказки загрузчика получают этот стек,
     * а не пустой, и моды (сытость, рамки) видят, над чем подсказка.
     */
    default void renderTooltip(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.gui.Font font,
                               List<net.minecraft.network.chat.Component> lines,
                               java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image,
                               net.minecraft.world.item.ItemStack stack, int x, int y) {
        g.renderTooltip(font, lines, image, x, y);
    }

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
