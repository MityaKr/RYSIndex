package works.repka.ryscope.core.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Нечёткое сопоставление без зависимостей от игры: одна опечатка, раскладка, транслит. */
public final class Fuzzy {
    private static final String EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";
    private static final String RU = "йцукенгшщзхъфывапролджэячсмитьбюё";

    private static final String[][] TRANSLIT = {
        {"shch", "щ"}, {"sch", "щ"}, {"zh", "ж"}, {"kh", "х"}, {"ts", "ц"}, {"ch", "ч"}, {"sh", "ш"},
        {"yu", "ю"}, {"ya", "я"}, {"yo", "ё"}, {"ye", "е"}, {"ju", "ю"}, {"ja", "я"}, {"jo", "ё"},
        {"a", "а"}, {"b", "б"}, {"v", "в"}, {"g", "г"}, {"d", "д"}, {"e", "е"}, {"z", "з"}, {"i", "и"},
        {"j", "й"}, {"k", "к"}, {"l", "л"}, {"m", "м"}, {"n", "н"}, {"o", "о"}, {"p", "п"}, {"r", "р"},
        {"s", "с"}, {"t", "т"}, {"u", "у"}, {"f", "ф"}, {"h", "х"}, {"c", "к"}, {"y", "ы"}, {"w", "в"},
        {"x", "кс"}, {"q", "к"}
    };

    private Fuzzy() {}

    /** Варианты запроса: как набрано, в другой раскладке, транслитом. Первый элемент всегда исходный. */
    public static List<String> variants(String term) {
        Set<String> out = new LinkedHashSet<>();
        out.add(term);
        boolean latin = false;
        boolean cyr = false;
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c >= 'a' && c <= 'z') latin = true;
            else if (c >= 'а' && c <= 'я' || c == 'ё') cyr = true;
        }
        if (latin && !cyr) {
            out.add(swap(term, EN, RU));
            out.add(translitToCyr(term));
        } else if (cyr && !latin) {
            out.add(swap(term, RU, EN));
        }
        return new ArrayList<>(out);
    }

    static String swap(String s, String from, String to) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = from.indexOf(c);
            sb.append(idx >= 0 ? to.charAt(idx) : c);
        }
        return sb.toString();
    }

    static String translitToCyr(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        outer:
        while (i < s.length()) {
            for (String[] pair : TRANSLIT) {
                if (s.startsWith(pair[0], i)) {
                    sb.append(pair[1]);
                    i += pair[0].length();
                    continue outer;
                }
            }
            sb.append(s.charAt(i++));
        }
        return sb.toString();
    }

    /**
     * Есть ли в тексте подстрока, отличающаяся от шаблона не больше чем на одну правку
     * (замена, вставка, удаление). Шаблон до 63 символов, иначе обычный contains.
     */
    public static boolean containsWithinOne(String text, String pattern) {
        int m = pattern.length();
        if (m == 0) return true;
        if (m > 63) return text.contains(pattern);
        // Алгоритм Селлерса: prev[j] = минимальное число правок, чтобы pattern[0..j) совпал с суффиксом text[..i).
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        if (prev[m] <= 1) return true;
        for (int i = 1; i <= text.length(); i++) {
            cur[0] = 0;
            char c = text.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = pattern.charAt(j - 1) == c ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
            }
            if (cur[m] <= 1) return true;
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return false;
    }
}
