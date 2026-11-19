package works.repka.rysindex.runtime;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.Stack;

/** Снимок инвентаря игрока: что есть и сколько. Обновляется раз в несколько тиков в главном потоке. */
public final class InventoryView {
    private static volatile Map<Item, Integer> counts = Map.of();
    /** Растёт только при изменении содержимого: по нему фоновые расчёты узнают, что инвентарь тот же. */
    private static volatile int version;
    private static int age;
    private static boolean tracking;

    private InventoryView() {}

    public static void tick() {
        if (++age < 5) return;
        age = 0;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            if (!counts.isEmpty()) version++;
            counts = Map.of();
            tracking = false;
            return;
        }
        Map<Item, Integer> map = new HashMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty()) map.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        // то, что уже лежит в сетке крафта открытого окна, тоже есть: перенос рецепта возьмёт это первым (замечание автора)
        for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
            if (slot.container == player.getInventory() || !craftingInput(slot)) continue;
            ItemStack s = slot.getItem();
            if (!s.isEmpty()) map.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty()) map.merge(carried.getItem(), carried.getCount(), Integer::sum);
        // опубликованную карту не меняем никогда: её читают потоки поиска; при том же содержимом остаётся прежняя
        if (!map.equals(counts)) {
            counts = map;
            version++;
        }
        tracking = !player.isCreative() && !player.isSpectator();
    }

    /**
     * Ячейка сетки крафта с настоящими предметами: верстак, 2×2 игрока, сетки модов на ванильном {@code CraftingContainer},
     * сетка крафт-терминала AE2. Ячейка результата и призрачные ячейки шаблонов AE2 не считаются.
     */
    private static boolean craftingInput(net.minecraft.world.inventory.Slot slot) {
        if (slot instanceof net.minecraft.world.inventory.ResultSlot) return false;
        if (slot.container instanceof net.minecraft.world.inventory.CraftingContainer) return true;
        for (Class<?> c = slot.getClass(); c != null && c != net.minecraft.world.inventory.Slot.class; c = c.getSuperclass()) {
            if (c.getName().equals("appeng.menu.slot.CraftingMatrixSlot")) return true;
        }
        return false;
    }

    /** Номер содержимого инвентаря: меняется, только когда меняется само содержимое. */
    public static int version() {
        return version;
    }

    /** Имеет ли смысл подсвечивать недостающее (в творческом режиме нет). */
    public static boolean tracking() {
        return tracking;
    }

    public static int count(Stack stack) {
        Object key = stack.key();
        if (!(key instanceof Item item)) return 0;
        return counts.getOrDefault(item, 0);
    }

    public static boolean has(Ing ing) {
        if (ing.isEmpty()) return true;
        Map<Item, Integer> map = counts;
        for (Stack o : ing.options) {
            if (o.key() instanceof Item item && map.getOrDefault(item, 0) > 0) return true;
        }
        return false;
    }

    public static Map<Item, Integer> snapshot() {
        return counts;
    }
}
