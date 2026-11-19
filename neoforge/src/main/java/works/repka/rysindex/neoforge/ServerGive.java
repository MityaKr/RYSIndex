package works.repka.rysindex.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.neoforge.net.GivePayload;

/** Сервер: выдача предметов из режима читов. Права как у /give: творческий режим или оператор уровня 2. */
final class ServerGive {
    private static final int PERMISSION = 2;

    private ServerGive() {}

    static void handle(ServerPlayer player, GivePayload payload) {
        if (!player.isCreative() && !player.hasPermissions(PERMISSION)) {
            RYSIndex.LOG.debug("{} попросил выдать предмет без прав", player.getName().getString());
            return;
        }
        // клиентский класс выдачи на сервере не загружается: режимы числами из пакета
        int mode = payload.mode();
        AbstractContainerMenu menu = player.containerMenu;
        if (mode == GivePayload.DELETE) {
            menu.setCarried(ItemStack.EMPTY);
            menu.broadcastChanges();
            return;
        }
        ItemStack stack = payload.stack();
        if (stack.isEmpty()) return;
        // клиенту не верим в количестве: не больше стака
        stack = stack.copyWithCount(Math.max(1, Math.min(stack.getCount(), stack.getMaxStackSize())));
        if (mode == GivePayload.CURSOR) toCursor(player, menu, stack);
        else if (mode == GivePayload.INVENTORY) toInventory(player, stack);
    }

    /** Как в JEI: тот же предмет на курсоре дополняется до стака, иначе курсор занимает новый предмет. */
    private static void toCursor(ServerPlayer player, AbstractContainerMenu menu, ItemStack stack) {
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && ItemStack.isSameItemSameComponents(carried, stack)) {
            carried.setCount(Math.min(carried.getMaxStackSize(), carried.getCount() + stack.getCount()));
        } else if (carried.isEmpty()) {
            menu.setCarried(stack);
        } else {
            // на курсоре другой предмет: новый идёт в инвентарь, чтобы ничего не пропало
            toInventory(player, stack);
            return;
        }
        menu.broadcastChanges();
    }

    private static void toInventory(ServerPlayer player, ItemStack stack) {
        boolean added = player.getInventory().add(stack);
        if (added && stack.isEmpty()) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f,
                ((player.getRandom().nextFloat() - player.getRandom().nextFloat()) * 0.7f + 1.0f) * 2.0f);
            player.inventoryMenu.broadcastChanges();
            return;
        }
        // что не влезло, падает под ноги и подбирается только этим игроком
        ItemEntity dropped = player.drop(stack, false);
        if (dropped != null) {
            dropped.setNoPickUpDelay();
            dropped.setTarget(player.getUUID());
        }
        player.inventoryMenu.broadcastChanges();
    }
}
