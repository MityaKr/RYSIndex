package works.repka.ryscope.runtime;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
        boolean network;
        boolean anywhere;
    }

    private static final Map<Key, Seen> SEEN = new LinkedHashMap<>();
    private static volatile Map<Item, Long> merged = Map.of();
    private static volatile int inRange;

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

    /** Сколько запомненных хранилищ сейчас учитывается. */
    public static int inRange() {
        return Config.get().countStorages ? inRange : 0;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            if (!SEEN.isEmpty()) SEEN.clear();
            merged = Map.of();
            inRange = 0;
            trackedMenu = null;
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
        Map<Item, Long> network = Ae2.read(menu);
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
        seen.network = network != null;
        seen.anywhere = trackedAnywhere;
        mergeAge = 20;
    }

    private static void rebuild(Minecraft mc, LocalPlayer player) {
        int radius = Math.max(4, Config.get().storageRadius);
        long limit = (long) radius * radius;
        Map<Item, Long> sum = new HashMap<>();
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
            if (seen.counts.isEmpty()) continue;
            used++;
            for (Map.Entry<Item, Long> c : seen.counts.entrySet()) sum.merge(c.getKey(), c.getValue(), Long::sum);
        }
        merged = sum;
        inRange = used;
    }

    /**
     * Терминал сети AE2: клиент получает полный список содержимого сети, пока терминал открыт.
     * Читается отражением, без зависимости от AE2. Имена проверены по AE2 19.2.19:
     * MEStorageMenu.getClientRepo(), IClientRepo.getAllEntries(), GridInventoryEntry.getWhat()/getStoredAmount(),
     * AEItemKey.getItem().
     */
    private static final class Ae2 {
        private static boolean broken;
        private static Class<?> menuClass;
        private static Method getClientRepo, getAllEntries, getWhat, getStoredAmount, getItem;
        private static Class<?> itemKeyClass;

        static Map<Item, Long> read(AbstractContainerMenu menu) {
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
                    menuClass = candidate;
                }
                if (!menuClass.isInstance(menu)) return null;
                Object repo = getClientRepo.invoke(menu);
                if (repo == null) return null;
                Map<Item, Long> counts = new HashMap<>();
                for (Object entry : (Iterable<?>) getAllEntries.invoke(repo)) {
                    Object what = getWhat.invoke(entry);
                    if (!itemKeyClass.isInstance(what)) continue;
                    long amount = (long) getStoredAmount.invoke(entry);
                    if (amount > 0) counts.merge((Item) getItem.invoke(what), amount, Long::sum);
                }
                return counts;
            } catch (Throwable t) {
                broken = true;
                RYScope.LOG.warn("Не удалось прочитать содержимое сети AE2, учёт сети отключён: {}", t.toString());
                return null;
            }
        }
    }
}
