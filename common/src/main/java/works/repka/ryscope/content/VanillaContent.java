package works.repka.ryscope.content;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.ItemLike;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.runtime.Snapshot;

/** Рецепты самой игры со своими раскладками. Работает и без JEI. */
public final class VanillaContent {
    public static final ResourceLocation CRAFTING = ResourceLocation.withDefaultNamespace("crafting");
    public static final ResourceLocation SMELTING = ResourceLocation.withDefaultNamespace("smelting");
    public static final ResourceLocation BLASTING = ResourceLocation.withDefaultNamespace("blasting");
    public static final ResourceLocation SMOKING = ResourceLocation.withDefaultNamespace("smoking");
    public static final ResourceLocation CAMPFIRE = ResourceLocation.withDefaultNamespace("campfire_cooking");
    public static final ResourceLocation STONECUTTING = ResourceLocation.withDefaultNamespace("stonecutting");
    public static final ResourceLocation SMITHING = ResourceLocation.withDefaultNamespace("smithing");

    private static final int PITCH = 20;

    private VanillaContent() {}

    public static void provide(Snapshot.Builder b, RecipeManager manager, RegistryAccess registries) {
        Category crafting = b.category(category(CRAFTING, "block.minecraft.crafting_table", 0, Items.CRAFTING_TABLE));
        Category smelting = b.category(category(SMELTING, "block.minecraft.furnace", 1, Items.FURNACE));
        Category blasting = b.category(category(BLASTING, "block.minecraft.blast_furnace", 2, Items.BLAST_FURNACE));
        Category smoking = b.category(category(SMOKING, "block.minecraft.smoker", 3, Items.SMOKER));
        Category campfire = b.category(category(CAMPFIRE, "block.minecraft.campfire", 4, Items.CAMPFIRE, Items.SOUL_CAMPFIRE));
        Category stonecutting = b.category(category(STONECUTTING, "block.minecraft.stonecutter", 5, Items.STONECUTTER));
        Category smithing = b.category(category(SMITHING, "block.minecraft.smithing_table", 6, Items.SMITHING_TABLE));

        for (RecipeHolder<CraftingRecipe> holder : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            try {
                NativeRecipe r = crafting(holder, registries);
                if (r != null) b.recipe(crafting, r);
            } catch (Exception e) {
                RYScope.LOG.debug("Пропущен рецепт {}: {}", holder.id(), e.toString());
            }
        }
        cooking(b, smelting, manager.getAllRecipesFor(RecipeType.SMELTING), registries);
        cooking(b, blasting, manager.getAllRecipesFor(RecipeType.BLASTING), registries);
        cooking(b, smoking, manager.getAllRecipesFor(RecipeType.SMOKING), registries);
        cooking(b, campfire, manager.getAllRecipesFor(RecipeType.CAMPFIRE_COOKING), registries);

        for (RecipeHolder<StonecutterRecipe> holder : manager.getAllRecipesFor(RecipeType.STONECUTTING)) {
            StonecutterRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(registries);
            if (result.isEmpty() || recipe.getIngredients().isEmpty()) continue;
            NativeRecipe r = sized(NativeRecipe.Kind.SINGLE, holder, 61, 22);
            r.input(Ing.of(recipe.getIngredients().get(0)), 0, 4);
            r.flow(22, 11, 9, "");
            r.output(ItemRes.of(result), 39, 0, true);
            b.recipe(stonecutting, r);
        }

        for (RecipeHolder<SmithingRecipe> holder : manager.getAllRecipesFor(RecipeType.SMITHING)) {
            if (!(holder.value() instanceof SmithingTransformRecipe recipe)) continue;
            try {
                Ingredient template = field(recipe, "template");
                Ingredient base = field(recipe, "base");
                Ingredient addition = field(recipe, "addition");
                ItemStack result = recipe.getResultItem(registries);
                if (result.isEmpty()) continue;
                NativeRecipe r = sized(NativeRecipe.Kind.SMITHING, holder, 93, 22);
                r.input(Ing.of(template), 0, 4);
                r.input(Ing.of(base), DESIGN_PITCH, 4);
                r.input(Ing.of(addition), DESIGN_PITCH * 2, 4);
                r.flow(54, 11, 9, "");
                r.output(ItemRes.of(result), 71, 0, true);
                b.recipe(smithing, r);
            } catch (ReflectiveOperationException e) {
                RYScope.LOG.debug("Кузнечный рецепт {} недоступен: {}", holder.id(), e.toString());
            }
        }
    }

    private static Category category(ResourceLocation id, String titleKey, int order, ItemLike... stations) {
        Category c = new Category(id, Component.translatable(titleKey), ItemRes.of(new ItemStack(stations[0])), order);
        for (ItemLike s : stations) c.stations.add(ItemRes.of(new ItemStack(s)));
        return c;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    private static NativeRecipe crafting(RecipeHolder<CraftingRecipe> holder, RegistryAccess registries) {
        CraftingRecipe recipe = holder.value();
        ItemStack result = recipe.getResultItem(registries);
        if (result.isEmpty()) return null;
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null;
        int w, h;
        boolean shapeless;
        if (recipe instanceof ShapedRecipe shaped) {
            w = shaped.getWidth();
            h = shaped.getHeight();
            shapeless = false;
        } else if (recipe instanceof ShapelessRecipe) {
            int n = ingredients.size();
            w = n <= 4 ? Math.min(2, n) : 3;
            if (n == 1) w = 1;
            h = (n + w - 1) / w;
            shapeless = true;
        } else {
            return null;
        }
        if (w > 3 || h > 3) return null;
        NativeRecipe r = sized(NativeRecipe.Kind.CRAFTING, holder, 93, 46);
        r.gridW = w;
        r.gridH = h;
        r.shapeless = shapeless;
        for (int i = 0; i < w * h; i++) {
            Ing ing = i < ingredients.size() ? Ing.of(ingredients.get(i)) : Ing.EMPTY;
            r.input(ing, (i % w) * DESIGN_PITCH, (i / w) * DESIGN_PITCH);
        }
        // пустые ячейки сетки 3×3, чтобы рецепт читался как верстак
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (col >= w || row >= h) r.slots.add(new NativeRecipe.SlotW(Ing.EMPTY, col * DESIGN_PITCH, row * DESIGN_PITCH, false, false));
            }
        }
        r.flow(54, 23, 9, "");
        if (shapeless) r.shapelessMark(55, 10);
        r.output(ItemRes.of(result), 71, 12, true);
        return r;
    }

    private static <T extends AbstractCookingRecipe> void cooking(Snapshot.Builder b, Category category, List<RecipeHolder<T>> recipes, RegistryAccess registries) {
        for (RecipeHolder<T> holder : recipes) {
            T recipe = holder.value();
            ItemStack result = recipe.getResultItem(registries);
            if (result.isEmpty() || recipe.getIngredients().isEmpty()) continue;
            NativeRecipe r = sized(NativeRecipe.Kind.COOKING, holder, 96, 28);
            r.ticks = recipe.getCookingTime();
            r.input(Ing.of(recipe.getIngredients().get(0)), 0, 0);
            r.flame(1, 16);
            String time = Component.translatable("ryscope.recipe.seconds", trim(recipe.getCookingTime() / 20f)).getString();
            String text = time;
            if (recipe.getExperience() > 0) {
                text += " · " + Component.translatable("ryscope.recipe.xp", trim(recipe.getExperience())).getString();
            }
            r.flow(20, 11, 48, text);
            r.output(ItemRes.of(result), 74, 0, true);
            b.recipe(category, r);
        }
    }

    private static final int DESIGN_PITCH = 16;

    /** Рецепт с размерами слотов из макета. */
    private static NativeRecipe sized(NativeRecipe.Kind kind, RecipeHolder<?> holder, int width, int height) {
        NativeRecipe r = new NativeRecipe(kind, holder, width, height);
        // макет задан под слоты 14 и 22 с шагом 16; на экране слот 20 и предмет 16 без уменьшения,
        // как значок в заголовке карточки, шаг 22
        r.designSlot = 14;
        r.designLarge = 22;
        r.layoutScale = 22f / DESIGN_PITCH;
        r.slotSize = 20;
        r.largeSize = 20;
        return r;
    }

    private static String trim(float v) {
        if (v == Math.rint(v)) return Integer.toString((int) v);
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
