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

        Term(Kind kind, boolean negated, String text, Pattern regex, String operator, int start, int end) {
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

    public static Query parse(String input) {
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
            if (i < n && s.charAt(i) == '"') {
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
                        case "mod" -> { kind = Kind.MOD; operator = null; }
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
