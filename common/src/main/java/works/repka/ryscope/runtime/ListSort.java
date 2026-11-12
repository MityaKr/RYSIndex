package works.repka.ryscope.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.platform.Platform;

/**
 * Порядок списка ресурсов. Сортируется один раз при индексации в фоне, поэтому поиск не дорожает.
 * «mod» повторяет порядок JEI по умолчанию (мод, вид ресурса, порядок творческих вкладок): жидкости и химикаты
 * стоят рядом со своим модом, а не в самом конце списка.
 */
public final class ListSort {
    public static final List<String> PRESETS = List.of("mod", "name", "source");

    private ListSort() {}

    public static String current() {
        String preset = Config.get().listSort;
        return PRESETS.contains(preset) ? preset : "mod";
    }

    public static void cycle() {
        int i = PRESETS.indexOf(current());
        Config.get().listSort = PRESETS.get((i + 1) % PRESETS.size());
        Config.save();
        Indexer.request("порядок списка");
    }

    /** Новый список в выбранном порядке. Сортировка устойчивая: при равенстве остаётся порядок источника. */
    public static List<Stack> apply(List<Stack> stacks, Platform platform) {
        String preset = current();
        if (preset.equals("source") || stacks.size() < 2) return stacks;
        int n = stacks.size();
        String[] keys = new String[n];
        Foreign.Source foreign = Foreign.get();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            Stack s = stacks.get(i);
            try {
                keys[i] = preset.equals("name") ? s.name().getString().toLowerCase(Locale.ROOT) : modKey(s, foreign, platform, names);
            } catch (Throwable t) {
                keys[i] = "";
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int c = keys[a].compareTo(keys[b]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        List<Stack> out = new ArrayList<>(n);
        for (int i : order) out.add(stacks.get(i));
        return out;
    }

    /** Minecraft первым, затем моды по имени; внутри мода предметы, потом жидкости, потом прочие типы. */
    private static String modKey(Stack s, Foreign.Source foreign, Platform platform, java.util.Map<String, String> names) {
        String mod = foreign == null ? null : foreign.displayModId(s);
        if (mod == null) mod = s.id().getNamespace();
        String name = mod.equals("minecraft") ? "" : names.computeIfAbsent(mod, m -> platform.modName(m).toLowerCase(Locale.ROOT));
        String type = s.type();
        int rank = type.equals("item") ? 0 : type.equals("fluid") ? 1 : 2;
        return (mod.equals("minecraft") ? "0" : "1") + name + "\u0000" + mod + "\u0000" + rank;
    }
}
