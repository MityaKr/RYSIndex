package works.repka.ryscope.neoforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.jei.JeiBridge;
import works.repka.ryscope.jei.RyscopeJeiPlugin;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Indexer;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.Snapshot;

/**
 * Часть автотеста, которая разговаривает с JEI напрямую: сверка «что знает JEI и что показываем мы»
 * и замеры родного интерфейса JEI. Вынесено отдельно, чтобы автотест работал и без JEI.
 */
final class AutoTestJei {
    private AutoTestJei() {}

    private static IJeiRuntime runtime() {
        JeiBridge bridge = RyscopeJeiPlugin.bridge();
        return bridge == null ? null : bridge.runtime();
    }

    // --- сверка ---

    /** Сколько рецептов категории знает JEI и сколько из них уже есть в наших раскладках (по id рецепта). */
    private static <T> long[] count(IRecipeManager rm, IRecipeCategory<T> c, List<IFocus<?>> focuses, Set<ResourceLocation> ownIds) {
        long[] out = new long[2];
        var lookup = rm.createRecipeLookup(c.getRecipeType());
        if (!focuses.isEmpty()) lookup = lookup.limitFocus(focuses);
        lookup.get().forEach(recipe -> {
            out[0]++;
            ResourceLocation id;
            try {
                id = c.getRegistryName(recipe);
            } catch (Throwable t) {
                id = null;
            }
            if (id != null && ownIds.contains(id)) out[1]++;
        });
        return out;
    }

    static void audit() {
        IJeiRuntime rt = runtime();
        if (rt == null) {
            RYScope.LOG.info("[audit] JEI не подключён, сверять не с чем");
            return;
        }
        IRecipeManager rm = rt.getRecipeManager();
        Snapshot snap = Indexer.snapshot();
        Set<ResourceLocation> replaced = works.repka.ryscope.content.JsonLayout.replacedCategories();

        // 1. категории и рецепты
        int total = 0, viaJei = 0, merged = 0, replacedCount = 0;
        long jeiRecipes = 0, lost = 0;
        List<String> details = new ArrayList<>();
        Map<String, int[]> byMod = new TreeMap<>();
        for (IRecipeCategory<?> c : rm.createRecipeCategoryLookup().get().toList()) {
            total++;
            ResourceLocation uid = c.getRecipeType().getUid();
            long[] n;
            try {
                // слитая категория сверяется с нашими рецептами этой же категории, заменённая со всеми нашими
                n = count(rm, c, List.of(), snap.hasCategory(uid) ? RyscopeJeiPlugin.bridge().nativeIds(snap, uid) : snap.recipeIds);
            } catch (Throwable t) {
                details.add("  " + uid + ": JEI не отдал рецепты (" + t + ")");
                continue;
            }
            jeiRecipes += n[0];
            int[] mod = byMod.computeIfAbsent(uid.getNamespace(), k -> new int[2]);
            mod[0]++;
            mod[1] += (int) n[0];
            if (snap.hasCategory(uid)) {
                merged++;
                Category own = snap.category(uid);
                details.add("  слита с нашей " + uid + ": у JEI " + n[0] + ", в нашей раскладке " + snap.recipes(own).size()
                    + ", добавляем из JEI " + (n[0] - n[1]));
            } else if (replaced.contains(uid)) {
                replacedCount++;
                lost += n[0] - n[1];
                details.add("  заменена нашей " + uid + ": у JEI " + n[0] + ", покрыто нашими " + n[1] + ", НЕ ПОКАЗАНО " + (n[0] - n[1]));
            } else {
                viaJei++;
            }
        }
        RYScope.LOG.info("[audit] категорий JEI: {} (показаны через JEI {}, слиты с нашими {}, заменены нашими {}); рецептов у JEI {}; не показано {}",
            total, viaJei, merged, replacedCount, jeiRecipes, lost);
        details.forEach(line -> RYScope.LOG.info("[audit] {}", line));
        int[] malum = byMod.get("malum");
        RYScope.LOG.info("[audit] Malum: категорий {}, рецептов {}", malum == null ? 0 : malum[0], malum == null ? 0 : malum[1]);

        // 2. предметы и прочие ресурсы по модам: у JEI (видимые) и у нас
        Map<String, int[]> resources = new HashMap<>();
        int jeiVisible = 0;
        for (IIngredientType<?> type : rt.getIngredientManager().getRegisteredIngredientTypes()) jeiVisible += countType(rt, type, resources);
        for (Stack s : snap.stacks) resources.computeIfAbsent(s.namespace(), k -> new int[2])[1]++;
        List<String> fewer = new ArrayList<>();
        resources.forEach((ns, n) -> {
            if (n[1] < n[0]) fewer.add(ns + " (JEI " + n[0] + ", у нас " + n[1] + ")");
        });
        java.util.Collections.sort(fewer);
        RYScope.LOG.info("[audit] ресурсов: у JEI видимых {}, у нас {}; модов, где у нас меньше: {} {}", jeiVisible, snap.stacks.size(), fewer.size(),
            fewer.size() > 20 ? fewer.subList(0, 20) + " …" : fewer);

        // 3. выборочно по предметам: рецепты JEI для предмета против того, что показывает наше окно
        int step = Math.max(1, snap.stacks.size() / 800), checked = 0, worse = 0;
        List<String> examples = new ArrayList<>();
        int fluidsChecked = 0;
        for (int i = 0; i < snap.stacks.size(); i++) {
            Stack stack = snap.stacks.get(i);
            // предметы выборочно, жидкости все: их мало, а сопоставление с JEI у них отдельное
            boolean fluid = stack instanceof works.repka.ryscope.model.FluidRes;
            if (!fluid && i % step != 0) continue;
            if (fluid) fluidsChecked++;
            Optional<ITypedIngredient<?>> typed = RyscopeJeiPlugin.bridge().toTyped(stack);
            if (typed.isEmpty()) continue;
            checked++;
            try {
                List<IFocus<?>> focus = List.of(rt.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.OUTPUT, typed.get()));
                Map<ResourceLocation, Integer> ours = new HashMap<>();
                for (Lookup.Group g : Lookup.find(stack, Lookup.Mode.RECIPES)) ours.merge(g.category().id, g.recipes().size(), Integer::sum);
                for (IRecipeCategory<?> c : rm.createRecipeCategoryLookup().limitFocus(focus).get().toList()) {
                    ResourceLocation uid = c.getRecipeType().getUid();
                    long[] n = count(rm, c, focus, snap.recipeIds);
                    long shown = replaced.contains(uid) && !snap.hasCategory(uid) ? n[1] : ours.getOrDefault(uid, 0);
                    if (shown < n[0]) {
                        worse++;
                        if (examples.size() < 15) examples.add(stack.id() + " в " + uid + ": у JEI " + n[0] + ", у нас " + shown);
                    }
                }
            } catch (Throwable t) {
                if (examples.size() < 15) examples.add(stack.id() + ": ошибка сверки " + t);
            }
        }
        RYScope.LOG.info("[audit] выборочно по предметам: проверено {} (из них жидкостей {}), случаев «у нас рецептов меньше, чем у JEI»: {}", checked, fluidsChecked, worse);
        examples.forEach(line -> RYScope.LOG.info("[audit]   {}", line));
    }

    private static <T> int countType(IJeiRuntime rt, IIngredientType<T> type, Map<String, int[]> byNamespace) {
        int visible = 0;
        var helper = rt.getIngredientManager().getIngredientHelper(type);
        for (T ingredient : rt.getIngredientManager().getAllIngredients(type)) {
            try {
                if (!rt.getIngredientVisibility().isIngredientVisible(type, ingredient)) continue;
                visible++;
                byNamespace.computeIfAbsent(helper.getResourceLocation(ingredient).getNamespace(), k -> new int[2])[0]++;
            } catch (Throwable ignored) {
                // ингредиент без имени
            }
        }
        return visible;
    }

    // --- замеры родного интерфейса JEI ---

    /** Включает и выключает панели JEI его внутренним переключателем (тем же, что Ctrl+O). */
    static void overlay(boolean on) {
        try {
            Object state = Class.forName("mezz.jei.common.Internal").getMethod("getClientToggleState").invoke(null);
            Class.forName("mezz.jei.common.config.IClientToggleState").getMethod("setOverlayEnabled", boolean.class).invoke(state, on);
        } catch (Throwable t) {
            RYScope.LOG.error("[perf] переключатель панелей JEI недоступен: {}", t.toString());
        }
    }

    /** Поиск JEI считается в главном потоке: меряем установку запроса вместе с получением результата. */
    static String search(String query) {
        IJeiRuntime rt = runtime();
        if (rt == null) return "JEI не подключён";
        long t0 = System.nanoTime();
        rt.getIngredientFilter().setFilterText(query);
        int found = rt.getIngredientFilter().getFilteredIngredients(VanillaTypes.ITEM_STACK).size();
        long micros = (System.nanoTime() - t0) / 1000;
        return micros + " мкс в главном потоке, найдено предметов " + found;
    }

    static void openRecipes(ItemStack stack, boolean uses) {
        IJeiRuntime rt = runtime();
        if (rt == null) return;
        var factory = rt.getJeiHelpers().getFocusFactory();
        List<IFocus<?>> focuses = new ArrayList<>();
        if (uses) {
            focuses.add(factory.createFocus(RecipeIngredientRole.INPUT, VanillaTypes.ITEM_STACK, stack));
            focuses.add(factory.createFocus(RecipeIngredientRole.CATALYST, VanillaTypes.ITEM_STACK, stack));
        } else {
            focuses.add(factory.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack));
        }
        rt.getRecipesGui().show(focuses);
    }

    /** Сколько категорий и рецептов JEI знает для предмета: чтобы сравнивать окна на одинаковом объёме. */
    static String volume(ItemStack stack, boolean uses) {
        IJeiRuntime rt = runtime();
        if (rt == null) return "";
        var factory = rt.getJeiHelpers().getFocusFactory();
        List<IFocus<?>> focuses = new ArrayList<>();
        if (uses) {
            focuses.add(factory.createFocus(RecipeIngredientRole.INPUT, VanillaTypes.ITEM_STACK, stack));
            focuses.add(factory.createFocus(RecipeIngredientRole.CATALYST, VanillaTypes.ITEM_STACK, stack));
        } else {
            focuses.add(factory.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack));
        }
        IRecipeManager rm = rt.getRecipeManager();
        int categories = 0;
        long recipes = 0;
        for (IRecipeCategory<?> c : rm.createRecipeCategoryLookup().limitFocus(focuses).get().toList()) {
            categories++;
            recipes += rm.createRecipeLookup(c.getRecipeType()).limitFocus(focuses).get().count();
        }
        return "категорий " + categories + ", рецептов " + recipes;
    }
}
