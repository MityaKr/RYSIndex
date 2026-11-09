package works.repka.ryscope.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRuntimeRegistration;
import mezz.jei.api.runtime.IBookmarkOverlay;
import mezz.jei.api.runtime.IIngredientFilter;
import mezz.jei.api.runtime.IIngredientListOverlay;
import mezz.jei.api.runtime.IJeiFeatures;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Config;
import works.repka.ryscope.runtime.Foreign;
import works.repka.ryscope.runtime.Indexer;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.SearchService;
import works.repka.ryscope.ui.Overlay;
import works.repka.ryscope.ui.RecipeScreen;

/**
 * Плагин JEI. Его находит и вызывает сам JEI, поэтому при отсутствии JEI этот класс не загружается вовсе.
 * Делает две вещи: подменяет интерфейс JEI нашим и отдаёт ядру готовый рантайм JEI как источник содержимого.
 */
@JeiPlugin
public final class RyscopeJeiPlugin implements IModPlugin {
    private static JeiBridge bridge;

    /** Мост к запущенному JEI или null. */
    public static JeiBridge bridge() {
        return bridge;
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "jei_bridge");
    }

    @Override
    public void configureJei(IJeiFeatures features) {
        if (!Config.get().replaceJeiInterface) return;
        // штатный способ JEI: его список, поиск и окно рецептов не создаются, плагины модов продолжают работать
        features.disableJeiGui();
        features.disableInventoryEffectRendererGuiHandler();
    }

    @Override
    public void registerRuntime(IRuntimeRegistration registration) {
        if (!Config.get().replaceJeiInterface) return;
        registration.setIngredientListOverlay(new ListOverlay());
        registration.setBookmarkOverlay(new BookmarkOverlay());
        registration.setRecipesGui(new RecipesGui());
        registration.setIngredientFilter(new Filter());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiBridge created = new JeiBridge(runtime);
        bridge = created;
        Runnable connect = () -> {
            if (bridge != created) return;
            Foreign.set(created);
            RYScope.LOG.info("Мост JEI подключён");
        };
        // при фоновой загрузке другие плагины ещё не закончили: подключаемся, когда JEI загрузится целиком
        if (works.repka.ryscope.runtime.JeiAsync.onLoaderThread()) works.repka.ryscope.runtime.JeiAsync.publishWhenLoaded(connect);
        else connect.run();
    }

    @Override
    public void onRuntimeUnavailable() {
        bridge = null;
        Foreign.set(null);
    }

    private static Optional<ITypedIngredient<?>> typed(Stack stack) {
        JeiBridge b = bridge;
        return b == null || stack == null ? Optional.empty() : b.toTyped(stack);
    }

    /** Моды спрашивают у JEI, что под курсором в списке: отвечаем состоянием нашей панели. */
    private static final class ListOverlay implements IIngredientListOverlay {
        @Override
        public Optional<ITypedIngredient<?>> getIngredientUnderMouse() {
            return typed(Overlay.listStackUnderMouse());
        }

        @Override
        public <T> T getIngredientUnderMouse(IIngredientType<T> type) {
            return getIngredientUnderMouse().flatMap(t -> t.getIngredient(type)).orElse(null);
        }

        @Override
        public boolean isListDisplayed() {
            return Overlay.active(Minecraft.getInstance().screen);
        }

        @Override
        public boolean hasKeyboardFocus() {
            return Overlay.searchFocused();
        }

        @Override
        public <T> List<T> getVisibleIngredients(IIngredientType<T> type) {
            return List.of();
        }
    }

    private static final class BookmarkOverlay implements IBookmarkOverlay {
        @Override
        public Optional<ITypedIngredient<?>> getIngredientUnderMouse() {
            return Optional.empty();
        }

        @Override
        public <T> T getIngredientUnderMouse(IIngredientType<T> type) {
            return null;
        }
    }

    /** Моды просят JEI показать рецепты (кнопки в GUI, справочники): открываем наш экран. */
    private static final class RecipesGui implements IRecipesGui {
        @Override
        public void show(List<IFocus<?>> focuses) {
            JeiBridge b = bridge;
            if (b == null) return;
            for (IFocus<?> focus : focuses) {
                Stack stack = b.fromTyped(focus.getTypedValue());
                if (stack == null) continue;
                Lookup.Mode mode = focus.getRole() == RecipeIngredientRole.OUTPUT ? Lookup.Mode.RECIPES : Lookup.Mode.USES;
                if (RecipeScreen.open(stack, mode)) return;
            }
        }

        @Override
        public void showTypes(List<RecipeType<?>> types) {
            JeiBridge b = bridge;
            if (b == null) return;
            for (RecipeType<?> type : types) {
                Category own = Indexer.snapshot().category(type.getUid());
                Category category = own != null ? own : b.categoryByUid(type.getUid());
                if (category != null && RecipeScreen.openCategory(category)) return;
            }
        }

        @Override
        public <T> void showRecipes(IRecipeCategory<T> category, List<T> recipes, List<IFocus<?>> focuses) {
            showTypes(List.of(category.getRecipeType()));
        }

        @Override
        public <T> Optional<T> getIngredientUnderMouse(IIngredientType<T> type) {
            Screen screen = Minecraft.getInstance().screen;
            if (!(screen instanceof RecipeScreen rs)) return Optional.empty();
            return typed(rs.hoveredStackAtMouse()).flatMap(t -> t.getIngredient(type));
        }

        @Override
        public Optional<Screen> getParentScreen() {
            return Optional.empty();
        }
    }

    private static final class Filter implements IIngredientFilter {
        @Override
        public void setFilterText(String text) {
            Overlay.setSearch(text);
        }

        @Override
        public String getFilterText() {
            return SearchService.text();
        }

        @Override
        public <T> List<T> getFilteredIngredients(IIngredientType<T> type) {
            List<T> out = new ArrayList<>();
            for (Stack s : SearchService.results().stacks) {
                typed(s).flatMap(t -> t.getIngredient(type)).ifPresent(out::add);
            }
            return out;
        }
    }
}
