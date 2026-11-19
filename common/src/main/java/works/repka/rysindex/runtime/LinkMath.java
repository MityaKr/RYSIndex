package works.repka.rysindex.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** Чистая логика сведений поставщиков ({@link Links}) без классов игры: проверяется юнит-тестами. */
public final class LinkMath {
    private LinkMath() {}

    /** Элементы по группам в порядке первого появления группы. */
    public static <T, K> Map<K, List<T>> byGroup(List<T> items, Function<T, K> key) {
        Map<K, List<T>> out = new LinkedHashMap<>();
        for (T item : items) out.computeIfAbsent(key.apply(item), k -> new ArrayList<>()).add(item);
        return out;
    }

    /** Число с пробелами между разрядами: 1 984, 12 000. */
    public static String thousands(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3 + 1);
        int lead = digits.length() % 3;
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (i - lead) % 3 == 0) out.append(' ');
            out.append(digits.charAt(i));
        }
        return value < 0 ? "-" + out : out.toString();
    }

    /** Доля «есть / нужно» для полосы, от 0 до 1. */
    public static float fraction(long have, long amount) {
        if (amount <= 0) return have > 0 ? 1f : 0f;
        return Math.max(0f, Math.min(1f, (float) have / amount));
    }

    /**
     * Обновить сведения поставщиков в готовых разделах карточки: группы тех же категорий получают новые карточки
     * на своём месте, новые категории встают в конец раздела, исчезнувшие уходят. Остальные группы не трогаются,
     * поэтому прогресс книги не пересчитывает рецепты JEI.
     */
    public static <G> List<G> replace(List<G> current, List<G> fresh, Function<G, Object> key, Predicate<G> isLink) {
        Map<Object, G> byKey = new LinkedHashMap<>();
        for (G g : fresh) byKey.put(key.apply(g), g);
        List<G> out = new ArrayList<>(current.size() + fresh.size());
        for (G g : current) {
            if (!isLink.test(g)) {
                out.add(g);
                continue;
            }
            G now = byKey.remove(key.apply(g));
            if (now != null) out.add(now);
        }
        out.addAll(byKey.values());
        return out;
    }

    /** Высота столбика блоков с промежутком между ними. */
    public static int stacked(int[] heights, int gap) {
        int sum = 0;
        for (int h : heights) sum += h;
        return heights.length == 0 ? 0 : sum + gap * (heights.length - 1);
    }

    /** Видимая часть списка высотой не больше {@code maxH} и предел его прокрутки. @return {видно, предел} */
    public static int[] window(int contentH, int maxH) {
        int shown = Math.min(contentH, Math.max(0, maxH));
        return new int[] {shown, Math.max(0, contentH - shown)};
    }
}
