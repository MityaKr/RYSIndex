package works.repka.ryscope.jei;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Foreign;
import works.repka.ryscope.runtime.Indexer;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.Snapshot;

/**
 * Мост к установленному JEI. JEI сам загружает плагины модов и строит свои индексы;
 * мост задаёт ему вопросы («рецепты для X», «список ингредиентов») и переводит ответы в модель RYScope.
 */
public final class JeiBridge implements Foreign.Source {
    final IJeiRuntime runtime;
    private final Map<ResourceLocation, JeiCategory> categories = new HashMap<>();
    /** Тип «жидкость» у JEI на этом загрузчике, или null, если узнать его не удалось. */
    @SuppressWarnings("rawtypes")
    private mezz.jei.api.ingredients.IIngredientTypeWithSubtypes fluidType;
    @SuppressWarnings("rawtypes")
    private mezz.jei.api.helpers.IPlatformFluidHelper fluidHelper;

    public JeiBridge(IJeiRuntime runtime) {
        this.runtime = runtime;
        try {
            fluidHelper = runtime.getJeiHelpers().getPlatformFluidHelper();
            fluidType = fluidHelper.getFluidIngredientType();
        } catch (Throwable t) {
            RYScope.LOG.debug("Тип жидкостей JEI недоступен: {}", t.toString());
        }
        try {
            runtime.getIngredientVisibility().registerListener(new IIngredientVisibility.IListener() {
                @Override
                public <V> void onIngredientVisibilityChanged(ITypedIngredient<V> ingredient, boolean visible) {
                    Indexer.request("JEI изменил видимость ингредиентов");
                }
            });
        } catch (Throwable t) {
            RYScope.LOG.debug("Подписка на видимость ингредиентов JEI недоступна: {}", t.toString());
        }
    }

    /** Сам JEI: нужен сверке и замерам в автотесте. */
    public IJeiRuntime runtime() {
        return runtime;
    }

    @Override
    public String name() {
        return "JEI";
    }

    // --- преобразование ---

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Stack fromTyped(ITypedIngredient<?> typed) {
        Optional<ItemStack> item = typed.getItemStack();
        if (item.isPresent()) return item.get().isEmpty() ? null : new ItemRes(item.get());
        Stack fluid = fluidFrom(typed);
        if (fluid != null) return fluid;
        try {
            IIngredientManager im = runtime.getIngredientManager();
            IIngredientType type = typed.getType();
            return new JeiRes(typed, im.getIngredientHelper(type), im.getIngredientRenderer(type));
        } catch (Throwable t) {
            return null;
        }
    }

    public Optional<ITypedIngredient<?>> toTyped(Stack stack) {
        if (stack instanceof JeiRes<?> jr) return Optional.of(jr.typed);
        if (stack instanceof works.repka.ryscope.model.FluidRes fluid) return fluidTo(fluid);
        ItemStack item = stack.item();
        if (item.isEmpty()) return Optional.empty();
        Optional<ITypedIngredient<ItemStack>> typed = runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, item);
        return typed.map(t -> t);
    }

    /**
     * Жидкость JEI превращается в нашу жидкость. Иначе одна и та же вода была бы двумя разными ресурсами:
     * из списка JEI и из наших раскладок, и рецепты одной не находились бы по другой
     * (так и было: найдено сверкой на сборке RepYS).
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private Stack fluidFrom(ITypedIngredient<?> typed) {
        if (fluidType == null || typed.getType() != fluidType) return null;
        try {
            // жидкость с компонентами остаётся ресурсом JEI: у нашей жидкости компонентов нет, и разные зелья слились бы в одно
            if (!works.repka.ryscope.platform.Platform.get().plainFluid(typed.getIngredient())) return null;
            Object base = fluidType.getBase(typed.getIngredient());
            if (!(base instanceof net.minecraft.world.level.material.Fluid fluid) || fluid == net.minecraft.world.level.material.Fluids.EMPTY) return null;
            return new works.repka.ryscope.model.FluidRes(fluid, works.repka.ryscope.platform.Platform.get().fluidAmount(typed.getIngredient()));
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<ITypedIngredient<?>> fluidTo(works.repka.ryscope.model.FluidRes fluid) {
        if (fluidType == null || fluidHelper == null) return Optional.empty();
        try {
            Object ingredient = fluidHelper.create(fluid.fluid.builtInRegistryHolder(), Math.max(1, fluid.amount));
            Optional<ITypedIngredient<?>> typed = runtime.getIngredientManager().createTypedIngredient((IIngredientType) fluidType, ingredient);
            return typed;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    // --- список ресурсов ---

    @Override
    public List<Stack> stacks() {
        IIngredientManager im = runtime.getIngredientManager();
        IIngredientVisibility visibility = runtime.getIngredientVisibility();
        List<Stack> out = new ArrayList<>();
        for (ItemStack s : im.getAllItemStacks()) {
            if (s.isEmpty()) continue;
            try {
                if (!visibility.isIngredientVisible(VanillaTypes.ITEM_STACK, s)) continue;
            } catch (Throwable ignored) {
                // если видимость определить не удалось, показываем
            }
            out.add(new ItemRes(s));
        }
        for (IIngredientType<?> type : im.getRegisteredIngredientTypes()) {
            if (type == VanillaTypes.ITEM_STACK) continue;
            addAll(out, im, visibility, type);
        }
        return out;
    }

    private <V> void addAll(List<Stack> out, IIngredientManager im, IIngredientVisibility visibility, IIngredientType<V> type) {
        try {
            java.util.Set<Object> fluids = new java.util.HashSet<>();
            for (V ingredient : im.getAllIngredients(type)) {
                Optional<ITypedIngredient<V>> typed = im.createTypedIngredient(type, ingredient);
                if (typed.isEmpty() || !visibility.isIngredientVisible(typed.get())) continue;
                Stack fluid = fluidFrom(typed.get());
                if (fluid != null) {
                    if (fluids.add(fluid.key())) out.add(fluid.withAmount(1000));
                    continue;
                }
                out.add(new JeiRes<>(typed.get(), im.getIngredientHelper(type), im.getIngredientRenderer(type)));
            }
        } catch (Throwable t) {
            RYScope.LOG.warn("Ингредиенты JEI типа {} пропущены: {}", type.getUid(), t.toString());
        }
    }

    @Override
    public boolean isHidden(Stack stack) {
        return false;
    }

    // --- рецепты ---

    private JeiCategory category(IRecipeCategory<?> c) {
        return categories.computeIfAbsent(c.getRecipeType().getUid(), id -> {
            JeiCategory jc = new JeiCategory(c, 100 + categories.size());
            try {
                runtime.getRecipeManager().createRecipeCatalystLookup(c.getRecipeType()).get().forEach(t -> {
                    Stack s = fromTyped(t);
                    if (s != null) jc.stations.add(s);
                });
            } catch (Throwable ignored) {
                // станции необязательны
            }
            return jc;
        });
    }

    @Override
    public List<Lookup.Group> find(Stack stack, Lookup.Mode mode, Snapshot snapshot) {
        Optional<ITypedIngredient<?>> typed = toTyped(stack);
        if (typed.isEmpty()) return List.of();
        IFocusFactory ff = runtime.getJeiHelpers().getFocusFactory();
        List<IFocus<?>> focuses = new ArrayList<>();
        if (mode == Lookup.Mode.RECIPES) {
            focuses.add(ff.createFocus(RecipeIngredientRole.OUTPUT, typed.get()));
        } else {
            focuses.add(ff.createFocus(RecipeIngredientRole.INPUT, typed.get()));
            focuses.add(ff.createFocus(RecipeIngredientRole.CATALYST, typed.get()));
        }
        return collect(focuses, snapshot);
    }

    private List<Lookup.Group> collect(List<IFocus<?>> focuses, Snapshot snapshot) {
        IRecipeManager rm = runtime.getRecipeManager();
        IFocusGroup group = runtime.getJeiHelpers().getFocusFactory().createFocusGroup(focuses);
        List<Lookup.Group> out = new ArrayList<>();
        List<IRecipeCategory<?>> cats = focuses.isEmpty()
            ? rm.createRecipeCategoryLookup().get().toList()
            : rm.createRecipeCategoryLookup().limitFocus(focuses).get().toList();
        for (IRecipeCategory<?> c : cats) {
            try {
                if (replaced(c, snapshot)) continue;
                List<RecipeView> views = views(rm, c, focuses, group, snapshot);
                if (views.isEmpty()) continue;
                // категории, которые есть и у нас, сливаются в одну вкладку
                Category own = snapshot.category(c.getRecipeType().getUid());
                out.add(new Lookup.Group(own != null ? own : category(c), views));
            } catch (Throwable t) {
                RYScope.LOG.warn("Категория JEI {} пропущена: {}", c.getRecipeType().getUid(), t.toString());
            }
        }
        return out;
    }

    /**
     * Категории JEI больше не прячутся за раскладками RYScope: оригинальный вид разработчиков мода важнее,
     * наши раскладки показываются только для рецептов, которых в JEI нет (см. {@code Lookup.preferOriginals}).
     */
    private static boolean replaced(IRecipeCategory<?> c, Snapshot snapshot) {
        return false;
    }

    private <T> List<RecipeView> views(IRecipeManager rm, IRecipeCategory<T> c, List<IFocus<?>> focuses, IFocusGroup group, Snapshot snapshot) {
        var lookup = rm.createRecipeLookup(c.getRecipeType());
        if (!focuses.isEmpty()) lookup = lookup.limitFocus(focuses);
        boolean overlaps = snapshot.hasCategory(c.getRecipeType().getUid());
        java.util.Set<ResourceLocation> ownIds = overlaps ? nativeIds(snapshot, c.getRecipeType().getUid()) : java.util.Set.of();
        List<RecipeView> views = new ArrayList<>();
        lookup.get().forEach(recipe -> {
            if (overlaps) {
                // рецепт уже показан нашей раскладкой в этой же категории
                ResourceLocation id;
                try {
                    id = c.getRegistryName(recipe);
                } catch (Throwable t) {
                    id = null;
                }
                if (id != null && ownIds.contains(id)) return;
            }
            RecipeView structure = StructureRecipeView.of(recipe);
            views.add(structure != null ? structure : new JeiRecipeView<>(this, c, recipe, group));
        });
        return views;
    }

    private Snapshot idsFor;
    private final Map<ResourceLocation, java.util.Set<ResourceLocation>> idsByCategory = new HashMap<>();

    /**
     * Id рецептов, которые наша раскладка показывает в категории с этим id. Сверять нужно именно по категории:
     * JEI показывает один рецепт в нескольких категориях (помол Create есть и в «Помоле», и в «Дроблении»),
     * и сверка по всем нашим рецептам сразу теряла такие повторы (найдено сверкой на сборке RepYS).
     */
    public synchronized java.util.Set<ResourceLocation> nativeIds(Snapshot snapshot, ResourceLocation uid) {
        if (idsFor != snapshot) {
            idsByCategory.clear();
            idsFor = snapshot;
        }
        return idsByCategory.computeIfAbsent(uid, u -> {
            java.util.Set<ResourceLocation> ids = new java.util.HashSet<>();
            Category own = snapshot.category(u);
            if (own != null) {
                // рецепты наших JSON-раскладок не вытесняют оригинал JEI
                for (RecipeView r : snapshot.recipes(own)) {
                    if (r.id() != null && !(r instanceof works.repka.ryscope.content.NativeRecipe n && n.fromLayout)) ids.add(r.id());
                }
            }
            return ids;
        });
    }

    @Override
    public List<Category> categories(Snapshot snapshot) {
        List<Category> out = new ArrayList<>();
        runtime.getRecipeManager().createRecipeCategoryLookup().get().forEach(c -> {
            if (!snapshot.hasCategory(c.getRecipeType().getUid()) && !replaced(c, snapshot)) out.add(category(c));
        });
        return out;
    }

    private record Ghost(mezz.jei.api.gui.handlers.IGhostIngredientHandler.Target<?> target, Object ingredient) {}

    private List<mezz.jei.api.gui.handlers.IGhostIngredientHandler<Screen>> ghostHandlers = List.of();
    private List<Ghost> ghosts = List.of();

    @Override
    public boolean ghostStart(Screen screen, Stack stack) {
        ghostEnd();
        Optional<ITypedIngredient<?>> typed = toTyped(stack);
        if (typed.isEmpty()) return false;
        List<mezz.jei.api.gui.handlers.IGhostIngredientHandler<Screen>> handlers = runtime.getScreenHelper().getGhostIngredientHandlers(screen);
        List<Ghost> found = new ArrayList<>();
        for (var handler : handlers) {
            try {
                collectGhosts(handler, screen, typed.get(), found);
            } catch (Throwable t) {
                RYScope.LOG.debug("Цели перетаскивания не получены: {}", t.toString());
            }
        }
        ghostHandlers = handlers;
        ghosts = found;
        return !found.isEmpty();
    }

    private static <I> void collectGhosts(mezz.jei.api.gui.handlers.IGhostIngredientHandler<Screen> handler, Screen screen, ITypedIngredient<I> typed, List<Ghost> out) {
        for (var target : handler.getTargetsTyped(screen, typed, true)) out.add(new Ghost(target, typed.getIngredient()));
    }

    @Override
    public List<int[]> ghostAreas() {
        List<int[]> out = new ArrayList<>();
        for (Ghost g : ghosts) {
            var a = g.target().getArea();
            out.add(new int[] {a.getX(), a.getY(), a.getWidth(), a.getHeight()});
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean ghostDrop(double x, double y) {
        for (Ghost g : ghosts) {
            if (!g.target().getArea().contains((int) x, (int) y)) continue;
            try {
                ((mezz.jei.api.gui.handlers.IGhostIngredientHandler.Target<Object>) g.target()).accept(g.ingredient());
                return true;
            } catch (Throwable t) {
                RYScope.LOG.warn("Предмет не положен в фильтр: {}", t.toString());
                return false;
            }
        }
        return false;
    }

    @Override
    public void ghostEnd() {
        for (var handler : ghostHandlers) {
            try {
                handler.onComplete();
            } catch (Throwable ignored) {
                // чужой обработчик
            }
        }
        ghostHandlers = List.of();
        ghosts = List.of();
    }

    @Override
    public Object subtypeUid(Stack stack) {
        if (!(stack instanceof ItemRes item)) return null;
        try {
            var helper = runtime.getIngredientManager().getIngredientHelper(VanillaTypes.ITEM_STACK);
            return helper.hasSubtypes(item.stack) ? helper.getUid(item.stack, mezz.jei.api.ingredients.subtypes.UidContext.Recipe) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String displayModId(Stack stack) {
        if (!(stack instanceof ItemRes item)) return null;
        try {
            return runtime.getIngredientManager().getIngredientHelper(VanillaTypes.ITEM_STACK).getDisplayModId(item.stack);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public List<Lookup.Group> categoryById(ResourceLocation id) {
        IFocusGroup empty = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        List<Lookup.Group> out = new ArrayList<>();
        for (IRecipeCategory<?> c : runtime.getRecipeManager().createRecipeCategoryLookup().get().toList()) {
            if (!c.getRecipeType().getUid().equals(id)) continue;
            List<RecipeView> views = views(runtime.getRecipeManager(), c, List.of(), empty, Snapshot.EMPTY);
            if (!views.isEmpty()) out.add(new Lookup.Group(category(c), views));
        }
        return out;
    }

    @Override
    public List<Lookup.Group> category(Category category) {
        if (!(category instanceof JeiCategory jc)) return List.of();
        IFocusGroup empty = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        List<RecipeView> views = views(runtime.getRecipeManager(), jc.category, List.of(), empty, Snapshot.EMPTY);
        return views.isEmpty() ? List.of() : List.of(new Lookup.Group(jc, views));
    }

    @Nullable
    public Category categoryByUid(ResourceLocation uid) {
        JeiCategory cached = categories.get(uid);
        if (cached != null) return cached;
        return runtime.getRecipeManager().createRecipeCategoryLookup().get()
            .filter(c -> c.getRecipeType().getUid().equals(uid)).findFirst().map(c -> (Category) category(c)).orElse(null);
    }

    @Override
    public java.util.Set<ResourceLocation> hiddenRecipeIds() {
        java.util.Set<ResourceLocation> out = new java.util.HashSet<>();
        IRecipeManager rm = runtime.getRecipeManager();
        java.util.Set<ResourceLocation> visibleCategories = new java.util.HashSet<>();
        rm.createRecipeCategoryLookup().get().forEach(c -> visibleCategories.add(c.getRecipeType().getUid()));
        rm.createRecipeCategoryLookup().includeHidden().get().forEach(c ->
            collectHidden(rm, c, !visibleCategories.contains(c.getRecipeType().getUid()), out));
        return out;
    }

    private <T> void collectHidden(IRecipeManager rm, IRecipeCategory<T> c, boolean categoryHidden, java.util.Set<ResourceLocation> out) {
        try {
            if (!categoryHidden) {
                long all = rm.createRecipeLookup(c.getRecipeType()).includeHidden().get().count();
                long visible = rm.createRecipeLookup(c.getRecipeType()).get().count();
                if (all == visible) return;
            }
            java.util.Set<ResourceLocation> visibleIds = new java.util.HashSet<>();
            if (!categoryHidden) {
                rm.createRecipeLookup(c.getRecipeType()).get().forEach(r -> {
                    ResourceLocation id = c.getRegistryName(r);
                    if (id != null) visibleIds.add(id);
                });
            }
            rm.createRecipeLookup(c.getRecipeType()).includeHidden().get().forEach(r -> {
                ResourceLocation id = c.getRegistryName(r);
                if (id != null && !visibleIds.contains(id)) out.add(id);
            });
        } catch (Throwable ignored) {
            // категория без имён рецептов
        }
    }

    // --- чужие экраны ---

    @Override
    public List<int[]> exclusionAreas(Screen screen) {
        List<int[]> out = new ArrayList<>();
        runtime.getScreenHelper().getGuiExclusionAreas(screen).forEach((Rect2i r) -> {
            if (r != null) out.add(new int[] {r.getX(), r.getY(), r.getWidth(), r.getHeight()});
        });
        return out;
    }

    @Override
    public Stack stackUnderMouse(Screen screen, double mouseX, double mouseY) {
        return runtime.getScreenHelper().getClickableIngredientUnderMouse(screen, mouseX, mouseY)
            .findFirst().map(IClickableIngredient::getTypedIngredient).map(t -> fromTyped(t)).orElse(null);
    }
}
