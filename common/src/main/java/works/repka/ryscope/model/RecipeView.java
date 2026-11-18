package works.repka.ryscope.model;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.theme.Theme;

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

    /** Ингредиент ячейки под курсором со всеми вариантами или null: для плашки «все варианты». */
    @Nullable
    default Ing ingAt(int x, int y, int mouseX, int mouseY) {
        return null;
    }

    /** Подсказка рецепта, если курсор над чем-то, кроме слота. Возвращает true, если подсказка нарисована. */
    default boolean renderTooltip(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        return false;
    }

    List<Ing> inputs();

    List<Stack> outputs();

    default void tick() {}

    /**
     * Отпустить тяжёлое, что нужно только для показа (раскладку JEI). Входы и выходы остаются; если рецепт
     * снова покажут, показ соберётся заново. Не вызывать для рецепта, который сейчас на экране.
     */
    default void release() {}

    /*
     * Ввод мышью, как и stackAt: x/y это угол рецепта в той карточке, где кликнули. Один объект рисуют в нескольких
     * местах (окно рецептов, линза, подсказка закладки), поэтому угол с последней отрисовки брать нельзя.
     */

    /** Колесо над рецептом. True, если рецепт прокрутил что-то своё и страницу листать не надо. */
    default boolean mouseScrolled(int x, int y, double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    /** Нажатие по рецепту, например на его полосу прокрутки. */
    default boolean mouseClicked(int x, int y, double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseDragged(int x, int y, double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    default void mouseReleased(int x, int y, double mouseX, double mouseY, int button) {}

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

    /** Показывать ли у рецепта кнопку «+». По умолчанию у рецептов, которые игрок выполняет сам. */
    default boolean showsTransfer() {
        return isCrafting();
    }

    /** Ключ подсказки у неактивного «+», когда рецепт здесь не применить. */
    default String transferUnavailable() {
        return "ryscope.transfer.unsupported";
    }

    /** Можно ли перенести рецепт в слоты открытого экрана. */
    default Transfer transfer(AbstractContainerScreen<?> screen, boolean max, boolean simulate) {
        return Transfer.UNSUPPORTED;
    }

    /** Результат попытки переноса. */
    record Transfer(Status status, @Nullable String message, List<net.minecraft.network.chat.Component> lines) {
        /** PARTIAL: перенести можно, но части ингредиентов нет или их придётся заказать. */
        public enum Status { OK, PARTIAL, MISSING, UNSUPPORTED }

        public static final Transfer UNSUPPORTED = new Transfer(Status.UNSUPPORTED, null, List.of());
        public static final Transfer OK = new Transfer(Status.OK, null, List.of());

        public static Transfer missing(String message) {
            return new Transfer(Status.MISSING, message, List.of());
        }

        /** Перенос возможен, подсказка объясняет, что произойдёт. */
        public static Transfer ok(List<net.minecraft.network.chat.Component> lines) {
            return new Transfer(Status.OK, null, lines);
        }

        public static Transfer partial(List<net.minecraft.network.chat.Component> lines) {
            return new Transfer(Status.PARTIAL, null, lines);
        }

        public boolean applicable() {
            return status == Status.OK || status == Status.PARTIAL;
        }
    }

    /**
     * Клик, который рецепт забирает раньше, чем окно откроет рецепты предмета под курсором
     * (например, раскрыть все варианты ячейки). @return true, если клик обработан
     */
    default boolean clickBeforeStack(double mouseX, double mouseY, int button) {
        return false;
    }
}
