package works.repka.ryscope.runtime;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import works.repka.ryscope.RYScope;

/**
 * Что лежит в хранилищах рядом с игроком. Клиент не знает содержимого закрытых сундуков, поэтому это память:
 * мод запоминает, что игрок видел, когда открывал сундук, бочку, ящик или терминал сети AE2.
 * Работает на любом сервере, но показывает последнее увиденное, а не текущее.
 *
 * Память живёт до выхода из мира. В расчёт идут хранилища в том же измерении и не дальше заданного радиуса;
 * сеть, открытая через беспроводной терминал, считается доступной везде.
 */
public final class Storages {
    private static final int LIMIT = 256;

    private record Key(ResourceKey<Level> dimension, BlockPos pos) {}

    private static final class Seen {
        Map<Item, Long> counts = Map.of();
        /** Что сеть умеет делать автокрафтом (только у терминалов AE2). */
        Set<Item> craftable = Set.of();
        /** Когда терминал читался последний раз, мс. */
        long readAt;
        boolean network;
        boolean anywhere;
    }

    private static final Map<Key, Seen> SEEN = new LinkedHashMap<>();
    private static volatile Map<Item, Long> merged = Map.of();
    private static volatile int inRange;
    private static volatile Set<Item> mergedCraftable = Set.of();
    private static volatile long craftableAt;
    /** Растёт, только когда меняется само множество «сеть сделает». */
    private static volatile int craftableVersion;
    /** Растёт, только когда меняется сумма по хранилищам: по ней узнают, что запас тот же. */
    private static volatile int version;

    private static AbstractContainerMenu trackedMenu;
    private static Key trackedKey;
    private static boolean trackedAnywhere;
    private static int recordAge;
    private static int mergeAge;

    private Storages() {}

    /** Сумма по хранилищам в радиусе: предмет и сколько его видели. */
    public static Map<Item, Long> counts() {
        return Config.get().countStorages ? merged : Map.of();
    }

    public static long count(Item item) {
        return counts().getOrDefault(item, 0L);
    }

    /** Номер содержимого запомненных хранилищ: меняется, только когда меняется сама сумма. */
    public static int version() {
        return version;
    }

    /**
     * Что умеют делать автокрафтом сети AE2, чьи терминалы игрок открывал: те же правила дальности, что у количеств
     * (то же измерение и радиус, беспроводной терминал отовсюду). Не зависит от настройки учёта хранилищ.
     */
    public static Set<Item> craftable() {
        return mergedCraftable;
    }

    /** Когда прочитан самый свежий из учтённых терминалов, мс; 0, если ни одного. */
    public static long craftableAt() {
        return craftableAt;
    }

    public static int craftableVersion() {
        return craftableVersion;
    }

    /** Сколько запомненных хранилищ сейчас учитывается. */
    public static int inRange() {
        return Config.get().countStorages ? inRange : 0;
    }

    /**
     * Выход из мира: запомненные хранилища принадлежат миру. Тик сам этого не сделает: без мира он не вызывается,
     * а в следующем мире игрок уже есть, и беспроводной терминал прошлого мира (он учитывается отовсюду)
     * подмешивал бы свою сеть к новому миру.
     */
    public static void reset() {
        if (!SEEN.isEmpty()) SEEN.clear();
        if (!merged.isEmpty()) version++;
        merged = Map.of();
        inRange = 0;
        setCraftable(Set.of(), 0);
        trackedMenu = null;
        trackedKey = null;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            reset();
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != player.inventoryMenu && mc.screen instanceof AbstractContainerScreen<?>) {
            if (menu != trackedMenu) {
                trackedMenu = menu;
                trackedKey = openedBlock(mc);
                trackedAnywhere = false;
                recordAge = 8;
            }
            if (++recordAge >= 10) {
                recordAge = 0;
                record(mc, player, menu);
            }
        } else {
            trackedMenu = null;
        }
        if (++mergeAge >= 20) {
            mergeAge = 0;
            rebuild(mc, player);
        }
    }

    /** Блок, который игрок открыл: тот, на который он смотрел в момент открытия, если у блока есть хранилище данных. */
    private static Key openedBlock(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        if (mc.level.getBlockEntity(pos) == null) return null;
        BlockState state = mc.level.getBlockState(pos);
        // у двойного сундука две половины: обе должны давать один и тот же ключ
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            Direction side = ChestBlock.getConnectedDirection(state);
            BlockPos other = pos.relative(side);
            if (other.compareTo(pos) < 0) pos = other;
        }
        return new Key(mc.level.dimension(), pos.immutable());
    }

    private static void record(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu) {
        Ae2.Read read = Ae2.read(menu);
        Map<Item, Long> network = read == null ? null : read.counts();
        Key key = trackedKey;
        if (network != null && key == null) {
            // беспроводной терминал: блока нет, сеть доступна отовсюду
            key = new Key(mc.level.dimension(), BlockPos.ZERO);
            trackedAnywhere = true;
        }
        if (key == null) return;
        Map<Item, Long> counts;
        if (network != null) {
            counts = network;
        } else {
            counts = new HashMap<>();
            for (Slot slot : menu.slots) {
                if (slot.container instanceof Inventory) continue;
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) counts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
            }
        }
        Seen seen = SEEN.get(key);
        if (seen == null) {
            // многоблочное хранилище, открытое с другого блока: то же содержимое в паре блоков отсюда уже записано
            if (!counts.isEmpty() && network == null) {
                for (Map.Entry<Key, Seen> e : SEEN.entrySet()) {
                    if (e.getKey().dimension().equals(key.dimension()) && !e.getValue().network
                        && e.getKey().pos().distManhattan(key.pos()) <= 4 && e.getValue().counts.equals(counts)) {
                        return;
                    }
                }
            }
            seen = new Seen();
            SEEN.put(key, seen);
            if (SEEN.size() > LIMIT) {
                Iterator<Key> oldest = SEEN.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
        seen.counts = counts;
        // при каждом открытии терминала память о шаблонах этой сети перезаписывается
        seen.craftable = read == null ? Set.of() : read.craftable();
        if (read != null) seen.readAt = System.currentTimeMillis();
        seen.network = network != null;
        seen.anywhere = trackedAnywhere;
        mergeAge = 20;
    }

    private static void rebuild(Minecraft mc, LocalPlayer player) {
        int radius = Math.max(4, Config.get().storageRadius);
        long limit = (long) radius * radius;
        Map<Item, Long> sum = new HashMap<>();
        Set<Item> craft = new java.util.HashSet<>();
        long craftAt = 0;
        int used = 0;
        for (Iterator<Map.Entry<Key, Seen>> it = SEEN.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Seen> e = it.next();
            Key key = e.getKey();
            Seen seen = e.getValue();
            if (!key.dimension().equals(mc.level.dimension())) continue;
            if (!seen.anywhere) {
                if (mc.level.isLoaded(key.pos()) && mc.level.getBlockEntity(key.pos()) == null) {
                    // блок сломали или заменили
                    it.remove();
                    continue;
                }
                if (key.pos().distToCenterSqr(player.position()) > limit) continue;
            }
            if (!seen.craftable.isEmpty()) {
                craft.addAll(seen.craftable);
                craftAt = Math.max(craftAt, seen.readAt);
            }
            if (seen.counts.isEmpty()) continue;
            used++;
            for (Map.Entry<Item, Long> c : seen.counts.entrySet()) sum.merge(c.getKey(), c.getValue(), Long::sum);
        }
        // опубликованную карту не меняем: при той же сумме остаётся прежняя и прежний номер
        if (!sum.equals(merged)) {
            merged = sum;
            version++;
        }
        inRange = used;
        setCraftable(craft, craftAt);
    }

    /** Публикует множество «сеть сделает»; прежний экземпляр не меняется, версия растёт только при другом составе. */
    private static void setCraftable(Set<Item> craft, long at) {
        craftableAt = craft.isEmpty() ? 0 : at;
        if (craft.equals(mergedCraftable)) return;
        mergedCraftable = craft.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(craft);
        craftableVersion++;
    }

    /**
     * Терминал сети AE2: клиент получает полный список содержимого сети, пока терминал открыт.
     * Читается отражением, без зависимости от AE2. Имена проверены по AE2 19.2.17 и 19.2.19:
     * MEStorageMenu.getClientRepo(), IClientRepo.getAllEntries(), GridInventoryEntry.getWhat()/getStoredAmount()/
     * isCraftable(), AEItemKey.getItem().
     *
     * Что сеть умеет делать (AE2 19.2.17, декомпиляция): MEStorageMenu.broadcastChanges на сервере шлёт craftables
     * из getCraftingService().getCraftables(isKeyVisible) в MEInventoryUpdatePacket; клиентский Repo.handleUpdate
     * держит запись, пока она isMeaningful (есть количество или craftable), поэтому в getAllEntries попадают и
     * предметы с нулём в сети, на которые есть шаблон. showsCraftables() внутри AE2 не переопределяется; у
     * обесточенной сети список пуст.
     */
    private static final class Ae2 {
        record Read(Map<Item, Long> counts, Set<Item> craftable) {}

        private static boolean broken;
        private static Class<?> menuClass;
        private static Method getClientRepo, getAllEntries, getWhat, getStoredAmount, getItem;
        @org.jetbrains.annotations.Nullable
        private static Method isCraftable;
        private static Class<?> itemKeyClass;

        static Read read(AbstractContainerMenu menu) {
            if (broken) return null;
            try {
                if (menuClass == null) {
                    ClassLoader loader = menu.getClass().getClassLoader();
                    Class<?> candidate;
                    try {
                        candidate = Class.forName("appeng.menu.me.common.MEStorageMenu", false, loader);
                    } catch (ClassNotFoundException e) {
                        // у этого меню другой загрузчик или AE2 не установлен
                        if (loader == Storages.class.getClassLoader()) return null;
                        try {
                            candidate = Class.forName("appeng.menu.me.common.MEStorageMenu");
                        } catch (ClassNotFoundException e2) {
                            return null;
                        }
                    }
                    if (!candidate.isInstance(menu)) return null;
                    ClassLoader ae = candidate.getClassLoader();
                    getClientRepo = candidate.getMethod("getClientRepo");
                    getAllEntries = Class.forName("appeng.menu.me.common.IClientRepo", false, ae).getMethod("getAllEntries");
                    Class<?> entry = Class.forName("appeng.menu.me.common.GridInventoryEntry", false, ae);
                    getWhat = entry.getMethod("getWhat");
                    getStoredAmount = entry.getMethod("getStoredAmount");
                    itemKeyClass = Class.forName("appeng.api.stacks.AEItemKey", false, ae);
                    getItem = itemKeyClass.getMethod("getItem");
                    try {
                        isCraftable = entry.getMethod("isCraftable");
                    } catch (NoSuchMethodException e) {
                        // количества читаются и без этого, выключен только учёт автокрафта
                        RYScope.LOG.warn("В этой версии AE2 нет GridInventoryEntry.isCraftable: учёт автокрафта отключён");
                    }
                    menuClass = candidate;
                }
                if (!menuClass.isInstance(menu)) return null;
                Object repo = getClientRepo.invoke(menu);
                if (repo == null) return null;
                Map<Item, Long> counts = new HashMap<>();
                Set<Item> craftable = new java.util.HashSet<>();
                for (Object entry : (Iterable<?>) getAllEntries.invoke(repo)) {
                    Object what = getWhat.invoke(entry);
                    // жидкости и химикаты аддонов в первую версию не входят
                    if (!itemKeyClass.isInstance(what)) continue;
                    Item item = (Item) getItem.invoke(what);
                    long amount = (long) getStoredAmount.invoke(entry);
                    if (amount > 0) counts.merge(item, amount, Long::sum);
                    if (isCraftable != null && (boolean) isCraftable.invoke(entry)) craftable.add(item);
                }
                return new Read(counts, craftable);
            } catch (Throwable t) {
                broken = true;
                RYScope.LOG.warn("Не удалось прочитать содержимое сети AE2, учёт сети отключён: {}", t.toString());
                return null;
            }
        }
    }
}
