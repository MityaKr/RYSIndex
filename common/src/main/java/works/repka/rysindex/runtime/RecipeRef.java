package works.repka.rysindex.runtime;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.RecipeView;
import works.repka.rysindex.model.Stack;

/**
 * Ссылка на рецепт, которая переживает перезаход: id категории и id рецепта. Категория может быть неизвестна
 * (так задаёт её сборка), тогда подходит рецепт с этим id в любой категории.
 */
public record RecipeRef(@Nullable ResourceLocation category, ResourceLocation recipe) {
    @Nullable
    public static RecipeRef of(Category category, RecipeView recipe) {
        return recipe.id() == null ? null : new RecipeRef(category.id, recipe.id());
    }

    public boolean matches(Category c, RecipeView r) {
        return recipe.equals(r.id()) && (category == null || category.equals(c.id));
    }

    /**
     * Рецепт по ссылке. Сначала среди рецептов результата (это дёшево и даёт тот же вид, что в карточке),
     * потом во всей категории. @return группа из одного рецепта или null, если рецепта больше нет
     */
    @Nullable
    public Lookup.Group resolve(@Nullable Stack output) {
        if (output != null) {
            Lookup.Group found = find(Lookup.find(output, Lookup.Mode.RECIPES));
            if (found != null) return found;
        }
        if (category == null) return null;
        Category own = Indexer.snapshot().category(category);
        if (own != null) {
            Lookup.Group found = find(Lookup.category(own));
            if (found != null) return found;
        }
        Foreign.Source foreign = Foreign.get();
        if (foreign == null) return null;
        try {
            return find(foreign.categoryById(category));
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private Lookup.Group find(List<Lookup.Group> groups) {
        for (Lookup.Group g : groups) {
            for (RecipeView r : g.recipes()) {
                if (matches(g.category(), r)) return new Lookup.Group(g.category(), List.of(r));
            }
        }
        return null;
    }

    com.google.gson.JsonObject toJson() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        if (category != null) o.addProperty("category", category.toString());
        o.addProperty("recipe", recipe.toString());
        return o;
    }

    /** {"category": "...", "recipe": "..."} или просто строка с id рецепта. */
    @Nullable
    static RecipeRef fromJson(@Nullable com.google.gson.JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
            return id == null ? null : new RecipeRef(null, id);
        }
        if (!e.isJsonObject() || !e.getAsJsonObject().has("recipe")) return null;
        com.google.gson.JsonObject o = e.getAsJsonObject();
        ResourceLocation id = ResourceLocation.tryParse(o.get("recipe").getAsString());
        ResourceLocation cat = o.has("category") ? ResourceLocation.tryParse(o.get("category").getAsString()) : null;
        return id == null ? null : new RecipeRef(cat, id);
    }
}
