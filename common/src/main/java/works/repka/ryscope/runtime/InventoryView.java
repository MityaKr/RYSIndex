package works.repka.ryscope.runtime;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.Stack;

/** Снимок инвентаря игрока: что есть и сколько. Обновляется раз в несколько тиков в главном потоке. */
public final class InventoryView {
    private static volatile Map<Item, Integer> counts = Map.of();
    private static int age;
    private static boolean tracking;

    private InventoryView() {}

    public static void tick() {
        if (++age < 5) return;
        age = 0;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            counts = Map.of();
            tracking = false;
            return;
        }
        Map<Item, Integer> map = new HashMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty()) map.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty()) map.merge(carried.getItem(), carried.getCount(), Integer::sum);
        counts = map;
        tracking = !player.isCreative() && !player.isSpectator();
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
