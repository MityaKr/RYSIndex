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
                if (all.isEmpty()) continue;
                List<RecipeView> group = groups.computeIfAbsent(c, k -> new ArrayList<>());
                if (group.isEmpty()) {
                    group.addAll(all);
                } else {
                    // станция сама бывает ингредиентом (верстак в рецепте крафтера): без повторов
                    java.util.Set<RecipeView> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    seen.addAll(group);
                    for (RecipeView r : all) if (seen.add(r)) group.add(r);
                }
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
        List<Group> out = preferOriginals(groups);
        out.sort(java.util.Comparator.comparingInt(g -> rank(g.category(), stack, mode, snap)));
        return out;
    }

    /**
     * Порядок вкладок: у машины в «Использованиях» сначала то, что она делает; затем верстак;
     * дальше машины и процессы в своём порядке; служебное (теги, квесты) и лут (сундуки, зачарования,
     * мобы, генерация мира) в самом конце.
     */
    private static int rank(Category c, Stack stack, Mode mode, Snapshot snap) {
        String ns = c.id.getNamespace(), path = c.id.getPath();
        if (mode == Mode.USES && isStation(c, stack, snap)) return 0;
        if (path.equals("crafting") && (ns.equals("minecraft") || ns.equals(RYScope.MOD_ID))) return 1;
        if (LOOT_NAMESPACES.contains(ns) || LOOT_PATHS.contains(path) || path.endsWith("_loot") || path.endsWith("_drops")) return 9;
        if (path.startsWith("tag_recipes") || path.equals("information") || path.equals("info") || ns.equals("ftbquests") || ns.equals("rysquests")) return 8;
        if (path.equals("trades")) return 7;
        return 5;
    }

    /** Лут и сведения о мире: всегда в конце. Машины вроде «loot_fabricator» сюда не попадают. */
    private static final java.util.Set<String> LOOT_NAMESPACES = java.util.Set.of("jeresources", "lootjs", "jei_biome", "jei_structures");
    private static final java.util.Set<String> LOOT_PATHS = java.util.Set.of("loot", "loot_crate", "chest_loot", "mob_drops", "dungeon",
        "enchantment", "villager", "worldgen", "plant", "mob");

    private static boolean isStation(Category c, Stack stack, Snapshot snap) {
        for (Stack st : c.stations) if (st.key().equals(stack.key())) return true;
        for (Category own : snap.stationOf(stack)) if (own.id.equals(c.id)) return true;
        return false;
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

    /**
     * Рецепт подходит к ресурсу с учётом подтипа: контроллер «Улья-матки» не путается с остальными контроллерами,
     * книга Patchouli с другими книгами. Ингредиент без данных («любой контроллер») подходит всем подтипам.
     */
    private static boolean matches(RecipeView recipe, Stack stack, Mode mode) {
        Foreign.Source foreign = Foreign.get();
        Object want = foreign == null ? null : foreign.subtypeUid(stack);
        // JEI не знает подтипа (мод его не зарегистрировал), но у предмета свои данные: сверяем данные целиком
        boolean byData = want == null && stack instanceof works.repka.ryscope.model.ItemRes focus && !focus.stack.getComponentsPatch().isEmpty();
        if (mode == Mode.RECIPES) {
            for (Stack o : recipe.outputs()) {
                if (o.key().equals(stack.key()) && sameKind(o, stack, want, byData, foreign)) return true;
            }
            return false;
        }
        for (Ing in : recipe.inputs()) {
            if (!in.accepts(stack)) continue;
            if (want == null && !byData) return true;
            for (Stack o : in.options) {
                if (!o.key().equals(stack.key())) continue;
                if (generic(o) || sameKind(o, stack, want, byData, foreign)) return true;
            }
        }
        return false;
    }

    private static boolean sameKind(Stack candidate, Stack focus, Object want, boolean byData, Foreign.Source foreign) {
        if (want != null) return want.equals(foreign.subtypeUid(candidate));
        if (byData) return candidate.same(focus);
        return true;
    }

    /** Ингредиент без своих данных: подходит любому подтипу предмета. */
    private static boolean generic(Stack option) {
        return option instanceof works.repka.ryscope.model.ItemRes item && item.stack.getComponentsPatch().isEmpty();
    }

    /** Число рецептов без построения полного списка: для линзы и подсказок. */
    public static int countNative(Stack stack, Mode mode) {
        Snapshot snap = Indexer.snapshot();
        return (mode == Mode.RECIPES ? snap.producing(stack) : snap.consuming(stack)).size();
    }
}
