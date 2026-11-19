package works.repka.rysindex.core.search;

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

    /** Шаблон, разобранный один раз на запрос: дальше {@link Compiled#within(String)} проверяет записи без выделений памяти. */
    public static Compiled compile(String pattern) {
        return new Compiled(pattern);
    }

    /**
     * Тот же ответ, что у {@link #containsWithinOne}, но быстрее на большом списке.
     * <ol>
     *   <li>Отсев по принципу Дирихле: одна правка задевает только одну половину шаблона, поэтому вторая половина
     *       входит в подстроку без изменений. Если в тексте нет ни одной половины, совпадения нет.</li>
     *   <li>Битово-параллельный поиск Ву и Манбера для одной правки: два слова на символ текста вместо строки таблицы.</li>
     * </ol>
     */
    public static final class Compiled {
        /** Маски для символов до этого кода (латиница и кириллица) лежат в таблице, остальные в коротком списке. */
        private static final int TABLE = 0x500;
        private final String pattern;
        private final int m;
        private final String left, right;
        private final long[] table;
        private final char[] otherChars;
        private final long[] otherMasks;

        Compiled(String pattern) {
            this.pattern = pattern;
            this.m = pattern.length();
            if (m < 2 || m > 63) {
                left = right = "";
                table = null;
                otherChars = new char[0];
                otherMasks = new long[0];
                return;
            }
            left = pattern.substring(0, m / 2);
            right = pattern.substring(m / 2);
            table = new long[TABLE];
            StringBuilder others = new StringBuilder();
            long[] masks = new long[m];
            for (int j = 0; j < m; j++) {
                char c = pattern.charAt(j);
                if (c < TABLE) {
                    table[c] |= 1L << j;
                } else {
                    int at = others.indexOf(String.valueOf(c));
                    if (at < 0) {
                        at = others.length();
                        others.append(c);
                    }
                    masks[at] |= 1L << j;
                }
            }
            otherChars = others.toString().toCharArray();
            otherMasks = java.util.Arrays.copyOf(masks, otherChars.length);
        }

        private long mask(char c) {
            if (c < TABLE) return table[c];
            for (int i = 0; i < otherChars.length; i++) if (otherChars[i] == c) return otherMasks[i];
            return 0L;
        }

        /** Есть ли в тексте подстрока на расстоянии не больше одной правки от шаблона. */
        public boolean within(String text) {
            if (m <= 1) return true;
            if (m > 63) return text.contains(pattern);
            if (text.indexOf(left) < 0 && text.indexOf(right) < 0) return false;
            // бит j: префикс шаблона длины j+1 совпадает с концом прочитанного текста без правок (r0) или с одной (r1)
            long r0 = 0L, r1 = 1L, done = 1L << (m - 1);
            for (int i = 0; i < text.length(); i++) {
                long b = mask(text.charAt(i));
                long n0 = ((r0 << 1) | 1L) & b;
                // совпадение, замена, лишний символ в тексте, пропуск символа шаблона
                long n1 = (((r1 << 1) | 1L) & b) | ((r0 << 1) | 1L) | r0 | ((n0 << 1) | 1L);
                r0 = n0;
                r1 = n1;
                if ((r1 & done) != 0) return true;
            }
            return false;
        }
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
