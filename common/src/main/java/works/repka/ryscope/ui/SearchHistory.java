package works.repka.ryscope.ui;

import java.util.LinkedList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Прошлые запросы поиска, как в JEI: запрос запоминается, когда поле получает или теряет фокус,
 * стрелка вверх возвращает предыдущий, вниз следующий, после самого нового поле пустеет. Живёт до выхода из игры.
 */
final class SearchHistory {
    private static final int LIMIT = 100;
    private static final List<String> HISTORY = new LinkedList<>();

    private SearchHistory() {}

    static boolean add(String query) {
        if (query.isEmpty()) return false;
        HISTORY.remove(query);
        HISTORY.add(query);
        if (HISTORY.size() > LIMIT) HISTORY.remove(0);
        return true;
    }

    /** Запрос перед текущим; набранное, которого нет в истории, запоминается, чтобы к нему можно было вернуться. */
    @Nullable
    static String previous(String current) {
        int index = HISTORY.indexOf(current);
        if (index < 0) index = add(current) ? HISTORY.size() - 1 : HISTORY.size();
        return index <= 0 ? null : HISTORY.get(index - 1);
    }

    /** Запрос после текущего, пустая строка после самого нового, {@code null}, если текущего в истории нет. */
    @Nullable
    static String next(String current) {
        int index = HISTORY.indexOf(current);
        if (index < 0) return null;
        return index + 1 < HISTORY.size() ? HISTORY.get(index + 1) : "";
    }
}
