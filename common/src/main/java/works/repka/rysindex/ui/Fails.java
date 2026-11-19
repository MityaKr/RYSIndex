package works.repka.rysindex.ui;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Сколько раз подряд чужой рецепт споткнулся при пересчёте. Единичный сбой оставляет прежние значения (без моргания),
 * после {@link #LIMIT} подряд строка наличия говорит «неизвестно», а «+» гаснет. Удачный пересчёт счёт сбрасывает.
 */
public final class Fails<K> {
    public static final int LIMIT = 3;
    private final Map<K, Integer> counts = new IdentityHashMap<>();

    /** Ещё один сбой; true, когда их набралось {@link #LIMIT} подряд и прежнее значение пора сменить. */
    public boolean again(K key) {
        return counts.merge(key, 1, Integer::sum) >= LIMIT;
    }

    public void ok(K key) {
        counts.remove(key);
    }

    public void clear() {
        counts.clear();
    }
}
