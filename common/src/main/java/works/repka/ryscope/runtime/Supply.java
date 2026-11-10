package works.repka.ryscope.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Ae2Terminal.State;

/**
 * Что из ингредиентов рецепта уже есть, с учётом количества, как в JEI: предметы инвентаря (и сети AE2,
 * если окно открыто над терминалом) раскладываются по ячейкам, одна пластина закрывает только одну ячейку.
 */
public final class Supply {
    private Supply() {}

    /** Отмечать ли нехватку вообще: в творческом режиме и без терминала вне инвентаря нет. */
    public static boolean tracking() {
        return Ae2Terminal.active() || InventoryView.tracking();
    }

    /** Состояние каждого ингредиента по порядку. Пустые и не предметы (жидкости) считаются имеющимися. */
    public static State[] states(List<Ing> ings) {
        State[] out = new State[ings.size()];
        Arrays.fill(out, State.HAVE);
        if (Ae2Terminal.active() && Ae2Terminal.isPattern()) {
            // в шаблоне важно только «можно заказать», количество не нужно
            for (int i = 0; i < out.length; i++) out[i] = Ae2Terminal.stateOf(ings.get(i));
            return out;
        }
        if (!tracking()) return out;
        boolean network = Ae2Terminal.active();
        Map<Item, Integer> inventory = InventoryView.snapshot();
        Map<Item, Long> stored = network ? Ae2Terminal.amounts() : Map.of();
        Map<Item, Long> used = new HashMap<>();
        // сначала ингредиенты с меньшим выбором: так общий предмет не уходит туда, где есть замена
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ings.size(); i++) if (counted(ings.get(i))) order.add(i);
        order.sort((a, b) -> Integer.compare(ings.get(a).options.size(), ings.get(b).options.size()));
        for (int i : order) {
            Ing ing = ings.get(i);
            boolean taken = false;
            for (Stack option : ing.options) {
                if (!(option.key() instanceof Item item)) continue;
                long need = Math.max(1, option.amount);
                long free = inventory.getOrDefault(item, 0) + stored.getOrDefault(item, 0L) - used.getOrDefault(item, 0L);
                if (free >= need) {
                    used.merge(item, need, Long::sum);
                    taken = true;
                    break;
                }
            }
            if (!taken) out[i] = network && Ae2Terminal.craftable(ing) ? State.CRAFTABLE : State.MISSING;
        }
        return out;
    }

    /** Ингредиент из предметов, который надо проверять. */
    private static boolean counted(Ing ing) {
        if (ing.isEmpty()) return false;
        for (Stack option : ing.options) if (!(option.key() instanceof Item)) return false;
        return true;
    }
}
