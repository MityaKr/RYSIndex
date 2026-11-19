package works.repka.rysindex.runtime;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.network.chat.Component;

/**
 * Единицы измерения. Внутри мод считает энергию в FE и время в тиках,
 * а игроку показывает в выбранных единицах: все моды в одной шкале.
 */
public final class Units {
    /** Сколько единиц приходится на 1 FE. */
    private static final Map<String, Double> ENERGY = new LinkedHashMap<>();

    static {
        ENERGY.put("FE", 1.0);
        ENERGY.put("RF", 1.0);
        ENERGY.put("J", 2.5);
        ENERGY.put("EU", 0.25);
    }

    private Units() {}

    public static String energyUnit() {
        String unit = Config.get().energyUnit;
        return ENERGY.containsKey(unit) ? unit : "FE";
    }

    public static void cycleEnergyUnit() {
        String[] keys = ENERGY.keySet().toArray(new String[0]);
        String cur = energyUnit();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(cur)) {
                Config.get().energyUnit = keys[(i + 1) % keys.length];
                return;
            }
        }
    }

    public static String energy(double fe) {
        String unit = energyUnit();
        return compact(fe * ENERGY.get(unit)) + " " + unit;
    }

    public static String energyPerTick(double fe) {
        return energy(fe) + "/t";
    }

    public static String time(double ticks) {
        double seconds = ticks / 20.0;
        if (seconds >= 3600) return Component.translatable("rysindex.unit.hours", trim(seconds / 3600)).getString();
        if (seconds >= 120) return Component.translatable("rysindex.unit.minutes", trim(seconds / 60)).getString();
        return Component.translatable("rysindex.recipe.seconds", trim(seconds)).getString();
    }

    public static String compact(double v) {
        return works.repka.ryscore.ui.Numbers.compact(v);
    }

    public static String trim(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        if (Math.abs(v) >= 100) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
