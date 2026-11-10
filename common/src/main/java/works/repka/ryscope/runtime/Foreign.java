package works.repka.ryscope.runtime;

import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Stack;

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

        /** Все рецепты категории источника с этим id, без сверки с нашими: для просмотра категории целиком. */
        default List<Lookup.Group> categoryById(net.minecraft.resources.ResourceLocation id) {
            return List.of();
        }

        /** Рецепты, скрытые в источнике (например, скриптом сборки): свои раскладки их тоже не показывают. */
        default java.util.Set<net.minecraft.resources.ResourceLocation> hiddenRecipeIds() {
            return java.util.Set.of();
        }
    }

    private static volatile Source source;

    private Foreign() {}

    @Nullable
    public static Source get() {
        return source;
    }

    public static void set(@Nullable Source s) {
        source = s;
        Indexer.request(s == null ? "источник отключён" : "готов " + s.name());
    }
}
