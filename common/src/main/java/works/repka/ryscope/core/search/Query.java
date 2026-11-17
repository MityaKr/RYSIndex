package works.repka.ryscope.core.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Разобранный поисковый запрос.
 * Синтаксис: слова через пробел = И, {@code |} = ИЛИ, {@code -слово} = исключение, {@code "фраза"},
 * {@code @мод}, {@code #тег}, {@code $тултип}, {@code *id}, {@code /regex/}, операторы {@code ключ:значение}.
 * <p>{@code @мод} сравнивается с id мода и его названием без учёта регистра, пробелов и пунктуации.
 * Название из нескольких слов пишется как есть: следующие слова присоединяются к {@code @}, пока вместе они
 * ещё совпадают с каким-нибудь модом ({@code @applied energistics 2 cable} = мод AE2 и слово «cable»).
 * Кавычки {@code @"…"} задают границу явно.
 */
public final class Query {
    public enum Kind { NAME, MOD, TAG, TOOLTIP, ID, OPERATOR }

    public static final class Term {
        public final Kind kind;
        public final boolean negated;
        public final String text;
        public final List<String> variants;
        public final Pattern regex;
        /** Для операторов: uses, from, in, craftable и т.п. */
        public final String operator;
        /** Позиция в исходной строке, для подсветки. */
        public final int start;
        public final int end;

        /** Для {@link Kind#MOD}: текст без пробелов и пунктуации, см. {@link Query#squash}. */
        public final String squashed;

        Term(Kind kind, boolean negated, String text, Pattern regex, String operator, int start, int end) {
            this.squashed = kind == Kind.MOD ? squash(text) : text;
            this.kind = kind;
            this.negated = negated;
            this.text = text;
            this.regex = regex;
            this.operator = operator;
            this.start = start;
            this.end = end;
            this.variants = kind == Kind.NAME || kind == Kind.TOOLTIP ? Fuzzy.variants(text) : List.of(text);
        }

        private volatile Fuzzy.Compiled[] fuzzy;

        /** Варианты, разобранные для нечёткого поиска: строятся при первом нечётком проходе, не при каждом разборе. */
        Fuzzy.Compiled[] fuzzy() {
            Fuzzy.Compiled[] out = fuzzy;
            if (out == null) {
                out = new Fuzzy.Compiled[variants.size()];
                for (int i = 0; i < out.length; i++) out[i] = Fuzzy.compile(variants.get(i));
                fuzzy = out;
            }
            return out;
        }
    }

    /** ИЛИ-группы, внутри группы И. */
    public final List<List<Term>> groups;
    public final String source;

    private Query(String source, List<List<Term>> groups) {
        this.source = source;
        this.groups = groups;
    }

    public boolean isEmpty() {
        for (List<Term> g : groups) if (!g.isEmpty()) return false;
        return true;
    }

    public boolean needsTooltip() {
        for (List<Term> g : groups) for (Term t : g) if (t.kind == Kind.TOOLTIP) return true;
        return false;
    }

    private volatile List<Term> terms;

    /** Все условия по порядку. Список общий и неизменяемый: собирается один раз на запрос. */
    public List<Term> terms() {
        List<Term> out = terms;
        if (out == null) {
            List<Term> all = new ArrayList<>();
            for (List<Term> g : groups) all.addAll(g);
            terms = out = java.util.Collections.unmodifiableList(all);
        }
        return out;
    }

    public List<Term> operators() {
        List<Term> out = new ArrayList<>();
        for (List<Term> g : groups) for (Term t : g) if (t.kind == Kind.OPERATOR) out.add(t);
        return out;
    }

    /** Сжатые id и названия всех модов списка: по ним {@code @} решает, забрать ли следующее слово. */
    private static volatile String[] modKeys = new String[0];

    /** Индекс сообщает, какие моды есть в списке. Ключи уже сжаты {@link #squash}. */
    public static void setModKeys(java.util.Collection<String> keys) {
        modKeys = keys.toArray(new String[0]);
    }

    /** Строка в нижнем регистре только из букв и цифр: «Actually Additions: Tiers» → «actuallyadditionstiers». */
    public static String squash(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int k = 0; k < text.length(); k++) {
            char ch = text.charAt(k);
            if (Character.isLetterOrDigit(ch)) out.append(Character.toLowerCase(ch));
        }
        return out.toString();
    }

    private static boolean anyModContains(String squashed, String[] keys) {
        if (squashed.isEmpty()) return false;
        for (String key : keys) if (key.contains(squashed)) return true;
        return false;
    }

    /**
     * Следующее слово после {@code pos}, если это простое слово (не префикс, не группа, не кавычки, не оператор)
     * и вместе с уже набранным текстом мода оно ещё входит в чьё-то id или название. Возвращает конец слова или -1.
     */
    private static int modWordEnd(String s, int pos, String soFar, String[] keys) {
        int n = s.length();
        int j = pos;
        while (j < n && Character.isWhitespace(s.charAt(j))) j++;
        if (j >= n || j == pos) return -1;
        char c = s.charAt(j);
        if (c == '|' || c == '@' || c == '#' || c == '$' || c == '*' || c == '"' || c == '/' || c == '-') return -1;
        int end = j;
        while (end < n && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != '|') end++;
        String word = s.substring(j, end);
        int colon = word.indexOf(':');
        if (colon > 0 && colon < word.length() - 1 && isOperator(word.substring(0, colon))) return -1;
        if (isFlag(word)) return -1;
        String joined = squash(soFar + word);
        // слово из одной пунктуации («actually additions : tiers») ничего не меняет и уходит в название мода
        if (joined.equals(squash(soFar))) return end;
        return anyModContains(joined, keys) ? end : -1;
    }

    public static Query parse(String input) {
        String[] keys = modKeys;
        String s = input.toLowerCase(Locale.ROOT);
        List<List<Term>> groups = new ArrayList<>();
        List<Term> current = new ArrayList<>();
        groups.add(current);
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '|') {
                current = new ArrayList<>();
                groups.add(current);
                i++;
                continue;
            }
            int start = i;
            boolean neg = false;
            if (c == '-' && i + 1 < n && !Character.isWhitespace(s.charAt(i + 1))) {
                neg = true;
                i++;
                c = s.charAt(i);
            }
            Kind kind = Kind.NAME;
            if (c == '@') { kind = Kind.MOD; i++; }
            else if (c == '#') { kind = Kind.TAG; i++; }
            else if (c == '$') { kind = Kind.TOOLTIP; i++; }
            else if (c == '*') { kind = Kind.ID; i++; }
            String text;
            Pattern regex = null;
            boolean quoted = false;
            int textStart = i;
            if (i < n && s.charAt(i) == '"') {
                quoted = true;
                int close = s.indexOf('"', i + 1);
                if (close < 0) close = n;
                text = s.substring(i + 1, close);
                i = Math.min(n, close + 1);
            } else if (i < n && s.charAt(i) == '/' && s.indexOf('/', i + 1) > i) {
                int close = i + 1;
                while (close < n && !(s.charAt(close) == '/' && s.charAt(close - 1) != '\\')) close++;
                text = input.substring(i + 1, Math.min(close, n));
                i = Math.min(n, close + 1);
                try {
                    regex = Pattern.compile(text, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                } catch (PatternSyntaxException e) {
                    regex = null;
                    text = text.toLowerCase(Locale.ROOT);
                }
            } else {
                int end = i;
                while (end < n && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != '|') end++;
                text = s.substring(i, end);
                i = end;
            }
            String operator = null;
            if (kind == Kind.NAME && regex == null) {
                int colon = text.indexOf(':');
                if (colon > 0 && isOperator(text.substring(0, colon))) {
                    operator = text.substring(0, colon);
                    text = text.substring(colon + 1);
                    switch (operator) {
                        case "mod" -> { kind = Kind.MOD; operator = null; textStart += colon + 1; }
                        case "tag" -> { kind = Kind.TAG; operator = null; }
                        case "id" -> { kind = Kind.ID; operator = null; }
                        case "tip", "tooltip" -> { kind = Kind.TOOLTIP; operator = null; }
                        default -> kind = Kind.OPERATOR;
                    }
                } else if (isFlag(text)) {
                    operator = text;
                    text = "";
                    kind = Kind.OPERATOR;
                }
            }
            if (kind == Kind.MOD && regex == null && !quoted && !text.isEmpty() && keys.length > 0) {
                // название мода из нескольких слов: «@applied energistics 2», «@actually additions: tiers»
                int end;
                while ((end = modWordEnd(s, i, text, keys)) > 0) {
                    text = s.substring(textStart, end);
                    i = end;
                }
            }
            if (text.isEmpty() && kind != Kind.OPERATOR) continue;
            current.add(new Term(kind, neg, text, regex, operator, start, i));
        }
        return new Query(input, groups);
    }

    private static boolean isOperator(String key) {
        return switch (key) {
            case "mod", "tag", "id", "tip", "tooltip", "uses", "from", "in", "drop", "loot", "stage" -> true;
            default -> false;
        };
    }

    private static boolean isFlag(String text) {
        return text.equals("craftable") || text.equals("almost") || text.equals("locked") || text.equals("unlocked");
    }
}
