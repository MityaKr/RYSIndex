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
        return preferOriginals(groups);
    }

    /**
     * Наша раскладка рецепта мода уступает виду JEI: если JEI показывает рецепт с тем же id (в любой категории),
     * наш вариант убирается. Свой остаётся только там, где у рецепта нет оригинального вида.
     */
    private static List<Group> preferOriginals(Map<Category, List<RecipeView>> groups) {
        java.util.Set<net.minecraft.resources.ResourceLocation> original = new java.util.HashSet<>();
        for (List<RecipeView> list : groups.values()) {
            for (RecipeView r : list) if (!(r instanceof works.repka.ryscope.content.NativeRecipe) && r.id() != null) original.add(r.id());
        }
        List<Group> out = new ArrayList<>();
        groups.forEach((c, list) -> {
            List<RecipeView> kept = new ArrayList<>(list.size());
            for (RecipeView r : list) {
                if (r instanceof works.repka.ryscope.content.NativeRecipe n && n.fromLayout && r.id() != null && original.contains(r.id())) continue;
                kept.add(r);
            }
            if (!kept.isEmpty()) out.add(new Group(c, kept));
        });
        return out;
    }

    public static List<Group> category(Category category) {
        Snapshot snap = Indexer.snapshot();
        List<RecipeView> own = snap.recipes(category);
        Foreign.Source foreign = Foreign.get();
        boolean layout = false;
        for (RecipeView r : own) layout |= r instanceof works.repka.ryscope.content.NativeRecipe n && n.fromLayout;
        if (layout && foreign != null) {
            try {
                // категория раскладки: оригинальные рецепты JEI плюс наши только там, где оригинала нет
                Map<Category, List<RecipeView>> groups = new LinkedHashMap<>();
                groups.put(category, new ArrayList<>(own));
                for (Group g : foreign.categoryById(category.id)) groups.get(category).addAll(g.recipes());
                return preferOriginals(groups);
            } catch (Throwable t) {
                RYScope.LOG.warn("Источник {} не отдал категорию {}", foreign.name(), category.id, t);
            }
        }
        if (!own.isEmpty()) return List.of(new Group(category, own));
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
