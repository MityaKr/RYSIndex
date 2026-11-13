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
        try {
            // моды убирают и добавляют ингредиенты во время игры (KubeJS, almostunified): об этом JEI сообщает
            // только этим слушателям, без них в списке оставались дубликаты руд и не появлялись новые предметы
            runtime.getIngredientManager().registerIngredientListener(new IIngredientManager.IIngredientListener() {
                @Override
                public <V> void onIngredientsAdded(mezz.jei.api.ingredients.IIngredientHelper<V> helper, java.util.Collection<ITypedIngredient<V>> ingredients) {
                    Indexer.request("JEI добавил ингредиенты");
                }

                @Override
                public <V> void onIngredientsRemoved(mezz.jei.api.ingredients.IIngredientHelper<V> helper, java.util.Collection<ITypedIngredient<V>> ingredients) {
                    Indexer.request("JEI убрал ингредиенты");
                }
            });
        } catch (Throwable t) {
            RYScope.LOG.debug("Подписка на список ингредиентов JEI недоступна: {}", t.toString());
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

    /** Ресурсы последнего списка, которые игрок скрыл через общий список JEI: те самые объекты, что ушли в индекс. */
    private volatile java.util.Set<Stack> playerHidden = java.util.Set.of();

    @Override
    public List<Stack> stacks() {
        IIngredientManager im = runtime.getIngredientManager();
        IIngredientVisibility visibility = runtime.getIngredientVisibility();
        List<Stack> out = new ArrayList<>();
        java.util.Set<Stack> hidden = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ItemStack s : im.getAllItemStacks()) {
            if (s.isEmpty()) continue;
            boolean byPlayer = false;
            try {
                // скрытое игроком остаётся в индексе: его прячет поиск, а при «показывать скрытые» его можно вернуть
                if (!visibility.isIngredientVisible(VanillaTypes.ITEM_STACK, s)) {
                    byPlayer = im.createTypedIngredient(VanillaTypes.ITEM_STACK, s).map(this::hiddenByPlayer).orElse(false);
                    if (!byPlayer) continue;
                }
            } catch (Throwable ignored) {
                // если видимость определить не удалось, показываем
            }
            ItemRes res = new ItemRes(s);
            out.add(res);
            if (byPlayer) hidden.add(res);
        }
        for (IIngredientType<?> type : im.getRegisteredIngredientTypes()) {
            if (type == VanillaTypes.ITEM_STACK) continue;
            addAll(out, hidden, im, visibility, type);
        }
        playerHidden = hidden;
        return out;
    }

    private <V> void addAll(List<Stack> out, java.util.Set<Stack> hidden, IIngredientManager im, IIngredientVisibility visibility, IIngredientType<V> type) {
        try {
            java.util.Set<Object> fluids = new java.util.HashSet<>();
            for (V ingredient : im.getAllIngredients(type)) {
                Optional<ITypedIngredient<V>> typed = im.createTypedIngredient(type, ingredient);
                if (typed.isEmpty()) continue;
                boolean byPlayer = !visibility.isIngredientVisible(typed.get());
                if (byPlayer && !hiddenByPlayer(typed.get())) continue;
                Stack fluid = fluidFrom(typed.get());
                if (fluid != null) {
                    if (fluids.add(fluid.key())) {
                        Stack res = fluid.withAmount(1000);
                        out.add(res);
                        if (byPlayer) hidden.add(res);
                    }
                    continue;
                }
                Stack res = new JeiRes<>(typed.get(), im.getIngredientHelper(type), im.getIngredientRenderer(type));
                out.add(res);
                if (byPlayer) hidden.add(res);
            }
        } catch (Throwable t) {
            RYScope.LOG.warn("Ингредиенты JEI типа {} пропущены: {}", type.getUid(), t.toString());
        }
    }

    /**
     * Скрыт игроком через общий список JEI (blacklist.json). Такие ресурсы остаются в индексе, чтобы их можно было
     * вернуть. Ответ по объектам последнего списка: смена blacklist.json всё равно переиндексирует список.
     */
    @Override
    public boolean isHidden(Stack stack) {
        return playerHidden.contains(stack);
    }

    private boolean hiddenByPlayer(ITypedIngredient<?> typed) {
        try {
            return runtime.getEditModeConfig().isIngredientHiddenUsingConfigFile(typed);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Boolean toggleHidden(Stack stack, boolean allVariants) {
        Optional<ITypedIngredient<?>> typed = toTyped(stack);
        if (typed.isEmpty()) return null;
        try {
            var config = runtime.getEditModeConfig();
            java.util.Set<mezz.jei.api.runtime.IEditModeConfig.HideMode> modes = config.getIngredientHiddenUsingConfigFile(typed.get());
            if (!modes.isEmpty()) {
                // скрытый любым способом возвращается целиком; слушатель видимости сам переиндексирует список
                for (var mode : modes) config.showIngredientUsingConfigFile(typed.get(), mode);
                return false;
            }
            config.hideIngredientUsingConfigFile(typed.get(), allVariants
                ? mezz.jei.api.runtime.IEditModeConfig.HideMode.WILDCARD : mezz.jei.api.runtime.IEditModeConfig.HideMode.SINGLE);
            return true;
        } catch (Throwable t) {
            RYScope.LOG.warn("JEI не скрыл ингредиент: {}", t.toString());
            return null;
        }
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

    /**
     * Сначала обработчики, которые умеют quickMove сами (как в JEI). Если таких нет, предмет идёт в первую цель
     * перетаскивания, под которой пустой слот: занятый фильтр не перезаписывается.
     */
    @Override
    public boolean quickMove(Screen screen, Stack stack) {
        Optional<ITypedIngredient<?>> typed = toTyped(stack);
        if (typed.isEmpty()) return false;
        for (var handler : runtime.getScreenHelper().getGhostIngredientHandlers(screen)) {
            try {
                if (handler.quickMove(screen, typed.get())) return true;
            } catch (Throwable t) {
                RYScope.LOG.debug("quickMove обработчика не сработал: {}", t.toString());
            }
        }
        if (!ghostStart(screen, stack)) return false;
        try {
            Ghost target = freeTarget(screen);
            if (target == null) return false;
            var a = target.target().getArea();
            return ghostDrop(a.getX() + a.getWidth() / 2.0, a.getY() + a.getHeight() / 2.0);
        } finally {
            ghostEnd();
        }
    }

    @Nullable
    private Ghost freeTarget(Screen screen) {
        if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs)) return ghosts.isEmpty() ? null : ghosts.get(0);
        int[] b = works.repka.ryscope.platform.Platform.get().screenBounds(acs);
        boolean anySlot = false;
        for (Ghost g : ghosts) {
            var a = g.target().getArea();
            net.minecraft.world.inventory.Slot under = null;
            for (net.minecraft.world.inventory.Slot slot : acs.getMenu().slots) {
                if (a.contains(b[0] + slot.x + 8, b[1] + slot.y + 8)) {
                    under = slot;
                    break;
                }
            }
            if (under == null) continue;
            anySlot = true;
            if (!under.hasItem()) return g;
        }
        // цели без слотов игры (свои виджеты мода): тогда первая
        return anySlot || ghosts.isEmpty() ? null : ghosts.get(0);
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
    public List<String> aliases(Stack stack) {
        try {
            Optional<ITypedIngredient<?>> typed = toTyped(stack);
            if (typed.isEmpty()) return List.of();
            // JEI уже переводит ключи псевдонимов на язык игры
            java.util.Collection<String> raw = runtime.getIngredientManager().getIngredientAliases(typed.get());
            if (raw.isEmpty()) return List.of();
            List<String> out = new ArrayList<>(raw.size());
            for (String alias : raw) if (alias != null && !alias.isBlank()) out.add(alias.toLowerCase(java.util.Locale.ROOT));
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Та же метка, что пишет клавиша JEI «поделиться в чат»: JEI получателя заменяет её ссылкой на ресурс. */
    @Override
    public String chatMarker(Stack stack) {
        try {
            Optional<ITypedIngredient<?>> typed = toTyped(stack);
            return typed.map(this::marker).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String marker(ITypedIngredient<?> typed) {
        IIngredientType type = typed.getType();
        mezz.jei.api.ingredients.IIngredientHelper helper = runtime.getIngredientManager().getIngredientHelper(type);
        Object uid = helper.getUid(typed, mezz.jei.api.ingredients.subtypes.UidContext.Ingredient);
        return "[JEI:v1:" + type.getUid() + ";" + uidString(uid) + "] ";
    }

    /** Как JeiChatItemLinks.getIngredientUidString: иначе JEI получателя не найдёт ресурс по метке. */
    private static String uidString(Object uid) {
        if (uid instanceof ResourceLocation rl) return rl.toString();
        if (uid instanceof net.minecraft.world.item.Item item) return net.minecraft.core.registries.BuiltInRegistries.ITEM.wrapAsHolder(item).getRegisteredName();
        if (uid instanceof net.minecraft.world.level.material.Fluid fluid) return net.minecraft.core.registries.BuiltInRegistries.FLUID.wrapAsHolder(fluid).getRegisteredName();
        if (uid instanceof Iterable<?> iterable) return iterableString(iterable);
        if (uid instanceof Object[] array) return iterableString(java.util.Arrays.asList(array));
        return String.valueOf(uid);
    }

    private static String iterableString(Iterable<?> values) {
        java.util.StringJoiner joiner = new java.util.StringJoiner(",", "(", ")");
        for (Object value : values) joiner.add(uidString(value));
        return joiner.toString();
    }

    @Override
    public java.util.Collection<String> modAliases(String modId) {
        try {
            return runtime.getJeiHelpers().getModIdHelper().getModAliases(modId);
        } catch (Throwable t) {
            return List.of();
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

    /**
     * Только эти рецепты категории (мод через showRecipes просит показать один рецепт). Рецепт, который у нас
     * показан своей раскладкой, берётся нашим, остальные оборачиваются как обычно. @return null, если показать нечего
     */
    @Nullable
    public <T> Lookup.Group groupOf(IRecipeCategory<T> c, List<T> recipes, List<IFocus<?>> focuses) {
        Snapshot snap = Indexer.snapshot();
        ResourceLocation uid = c.getRecipeType().getUid();
        IFocusGroup group = runtime.getJeiHelpers().getFocusFactory().createFocusGroup(focuses);
        Category own = snap.category(uid);
        java.util.Set<ResourceLocation> ownIds = own == null ? java.util.Set.of() : nativeIds(snap, uid);
        List<RecipeView> views = new ArrayList<>();
        for (T recipe : recipes) {
            ResourceLocation id;
            try {
                id = c.getRegistryName(recipe);
            } catch (Throwable t) {
                id = null;
            }
            RecipeView mine = null;
            if (id != null && ownIds.contains(id)) {
                for (RecipeView r : snap.recipes(own)) {
                    if (id.equals(r.id())) {
                        mine = r;
                        break;
                    }
                }
            }
            if (mine == null) {
                RecipeView structure = StructureRecipeView.of(recipe);
                mine = structure != null ? structure : new JeiRecipeView<>(this, c, recipe, group);
            }
            views.add(mine);
        }
        return views.isEmpty() ? null : new Lookup.Group(own != null ? own : category(c), views);
    }

    /** Как сортировка категорий JEI: есть ли обработчик переноса этой категории в открытое окно. */
    @Override
    public Boolean transfersTo(net.minecraft.world.inventory.AbstractContainerMenu menu, Category category) {
        try {
            Category known = category instanceof JeiCategory ? category : categoryByUid(category.id);
            if (!(known instanceof JeiCategory jc)) return null;
            // без аддона AE2 для JEI терминал всё равно принимает рецепты (см. JeiRecipeView.transfer)
            return hasTransferHandler(menu, jc.category) || works.repka.ryscope.runtime.Ae2Terminal.isTerminal(menu);
        } catch (Throwable t) {
            return null;
        }
    }

    private <C extends net.minecraft.world.inventory.AbstractContainerMenu, R> boolean hasTransferHandler(C menu, IRecipeCategory<R> c) {
        return runtime.getRecipeTransferManager().getRecipeTransferHandler(menu, c).isPresent();
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

    /**
     * Области чужих окон, которые панели обходят. Область тостов игры сюда не входит: её добавляет сам JEI
     * ({@code ToastGuiHandler}, настройка {@code toastReflowEnabled}), и из-за неё панель уезжала вниз, когда приходил тост
     * («открыт рецепт», уведомление FTB Quests, уведомление семьи). Тосты рисуются поверх окна, содержимое стоит на месте.
     */
    @Override
    public List<int[]> exclusionAreas(Screen screen) {
        List<int[]> out = new ArrayList<>();
        int right = screen.width;
        runtime.getScreenHelper().getGuiExclusionAreas(screen).forEach((Rect2i r) -> {
            if (r == null) return;
            // область тостов JEI считает от верхнего правого угла экрана: x = ширина экрана - ширина тоста, y = 0
            boolean toasts = r.getY() == 0 && r.getX() + r.getWidth() == right;
            if (KEEP_TOAST_AREA || !toasts) out.add(new int[] {r.getX(), r.getY(), r.getWidth(), r.getHeight()});
        });
        return out;
    }

    /** Для автотеста: оставить область тостов, как было, чтобы снять сдвиг «до». */
    public static boolean KEEP_TOAST_AREA;

    /** Как в JEI: курсор переводится в координаты окна, область берётся у обработчиков модов и самого JEI. */
    @Override
    public Foreign.ClickArea clickArea(Screen screen, double x, double y) {
        if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs)) return null;
        var screens = runtime.getScreenHelper();
        // с выключенным интерфейсом JEI не регистрирует обработчик окон-контейнеров (JeiGuiPlugin.registerGuiHandlers),
        // и свойства окна приходят пустыми: тогда положение берём у самого окна
        var props = screens.getGuiProperties(screen).orElse(null);
        int left, top;
        if (props != null) {
            left = props.guiLeft();
            top = props.guiTop();
        } else {
            int[] bounds = works.repka.ryscope.platform.Platform.get().screenBounds(acs);
            left = bounds[0];
            top = bounds[1];
        }
        mezz.jei.api.gui.handlers.IGuiClickableArea area = screens.getGuiClickableArea(acs, x - left, y - top)
            .findFirst().orElse(null);
        if (area == null) return null;
        return new Foreign.ClickArea() {
            @Override
            public List<net.minecraft.network.chat.Component> tooltip() {
                if (!area.isTooltipEnabled()) return List.of();
                List<net.minecraft.network.chat.Component> lines = TooltipLines.collect(area::getTooltip);
                return lines.isEmpty() ? List.of(net.minecraft.network.chat.Component.translatable("ryscope.click_area.show")) : lines;
            }

            @Override
            public void click() {
                area.onClick(runtime.getJeiHelpers().getFocusFactory(), runtime.getRecipesGui());
            }
        };
    }

    @Override
    public Stack stackUnderMouse(Screen screen, double mouseX, double mouseY) {
        return runtime.getScreenHelper().getClickableIngredientUnderMouse(screen, mouseX, mouseY)
            .findFirst().map(IClickableIngredient::getTypedIngredient).map(t -> fromTyped(t)).orElse(null);
    }
}
