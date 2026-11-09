package works.repka.ryscope.model;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.ui.theme.Theme;

/** Один рецепт, готовый к показу. Реализации: свои раскладки и обёртка над рецептом JEI. */
public interface RecipeView {
    @Nullable
    ResourceLocation id();

    int width();

    int height();

    /** Нужна ли светлая ванильная подложка (рецепты чужих модов рисуются в расчёте на неё). */
    default boolean needsPlate() {
        return false;
    }

    /** Координаты мыши даются в координатах экрана, x/y это левый верхний угол рецепта. */
    void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick);

    /** Ресурс под курсором или null. */
    @Nullable
    Stack stackAt(int x, int y, int mouseX, int mouseY);

    /** Подсказка рецепта, если курсор над чем-то, кроме слота. Возвращает true, если подсказка нарисована. */
    default boolean renderTooltip(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        return false;
    }

    List<Ing> inputs();

    List<Stack> outputs();

    default void tick() {}

    /** Длительность одного выполнения в тиках; 0, если рецепт ручной или время неизвестно. */
    default int ticks() {
        return 0;
    }

    /** Энергия на одно выполнение в FE; 0, если не тратится или неизвестна. */
    default long energy() {
        return 0;
    }

    /** Это рецепт, который игрок выполняет сам (крафт, плавка, машина), а не источник вроде дропа с моба. */
    default boolean isCrafting() {
        return true;
    }

    /** Можно ли перенести рецепт в слоты открытого экрана. */
    default Transfer transfer(AbstractContainerScreen<?> screen, boolean max, boolean simulate) {
        return Transfer.UNSUPPORTED;
    }

    /** Результат попытки переноса. */
    record Transfer(Status status, @Nullable String message) {
        public enum Status { OK, MISSING, UNSUPPORTED }

        public static final Transfer UNSUPPORTED = new Transfer(Status.UNSUPPORTED, null);
        public static final Transfer OK = new Transfer(Status.OK, null);

        public static Transfer missing(String message) {
            return new Transfer(Status.MISSING, message);
        }
    }
}
