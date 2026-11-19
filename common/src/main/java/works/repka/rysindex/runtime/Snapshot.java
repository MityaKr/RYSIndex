package works.repka.rysindex.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import works.repka.rysindex.core.search.SearchEntry;
import works.repka.rysindex.core.search.SearchIndex;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.RecipeView;
import works.repka.rysindex.model.Stack;

/** Неизменяемый снимок всего, что знает мод: список ресурсов, категории, рецепты и индексы по ним. */
public final class Snapshot {
    public static final Snapshot EMPTY = new Builder().build();

    public record Entry(Category category, RecipeView recipe) {}

    public final List<Stack> stacks;
    public final SearchIndex index;
    public final List<Category> categories;
    private final Map<ResourceLocation, List<RecipeView>> byCategory;
    private final Map<Object, List<Entry>> byOutput;
    private final Map<Object, List<Entry>> byInput;
    private final Map<Object, List<Category>> byStation;
    public final Set<ResourceLocation> recipeIds;
    public final int recipeCount;
    public final long buildMillis;

    private Snapshot(Builder b) {
        this.stacks = List.copyOf(b.stacks);
        this.index = new SearchIndex(b.entries);
        this.categories = List.copyOf(b.categories.values());
        this.byCategory = b.byCategory;
        this.byOutput = b.byOutput;
        this.byInput = b.byInput;
        this.byStation = b.byStation;
        this.recipeIds = b.recipeIds;
        this.recipeCount = b.recipeCount;
        this.buildMillis = b.buildMillis;
    }

    public List<RecipeView> recipes(Category category) {
        return byCategory.getOrDefault(category.id, List.of());
    }

    public Category category(ResourceLocation id) {
        for (Category c : categories) if (c.id.equals(id)) return c;
        return null;
    }

    public List<Entry> producing(Stack stack) {
        return byOutput.getOrDefault(stack.key(), List.of());
    }

    public List<Entry> consuming(Stack stack) {
        return byInput.getOrDefault(stack.key(), List.of());
    }

    public List<Category> stationOf(Stack stack) {
        return byStation.getOrDefault(stack.key(), List.of());
    }

    public boolean hasCategory(ResourceLocation id) {
        return byCategory.containsKey(id);
    }

    public static final class Builder {
        private final List<Stack> stacks = new ArrayList<>();
        private final List<SearchEntry> entries = new ArrayList<>();
        private final Map<ResourceLocation, Category> categories = new LinkedHashMap<>();
        private final Map<ResourceLocation, List<RecipeView>> byCategory = new HashMap<>();
        private final Map<Object, List<Entry>> byOutput = new HashMap<>();
        private final Map<Object, List<Entry>> byInput = new HashMap<>();
        private final Map<Object, List<Category>> byStation = new HashMap<>();
        private final Set<ResourceLocation> recipeIds = new HashSet<>();
        private int recipeCount;
        public long buildMillis;
        private Set<ResourceLocation> hidden = Set.of();

        /** Рецепты с этими id не попадают в снимок. */
        public void hide(Set<ResourceLocation> ids) {
            this.hidden = ids;
        }

        public void addStack(Stack stack, SearchEntry entry) {
            stacks.add(stack);
            entries.add(entry);
        }

        public int stackCount() {
            return stacks.size();
        }

        /** Регистрирует категорию; если категория с таким id уже есть, возвращает существующую. */
        public Category category(Category category) {
            Category existing = categories.putIfAbsent(category.id, category);
            if (existing != null) return existing;
            byCategory.put(category.id, new ArrayList<>());
            for (Stack station : category.stations) {
                byStation.computeIfAbsent(station.key(), k -> new ArrayList<>()).add(category);
            }
            return category;
        }

        public void recipe(Category category, RecipeView recipe) {
            if (recipe.id() != null && hidden.contains(recipe.id())) return;
            category = category(category);
            byCategory.get(category.id).add(recipe);
            recipeCount++;
            if (recipe.id() != null) recipeIds.add(recipe.id());
            Entry entry = new Entry(category, recipe);
            Set<Object> seen = new HashSet<>();
            for (Stack out : recipe.outputs()) {
                if (seen.add(out.key())) byOutput.computeIfAbsent(out.key(), k -> new ArrayList<>()).add(entry);
            }
            seen.clear();
            for (Ing in : recipe.inputs()) {
                for (Stack option : in.options) {
                    if (seen.add(option.key())) byInput.computeIfAbsent(option.key(), k -> new ArrayList<>()).add(entry);
                }
            }
        }

        public Snapshot build() {
            return new Snapshot(this);
        }
    }
}
