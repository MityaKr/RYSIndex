package works.repka.ryscope.stage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

/**
 * Описание стадии в виде, пригодном для сервера: без клиентских классов.
 * Сервер по нему решает, какие рецепты игроку не отправлять и что не давать крафтить.
 *
 * Поля сверх клиентского формата:
 * {@code hidden} (рецепты стадии не отправляются игроку, пока она закрыта) и
 * {@code enforce} (закрытый рецепт нельзя выполнить в сетке крафта; по умолчанию true).
 */
public final class StageDef {
    public final String id;
    /** Исходный JSON: в таком виде описание уходит клиенту. */
    public final String json;
    public final String name;
    public final int order;
    @Nullable
    public final ResourceLocation advancement;
    public final boolean hidden;
    public final boolean enforce;
    private final Set<String> items = new HashSet<>();
    private final Set<String> tags = new HashSet<>();
    private final Set<String> mods = new HashSet<>();
    private final Set<String> recipes = new HashSet<>();
    private final List<Pattern> recipePatterns = new ArrayList<>();
    private final Set<String> categories = new HashSet<>();

    public StageDef(String id, JsonObject json) {
        this.id = id;
        this.json = json.toString();
        this.name = json.has("name") ? json.get("name").getAsString() : id;
        this.order = json.has("order") ? json.get("order").getAsInt() : 0;
        this.advancement = json.has("advancement") ? ResourceLocation.tryParse(json.get("advancement").getAsString()) : null;
        this.hidden = json.has("hidden") && json.get("hidden").getAsBoolean();
        this.enforce = !json.has("enforce") || json.get("enforce").getAsBoolean();
        if (json.has("items")) {
            for (JsonElement e : json.getAsJsonArray("items")) {
                String s = e.getAsString();
                if (s.startsWith("#")) tags.add(s.substring(1));
                else if (s.startsWith("@")) mods.add(s.substring(1));
                else items.add(s);
            }
        }
        if (json.has("recipes")) {
            for (JsonElement e : json.getAsJsonArray("recipes")) {
                String s = e.getAsString();
                if (s.length() > 2 && s.startsWith("/") && s.endsWith("/")) recipePatterns.add(Pattern.compile(s.substring(1, s.length() - 1)));
                else recipes.add(s);
            }
        }
        if (json.has("categories")) for (JsonElement e : json.getAsJsonArray("categories")) categories.add(e.getAsString());
    }

    public boolean ownsItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (items.contains(id.toString()) || mods.contains(id.getNamespace())) return true;
        if (!tags.isEmpty()) {
            return stack.getTags().anyMatch(t -> tags.contains(t.location().toString()));
        }
        return false;
    }

    /** Рецепт относится к стадии: по id, по типу рецепта (поле categories) или по результату. */
    public boolean ownsRecipe(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        String id = holder.id().toString();
        if (recipes.contains(id)) return true;
        for (Pattern p : recipePatterns) if (p.matcher(id).matches()) return true;
        if (!categories.isEmpty()) {
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            if (type != null && categories.contains(type.toString())) return true;
        }
        if (items.isEmpty() && tags.isEmpty() && mods.isEmpty()) return false;
        try {
            return ownsItem(holder.value().getResultItem(registries));
        } catch (Throwable t) {
            // особый рецепт без заранее известного результата
            return false;
        }
    }
}
