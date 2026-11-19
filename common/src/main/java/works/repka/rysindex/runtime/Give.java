package works.repka.rysindex.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Выдача предметов в режиме читов, как в JEI. В творческом инвентаре выдаёт клиент: на курсор или в инвентарь.
 * Иначе, если RYSIndex стоит и на сервере, выдаёт сервер: тогда это может и оператор в выживании, предмет
 * кладётся на курсор открытого окна-контейнера, а лишнее, что не влезло, выпадает рядом. Без него остаётся путь
 * творческого режима: в инвентарь, с дозаполнением неполных стаков.
 */
public final class Give {
    public enum Mode { INVENTORY, CURSOR, DELETE }

    public interface Sender {
        /** @return отправлено ли серверу */
        boolean send(ItemStack stack, Mode mode);
    }

    /** Отправка серверу. Подставляет модуль загрузчика; без канала на сервере ничего не отправляет. */
    public static Sender sender = (stack, mode) -> false;
    /** Есть ли на сервере приёмник выдачи. */
    public static java.util.function.BooleanSupplier serverSupports = () -> false;

    /** Уровень прав, как у команды /give. */
    private static final int PERMISSION = 2;

    private Give() {}

    /** Можно ли сейчас выдавать: творческий режим или права оператора при RYSIndex на сервере. */
    public static boolean allowed() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        return player.isCreative() || (player.hasPermissions(PERMISSION) && serverSupports.getAsBoolean());
    }

    /** @return выдано ли (false: инвентарь полон и выдавать некуда) */
    public static boolean give(ItemStack item, int count, boolean toCursor) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || item.isEmpty()) return false;
        ItemStack stack = item.copyWithCount(Math.max(1, Math.min(count, item.getMaxStackSize())));
        if (creativeScreen()) {
            // творческий экран не принимает курсор от сервера (ClientPacketListener.handleContainerSetSlot),
            // а слоты синхронизирует сам: выдача идёт на клиенте, как в JEI
            if (toCursor && toCreativeCursor(player, stack)) return true;
            return toCreativeInventory(mc, player, stack);
        }
        // курсор серверного окна виден только в обычном окне-контейнере; в карточке рецептов и в мире в инвентарь
        boolean cursor = toCursor && mc.screen instanceof AbstractContainerScreen<?>;
        if (serverSupports.getAsBoolean() && sender.send(stack, cursor ? Mode.CURSOR : Mode.INVENTORY)) return true;
        if (!player.isCreative() || mc.gameMode == null) return false;
        return toCreativeInventory(mc, player, stack);
    }

    /** Открыт творческий инвентарь, и игрок в творческом режиме. */
    private static boolean creativeScreen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen instanceof CreativeModeInventoryScreen && mc.player != null && mc.player.isCreative() && mc.gameMode != null;
    }

    /** Как в JEI: тот же предмет на курсоре дополняется до стака, пустой курсор занимает новый. Другой предмет не трогаем. */
    private static boolean toCreativeCursor(LocalPlayer player, ItemStack stack) {
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            player.containerMenu.setCarried(stack);
            return true;
        }
        if (!ItemStack.isSameItemSameComponents(carried, stack)) return false;
        carried.setCount(Math.min(carried.getMaxStackSize(), carried.getCount() + stack.getCount()));
        return true;
    }

    /** Творческий режим без сервера с модом: сначала неполные стаки того же предмета, затем пустые слоты. */
    private static boolean toCreativeInventory(Minecraft mc, LocalPlayer player, ItemStack stack) {
        int amount = stack.getCount();
        var items = player.getInventory().items;
        for (int i = 0; i < items.size() && amount > 0; i++) {
            ItemStack current = items.get(i);
            if (!current.isEmpty() && (!ItemStack.isSameItemSameComponents(current, stack) || current.getCount() >= current.getMaxStackSize())) continue;
            int room = current.isEmpty() ? amount : Math.min(current.getMaxStackSize() - current.getCount(), amount);
            ItemStack put = stack.copyWithCount(current.isEmpty() ? room : current.getCount() + room);
            player.getInventory().setItem(i, put);
            mc.gameMode.handleCreativeModeItemAdd(put, i < 9 ? 36 + i : i);
            amount -= room;
        }
        // не влезло: выбрасывается рядом, как в JEI
        if (amount > 0) mc.gameMode.handleCreativeModeItemAdd(stack.copyWithCount(amount), -1);
        return true;
    }

    /** Можно ли убрать предмет с курсора кликом по списку: в творческом инвентаре всегда, иначе при RYSIndex на сервере. */
    public static boolean canDeleteCarried() {
        return creativeScreen() || serverSupports.getAsBoolean();
    }

    /** Убрать предмет с курсора (клик им по списку, как в JEI). */
    public static boolean deleteCarried() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.containerMenu.getCarried().isEmpty()) return false;
        if (creativeScreen()) {
            // курсор творческого экрана живёт на клиенте: игра сама так убирает предмет, брошенный в сетку
            player.containerMenu.setCarried(ItemStack.EMPTY);
            return true;
        }
        if (!serverSupports.getAsBoolean() || !sender.send(player.containerMenu.getCarried().copy(), Mode.DELETE)) return false;
        player.containerMenu.setCarried(ItemStack.EMPTY);
        return true;
    }
}
