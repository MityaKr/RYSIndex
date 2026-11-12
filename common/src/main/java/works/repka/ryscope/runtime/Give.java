package works.repka.ryscope.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Выдача предметов в режиме читов, как в JEI. Если RYScope стоит и на сервере, выдаёт сервер: тогда это может
 * и оператор в выживании, предмет кладётся на курсор, а лишнее, что не влезло, выпадает рядом. Без него
 * остаётся путь творческого режима: в инвентарь, с дозаполнением неполных стаков.
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

    /** Можно ли сейчас выдавать: творческий режим или права оператора при RYScope на сервере. */
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
        if (serverSupports.getAsBoolean() && sender.send(stack, toCursor ? Mode.CURSOR : Mode.INVENTORY)) return true;
        if (!player.isCreative() || mc.gameMode == null) return false;
        // творческий режим без сервера с модом: сначала неполные стаки того же предмета, затем пустые слоты
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

    /** Убрать предмет с курсора (клик им по списку, как в JEI). */
    public static boolean deleteCarried() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.containerMenu.getCarried().isEmpty()) return false;
        if (!serverSupports.getAsBoolean() || !sender.send(player.containerMenu.getCarried().copy(), Mode.DELETE)) return false;
        player.containerMenu.setCarried(ItemStack.EMPTY);
        return true;
    }
}
