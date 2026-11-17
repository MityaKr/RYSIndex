package works.repka.ryscope.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.FluidRes;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.RecipeView.Transfer;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.platform.Platform;

/**
 * Терминалы AE2: перенос рецепта в сетку крафта из сети, заказ недостающего (Ctrl) и запись шаблонов.
 * Работает через те же общие помощники AE2, которыми пользуются её интеграции с просмотрщиками рецептов,
 * поэтому отдельный аддон AE2 для JEI не нужен. Зависимости от AE2 нет: всё через отражение.
 */
public final class Ae2Terminal {
    public enum State { HAVE, CRAFTABLE, MISSING }

    private static final long REFRESH_MS = 500;

    private static AbstractContainerMenu active;
    private static AbstractContainerMenu cachedFor;
    private static long cachedAt;
    private static Map<Item, Long> network = Map.of();
    private static Set<Item> craftable = Set.of();

    private Ae2Terminal() {}

    /** Имена проверены по AE2 19.2.17 и 19.2.19. */
    private static final class Api {
        static final boolean OK;
        static Class<?> storageMenu, craftingMenu, patternMenu, itemKey;
        static Method repo, allEntries, what, stored, entryCraftable, itemOf;
        static Method findMissing, missingSlots, craftableSlots;
        static Method performTransfer, encodeCrafting, encodeProcessing, supported;
        static Method craftingTooltip, encodingTooltip, fromItemStack, fluidKey, itemKeyOf, processingInputSlots;
        static Constructor<?> genericStack;

        static {
            boolean ok = false;
            try {
                if (Platform.get().isModLoaded("ae2")) {
                    storageMenu = Class.forName("appeng.menu.me.common.MEStorageMenu");
                    craftingMenu = Class.forName("appeng.menu.me.items.CraftingTermMenu");
                    patternMenu = Class.forName("appeng.menu.me.items.PatternEncodingTermMenu");
                    itemKey = Class.forName("appeng.api.stacks.AEItemKey");
                    Class<?> entry = Class.forName("appeng.menu.me.common.GridInventoryEntry");
                    Class<?> aeKey = Class.forName("appeng.api.stacks.AEKey");
                    Class<?> stack = Class.forName("appeng.api.stacks.GenericStack");
                    Class<?> missing = Class.forName("appeng.menu.me.items.CraftingTermMenu$MissingIngredientSlots");
                    Class<?> crafting = Class.forName("appeng.integration.modules.itemlists.CraftingHelper");
                    Class<?> encoding = Class.forName("appeng.integration.modules.itemlists.EncodingHelper");
                    Class<?> tips = Class.forName("appeng.integration.modules.itemlists.TransferHelper");
                    repo = storageMenu.getMethod("getClientRepo");
                    allEntries = Class.forName("appeng.menu.me.common.IClientRepo").getMethod("getAllEntries");
                    what = entry.getMethod("getWhat");
                    stored = entry.getMethod("getStoredAmount");
                    entryCraftable = entry.getMethod("isCraftable");
                    itemOf = itemKey.getMethod("getItem");
                    findMissing = craftingMenu.getMethod("findMissingIngredients", Map.class);
                    missingSlots = missing.getMethod("missingSlots");
                    craftableSlots = missing.getMethod("craftableSlots");
                    performTransfer = crafting.getMethod("performTransfer", craftingMenu,
                        net.minecraft.resources.ResourceLocation.class, Recipe.class, boolean.class);
                    encodeCrafting = encoding.getMethod("encodeCraftingRecipe", patternMenu, RecipeHolder.class, List.class, Predicate.class);
                    encodeProcessing = encoding.getMethod("encodeProcessingRecipe", patternMenu, List.class, List.class);
                    supported = encoding.getMethod("isSupportedCraftingRecipe", Recipe.class);
                    craftingTooltip = tips.getMethod("createCraftingTooltip", missing, boolean.class, boolean.class);
                    encodingTooltip = tips.getMethod("createEncodingTooltip", boolean.class, boolean.class);
                    fromItemStack = stack.getMethod("fromItemStack", ItemStack.class);
                    fluidKey = Class.forName("appeng.api.stacks.AEFluidKey").getMethod("of", Fluid.class);
                    genericStack = stack.getConstructor(aeKey, long.class);
                    itemKeyOf = itemKey.getMethod("of", ItemStack.class);
                    processingInputSlots = patternMenu.getMethod("getProcessingInputSlots");
                    ok = true;
                }
            } catch (Throwable t) {
                RYScope.LOG.warn("Поддержка терминалов AE2 отключена: {}", t.toString());
            }
            OK = ok;
        }
    }

    /** Куда отдать группы взаимозаменяемых предметов разложенного рецепта (замещение в шаблонах обработки). */
    @Nullable
    public static java.util.function.Consumer<List<List<Item>>> alternativesSink;

    public static boolean isTerminal(@Nullable AbstractContainerMenu menu) {
        return menu != null && Api.OK && Api.storageMenu.isInstance(menu);
    }

    /** Экран рецептов сообщает, поверх какого терминала он открыт: от этого зависят отметки в рецептах. */
    public static void setActive(@Nullable AbstractContainerMenu menu) {
        active = isTerminal(menu) ? menu : null;
    }

    public static boolean active() {
        return active != null;
    }

    /** Выход из мира: меню терминала держит игрока и мир, а содержимое сети к следующему миру не относится. */
    public static void reset() {
        active = null;
        cachedFor = null;
        network = Map.of();
        craftable = Set.of();
    }

    /** Растёт на каждое обновление клиентского списка сети (перехват Repo.handleUpdate). */
    private static volatile int repoVersion;
    private static boolean repoHooked;
    private static int cachedRepo = -1;

    /** Вызывает перехват AE2: сервер прислал изменения содержимого сети. */
    public static void onRepoUpdate() {
        repoHooked = true;
        repoVersion++;
    }

    /** Номер содержимого сети: меняется, когда сервер прислал изменения. Для пересчёта отметок по событию. */
    public static int networkVersion() {
        return repoVersion;
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        // с перехватом список перечитывается ровно тогда, когда он изменился; без него по таймеру, как раньше
        boolean fresh = repoHooked ? cachedRepo == repoVersion : now - cachedAt < REFRESH_MS;
        if (active == cachedFor && fresh) return;
        cachedFor = active;
        cachedAt = now;
        cachedRepo = repoVersion;
        Map<Item, Long> have = new HashMap<>();
        Set<Item> craft = new java.util.HashSet<>();
        try {
            Object repo = Api.repo.invoke(active);
            if (repo != null) {
                for (Object entry : (Iterable<?>) Api.allEntries.invoke(repo)) {
                    Object what = Api.what.invoke(entry);
                    if (!Api.itemKey.isInstance(what)) continue;
                    Item item = (Item) Api.itemOf.invoke(what);
                    long amount = (long) Api.stored.invoke(entry);
                    if (amount > 0) have.merge(item, amount, Long::sum);
                    if ((boolean) Api.entryCraftable.invoke(entry)) craft.add(item);
                }
            }
        } catch (Throwable t) {
            RYScope.LOG.debug("Содержимое сети AE2 не прочитано: {}", t.toString());
        }
        network = have;
        if (!craft.equals(craftable)) liveVersion++;
        craftable = craft;
    }

    /** Растёт, когда меняется живое множество «можно заказать» открытого терминала. */
    private static int liveVersion;

    /**
     * Умеет ли сеть AE2 сделать предмет автокрафтом: по открытому сейчас терминалу или по памяти о терминалах,
     * которые игрок открывал в этом мире ({@link Storages#craftable}).
     */
    public static boolean craftableKnown(Item item) {
        return craftableNow().contains(item);
    }

    /**
     * Всё, что сеть сейчас умеет делать, одним множеством. Дерево берёт его один раз на проход, чтобы весь проход
     * считал по одним данным. Не менять: это опубликованный экземпляр.
     */
    public static Set<Item> craftableNow() {
        Set<Item> memory = Storages.craftable();
        if (active == null || !Api.OK) return memory;
        refresh();
        if (craftable.isEmpty() || memory.containsAll(craftable)) return memory;
        Set<Item> union = new java.util.HashSet<>(memory);
        union.addAll(craftable);
        return union;
    }

    /** Есть ли хоть что-то, что сеть умеет делать: без этого переключатель автокрафта не показывается. */
    public static boolean anyCraftableKnown() {
        return !craftableNow().isEmpty();
    }

    /**
     * Номер данных «сеть сделает»: меняется вместе с памятью о терминалах и с живым терминалом. Входит в признаки
     * устаревания деревьев целей и общей суммы.
     */
    public static int craftableVersion() {
        return Storages.craftableVersion() + liveVersion + (active != null ? 1 << 20 : 0);
    }

    /** Когда прочитаны данные «сеть сделает», мс: сейчас, если терминал открыт, иначе время последнего чтения. */
    public static long craftableAt() {
        return active != null ? System.currentTimeMillis() : Storages.craftableAt();
    }

    /** Окно открыто над терминалом шаблонов: там важно только «можно заказать». */
    public static boolean isPattern() {
        return active != null && Api.patternMenu.isInstance(active);
    }

    /** Сколько каждого предмета лежит в сети. */
    public static Map<Item, Long> amounts() {
        if (active == null) return Map.of();
        refresh();
        return network;
    }

    /** Умеет ли сеть сделать хоть один из вариантов ингредиента. */
    public static boolean craftable(Ing ing) {
        if (active == null) return false;
        refresh();
        for (Stack option : ing.options) if (option.key() instanceof Item item && craftable.contains(item)) return true;
        return false;
    }

    /**
     * Есть ли ингредиент в сети или инвентаре, можно ли его заказать, без учёта количества.
     * Для отметок в рецепте брать {@link Supply#states}: там предметы делятся между ячейками.
     */
    public static State stateOf(Ing ing) {
        if (active == null || ing.isEmpty()) return State.HAVE;
        refresh();
        boolean pattern = Api.patternMenu.isInstance(active);
        boolean canCraft = false;
        Map<Item, Integer> inventory = InventoryView.snapshot();
        for (Stack option : ing.options) {
            if (!(option.key() instanceof Item item)) return State.HAVE;
            if (!pattern && (network.containsKey(item) || inventory.getOrDefault(item, 0) > 0)) return State.HAVE;
            if (craftable.contains(item)) canCraft = true;
        }
        if (canCraft) return State.CRAFTABLE;
        return pattern ? State.HAVE : State.MISSING;
    }

    /** @return итог переноса или null, если это не терминал AE2 и пробовать надо другим способом. */
    @Nullable
    public static Transfer transfer(AbstractContainerMenu menu, @Nullable RecipeHolder<?> holder, List<Ing> inputs, List<Stack> outputs, boolean simulate) {
        if (!Api.OK) return null;
        try {
            if (Api.craftingMenu.isInstance(menu)) return toCraftingGrid(menu, holder, simulate);
            if (Api.patternMenu.isInstance(menu)) return toPattern(menu, holder, inputs, outputs, simulate);
        } catch (Throwable t) {
            RYScope.LOG.warn("Перенос рецепта в терминал AE2 не удался: {}", t.toString());
            return Transfer.missing("ryscope.transfer.failed");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Transfer toCraftingGrid(AbstractContainerMenu menu, @Nullable RecipeHolder<?> holder, boolean simulate) throws ReflectiveOperationException {
        if (holder == null || !(holder.value() instanceof CraftingRecipe recipe) || recipe.getType() != RecipeType.CRAFTING) return Transfer.UNSUPPORTED;
        if (!recipe.canCraftInDimensions(3, 3)) return Transfer.missing("ryscope.transfer.too_small");
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        Map<Integer, Ingredient> bySlot = new HashMap<>();
        for (int i = 0; i < ingredients.size(); i++) if (!ingredients.get(i).isEmpty()) bySlot.put(i, ingredients.get(i));
        if (bySlot.isEmpty()) return Transfer.UNSUPPORTED;
        boolean craftMissing = Screen.hasControlDown();
        if (simulate) {
            Object result = Api.findMissing.invoke(menu, bySlot);
            Set<Integer> missing = (Set<Integer>) Api.missingSlots.invoke(result);
            Set<Integer> canCraft = (Set<Integer>) Api.craftableSlots.invoke(result);
            if (missing.size() == bySlot.size()) return Transfer.missing("ryscope.transfer.missing");
            List<Component> tip = (List<Component>) Api.craftingTooltip.invoke(null, result, craftMissing, true);
            return missing.isEmpty() && canCraft.isEmpty() ? Transfer.ok(tip) : Transfer.partial(tip);
        }
        Api.performTransfer.invoke(null, menu, holder.id(), recipe, craftMissing);
        return Transfer.OK;
    }

    @SuppressWarnings("unchecked")
    private static Transfer toPattern(AbstractContainerMenu menu, @Nullable RecipeHolder<?> holder, List<Ing> inputs, List<Stack> outputs, boolean simulate) throws ReflectiveOperationException {
        boolean craftingLike = holder != null && (boolean) Api.supported.invoke(null, holder.value());
        if (craftingLike && holder.value() instanceof CraftingRecipe recipe && !recipe.canCraftInDimensions(3, 3)) {
            return Transfer.missing("ryscope.transfer.too_small");
        }
        if (simulate) {
            setActive(menu);
            boolean anyCraftable = false;
            for (Ing ing : inputs) if (stateOf(ing) == State.CRAFTABLE) anyCraftable = true;
            return Transfer.ok((List<Component>) Api.encodingTooltip.invoke(null, anyCraftable, true));
        }
        if (craftingLike) {
            Predicate<ItemStack> anyVisible = stack -> true;
            Api.encodeCrafting.invoke(null, menu, holder, List.of(), anyVisible);
            return Transfer.OK;
        }
        List<List<Object>> in = new ArrayList<>();
        for (Ing ing : inputs) {
            List<Object> options = new ArrayList<>();
            for (Stack option : ing.options) {
                Object stack = generic(option);
                if (stack != null) options.add(stack);
            }
            if (!options.isEmpty()) in.add(options);
        }
        List<Object> out = new ArrayList<>();
        for (Stack stack : outputs) {
            Object generic = generic(stack);
            if (generic != null) out.add(generic);
        }
        if (in.isEmpty() || out.isEmpty()) return Transfer.UNSUPPORTED;
        Api.encodeProcessing.invoke(null, menu, in, out);
        if (alternativesSink != null) {
            // какие предметы взаимозаменяемы в каждом входе: сервер запишет их в шаблон, если включено замещение
            List<List<Item>> groups = new ArrayList<>();
            for (Ing ing : inputs) {
                List<Item> items = new ArrayList<>();
                for (Stack option : ing.options) if (option.key() instanceof Item item && !items.contains(item)) items.add(item);
                if (items.size() > 1) groups.add(items);
            }
            if (!groups.isEmpty()) alternativesSink.accept(groups);
        }
        return Transfer.OK;
    }

    /**
     * Шаблон обработки из постройки мультиблока: каждый блок отдельным входом в полном количестве (AE2 позволяет
     * входу шаблона больше стопки), на выходе предмет машины. Пишется тем же помощником AE2, что и перенос рецептов
     * из JEI ({@code EncodingHelper.encodeProcessingRecipe}): терминал переключается в режим обработки, слоты
     * заполняются пакетами AE2. Если разных блоков больше, чем входов у шаблона (81 в AE2 19), шаблон не пишется.
     *
     * @return итог или null, если это не терминал шаблонов AE2
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static Transfer encodeStructure(AbstractContainerMenu menu, List<Stack> blocks, Stack machine, boolean simulate) {
        if (!Api.OK || !Api.patternMenu.isInstance(menu)) return null;
        try {
            int slots = ((Object[]) Api.processingInputSlots.invoke(menu)).length;
            if (blocks.size() > slots) {
                return new Transfer(Transfer.Status.MISSING, "ryscope.transfer.structure.too_many", List.of(
                    Component.translatable("ryscope.transfer.structure.too_many", blocks.size(), slots)));
            }
            if (simulate) {
                long total = 0;
                for (Stack b : blocks) total += Math.max(1, b.amount);
                List<Component> tip = new ArrayList<>();
                tip.add(Component.translatable("ryscope.transfer.structure.hint"));
                tip.add(Component.translatable("ryscope.transfer.structure.count", blocks.size(), total)
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
                tip.add(Component.translatable("ryscope.transfer.structure.output", machine.name())
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
                return Transfer.ok(tip);
            }
            List<List<Object>> in = new ArrayList<>();
            for (Stack b : blocks) {
                Object stack = genericFull(b);
                if (stack != null) in.add(List.of(stack));
            }
            Object out = genericFull(machine);
            if (in.isEmpty() || out == null) return Transfer.UNSUPPORTED;
            Api.encodeProcessing.invoke(null, menu, in, List.of(out));
            return Transfer.OK;
        } catch (Throwable t) {
            RYScope.LOG.warn("Шаблон постройки не записан: {}", t.toString());
            return Transfer.missing("ryscope.transfer.failed");
        }
    }

    /** Стопка AE2 в полном количестве, без обрезки до размера стопки игры. */
    @Nullable
    private static Object genericFull(Stack stack) throws ReflectiveOperationException {
        ItemStack item = stack.item();
        if (item.isEmpty()) return generic(stack);
        Object key = Api.itemKeyOf.invoke(null, item);
        return key == null ? null : Api.genericStack.newInstance(key, Math.max(1L, stack.amount));
    }

    @Nullable
    private static Object generic(Stack stack) throws ReflectiveOperationException {
        if (stack instanceof FluidRes fluid) {
            return Api.genericStack.newInstance(Api.fluidKey.invoke(null, fluid.fluid), Math.max(1, fluid.amount));
        }
        ItemStack item = stack.item();
        if (item.isEmpty()) return null;
        return Api.fromItemStack.invoke(null, item.copyWithCount((int) Math.max(1, Math.min(stack.amount, item.getMaxStackSize()))));
    }
}
