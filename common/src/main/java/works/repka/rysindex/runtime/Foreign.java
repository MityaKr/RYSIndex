package works.repka.rysindex.runtime;

import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.Stack;

/**
 * Внешний поставщик содержимого (мост JEI). Ядро не знает о классах JEI:
 * мост сам регистрирует себя здесь, когда рантайм JEI готов.
 */
public final class Foreign {
    public interface Source {
        String name();

        /** Все ресурсы, известные источнику, в его порядке. Вызывается из потока индексации. */
        List<Stack> stacks();

        /** Предметы, скрытые источником (например, плагинами модов): их не показываем и мы. */
        boolean isHidden(Stack stack);

        List<Lookup.Group> find(Stack stack, Lookup.Mode mode, Snapshot snapshot);

        /**
         * То же, но повторы с нашим индексом сверяются по тому, что индекс действительно нашёл для этого ресурса:
         * {@code shown} — id найденных рецептов по id категории. Рецепт с тем же id, который наш индекс не подобрал
         * (у нас один общий выход, а мод в JEI показывает свой вариант по подтипу: сундуки Sophisticated Storage
         * по породам дерева), источник показывает сам.
         */
        default List<Lookup.Group> find(Stack stack, Lookup.Mode mode, Snapshot snapshot,
                java.util.Map<net.minecraft.resources.ResourceLocation, java.util.Set<net.minecraft.resources.ResourceLocation>> shown) {
            return find(stack, mode, snapshot);
        }

        List<Category> categories(Snapshot snapshot);

        List<Lookup.Group> category(Category category);

        /** Области чужого экрана, которые нельзя перекрывать: x, y, w, h. */
        List<int[]> exclusionAreas(Screen screen);

        @Nullable
        Stack stackUnderMouse(Screen screen, double mouseX, double mouseY);

        /**
         * Перетаскивание предмета в призрачный слот чужого окна (фильтры шин AE2, фильтры Create): начать и
         * собрать цели. Координаты целей в единицах GUI игры. @return есть ли куда положить
         */
        default boolean ghostStart(Screen screen, Stack stack) {
            return false;
        }

        /** Цели текущего перетаскивания: x, y, w, h в единицах GUI игры. */
        default List<int[]> ghostAreas() {
            return List.of();
        }

        /** Отпускание в точке (единицы GUI игры): кладёт предмет в цель под курсором. */
        default boolean ghostDrop(double x, double y) {
            return false;
        }

        /** Конец перетаскивания: обработчики модов узнают, что подсветку можно убрать. */
        default void ghostEnd() {}

        /**
         * Признак подтипа предмета, как его считают моды (машина контроллера Modular Machinery, книга Patchouli,
         * чары книги), или {@code null}, если у предмета подтипов нет. Два предмета с одним id различаются им.
         */
        @Nullable
        default Object subtypeUid(Stack stack) {
            return null;
        }

        /** Мод, к которому источник относит предмет при поиске по моду (книги Patchouli относятся к своему моду). */
        @Nullable
        default String displayModId(Stack stack) {
            return null;
        }

        /** Все рецепты категории источника с этим id, без сверки с нашими: для просмотра категории целиком. */
        default List<Lookup.Group> categoryById(net.minecraft.resources.ResourceLocation id) {
            return List.of();
        }

        /** Рецепты, скрытые в источнике (например, скриптом сборки): свои раскладки их тоже не показывают. */
        default java.util.Set<net.minecraft.resources.ResourceLocation> hiddenRecipeIds() {
            return java.util.Set.of();
        }

        /**
         * Нажимаемое место окна под курсором, которое моды объявили для JEI: стрелка печи, верстака, машины.
         * Координаты в единицах GUI игры. @return null, если курсор не над таким местом
         */
        @Nullable
        default ClickArea clickArea(Screen screen, double x, double y) {
            return null;
        }

        /** Другие имена предмета, которые моды дали ему для поиска (старые названия Mekanism). Из потока индексации. */
        default List<String> aliases(Stack stack) {
            return List.of();
        }

        /**
         * Скрыть ресурс из списка или вернуть его, как Ctrl+клик в режиме правки JEI: запись в общий список скрытого
         * источника. @param allVariants все варианты предмета, а не только этот @return скрыт ли теперь; null, если
         * источник так не умеет
         */
        @Nullable
        default Boolean toggleHidden(Stack stack, boolean allVariants) {
            return null;
        }

        /**
         * Метка ресурса для чата, которую источник у получателей превращает в ссылку с подсказкой и рецептами
         * (у JEI вида [JEI:v1:тип;uid]). @return null, если источник так не умеет
         */
        @Nullable
        default String chatMarker(Stack stack) {
            return null;
        }

        /**
         * Есть ли у категории перенос в это окно (обработчик переноса), без построения рецептов.
         * @return null, если источник категорию не знает
         */
        @Nullable
        default Boolean transfersTo(net.minecraft.world.inventory.AbstractContainerMenu menu, Category category) {
            return null;
        }

        /**
         * Положить ресурс в фильтр окна без перетаскивания (Shift+клик, quickMove в JEI).
         * @return true, если положен
         */
        default boolean quickMove(Screen screen, Stack stack) {
            return false;
        }

        /** Другие имена мода для поиска через @. Из потока индексации. */
        default java.util.Collection<String> modAliases(String modId) {
            return List.of();
        }
    }

    /** Нажимаемое место чужого окна: по клику открываются рецепты его категорий. */
    public interface ClickArea {
        /** Подсказка при наведении; пустая, если мод её выключил. */
        List<net.minecraft.network.chat.Component> tooltip();

        void click();
    }

    private static volatile Source source;

    private Foreign() {}

    @Nullable
    public static Source get() {
        return source;
    }

    public static void set(@Nullable Source s) {
        source = s;
        Indexer.request(s == null ? "source disabled" : "ready " + s.name());
    }
}
