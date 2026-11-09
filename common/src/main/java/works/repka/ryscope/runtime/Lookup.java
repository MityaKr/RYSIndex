package works.repka.ryscope.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;

/** Ответы на вопросы «как получить» и «где используется» по всем источникам сразу. */
public final class Lookup {
    public enum Mode { RECIPES, USES }

    public record Group(Category category, List<RecipeView> recipes) {}

    private Lookup() {}

    public static List<Group> find(Stack stack, Mode mode) {
        Snapshot snap = Indexer.snapshot();
        Map<Category, List<RecipeView>> groups = new LinkedHashMap<>();
        List<Snapshot.Entry> entries = mode == Mode.RECIPES ? snap.producing(stack) : snap.consuming(stack);
        // сначала категории в их порядке
        for (Category c : snap.categories) {
            for (Snapshot.Entry e : entries) {
                if (!e.category().equals(c)) continue;
                if (!matches(e.recipe(), stack, mode)) continue;
                groups.computeIfAbsent(c, k -> new ArrayList<>()).add(e.recipe());
            }
        }
        if (mode == Mode.USES) {
            // станция: показываем всё, что на ней делается
            for (Category c : snap.stationOf(stack)) {
                List<RecipeView> all = snap.recipes(c);
                if (!all.isEmpty()) groups.computeIfAbsent(c, k -> new ArrayList<>()).addAll(all);
            }
        }
        Foreign.Source foreign = Foreign.get();
        if (foreign != null) {
            try {
                // категории с одинаковым id из разных источников показываются одной вкладкой
                for (Group g : foreign.find(stack, mode, snap)) {
                    groups.computeIfAbsent(g.category(), k -> new ArrayList<>()).addAll(g.recipes());
                }
            } catch (Throwable t) {
                RYScope.LOG.warn("Источник {} не ответил на запрос рецептов", foreign.name(), t);
            }
        }
        List<Group> out = new ArrayList<>();
        groups.forEach((c, list) -> out.add(new Group(c, list)));
        return out;
    }

    public static List<Group> category(Category category) {
        Snapshot snap = Indexer.snapshot();
        List<RecipeView> own = snap.recipes(category);
        if (!own.isEmpty()) return List.of(new Group(category, own));
        Foreign.Source foreign = Foreign.get();
        if (foreign != null) {
            try {
                return foreign.category(category);
            } catch (Throwable t) {
                RYScope.LOG.warn("Источник {} не отдал категорию {}", foreign.name(), category.id, t);
            }
        }
        return List.of();
    }

    public static List<Category> allCategories() {
        Snapshot snap = Indexer.snapshot();
        List<Category> out = new ArrayList<>(snap.categories);
        Foreign.Source foreign = Foreign.get();
        if (foreign != null) {
            try {
                out.addAll(foreign.categories(snap));
            } catch (Throwable t) {
                RYScope.LOG.warn("Источник {} не отдал список категорий", foreign.name(), t);
            }
        }
        return out;
    }

    private static boolean matches(RecipeView recipe, Stack stack, Mode mode) {
        if (mode == Mode.RECIPES) {
            for (Stack o : recipe.outputs()) if (o.key().equals(stack.key())) return true;
            return false;
        }
        for (Ing in : recipe.inputs()) if (in.accepts(stack)) return true;
        return false;
    }

    /** Число рецептов без построения полного списка: для линзы и подсказок. */
    public static int countNative(Stack stack, Mode mode) {
        Snapshot snap = Indexer.snapshot();
        return (mode == Mode.RECIPES ? snap.producing(stack) : snap.consuming(stack)).size();
    }
}
