package works.repka.ryscope.core.search;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/** Поиск по списку записей. Не зависит от игры, работает в фоновом потоке. */
public final class SearchIndex {
    public static final class Result {
        public final int[] indices;
        /** Сколько найдено точным совпадением; остальное добавлено нечётким поиском. */
        public final int exactCount;

        Result(int[] indices, int exactCount) {
            this.indices = indices;
            this.exactCount = exactCount;
        }
    }

    private final SearchEntry[] entries;

    public SearchIndex(List<SearchEntry> entries) {
        this.entries = entries.toArray(new SearchEntry[0]);
    }

    public int size() {
        return entries.length;
    }

    public SearchEntry entry(int i) {
        return entries[i];
    }

    /**
     * @param cancelled проверяется каждые 512 записей; при отмене возвращается null
     * @param operatorFilter фильтр для операторов (uses:, craftable…), которые ядро поиска само не вычисляет; может быть null
     */
    public Result search(Query query, BooleanSupplier cancelled, IntPredicate operatorFilter, boolean allowFuzzy) {
        int n = entries.length;
        if (query.isEmpty() && operatorFilter == null) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            return new Result(all, n);
        }
        boolean onlyOperators = true;
        for (Query.Term t : query.terms()) if (t.kind != Query.Kind.OPERATOR) onlyOperators = false;
        long[] ranked = new long[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if ((i & 511) == 0 && cancelled.getAsBoolean()) return null;
            if (operatorFilter != null && !operatorFilter.test(i)) continue;
            int rank = onlyOperators ? 0 : match(entries[i], query, false);
            if (rank >= 0) ranked[count++] = ((long) rank << 32) | i;
        }
        int exact = count;
        if (allowFuzzy && count < 12 && !onlyOperators && hasFuzzyTerms(query)) {
            boolean[] taken = new boolean[n];
            for (int k = 0; k < count; k++) taken[(int) ranked[k]] = true;
            for (int i = 0; i < n; i++) {
                if ((i & 511) == 0 && cancelled.getAsBoolean()) return null;
                if (taken[i]) continue;
                if (operatorFilter != null && !operatorFilter.test(i)) continue;
                int rank = match(entries[i], query, true);
                if (rank >= 0) ranked[count++] = ((long) (rank + 16) << 32) | i;
            }
        }
        Arrays.sort(ranked, 0, count);
        int[] out = new int[count];
        for (int k = 0; k < count; k++) out[k] = (int) ranked[k];
        return new Result(out, exact);
    }

    private static boolean hasFuzzyTerms(Query q) {
        for (Query.Term t : q.terms()) {
            if (t.kind == Query.Kind.NAME && t.regex == null && !t.negated && t.text.length() >= 4) return true;
        }
        return false;
    }

    /** @return ранг (меньше = лучше) или -1, если запись не подходит. */
    static int match(SearchEntry e, Query q, boolean fuzzy) {
        int best = -1;
        for (List<Query.Term> group : q.groups) {
            if (group.isEmpty()) continue;
            int rank = 0;
            boolean ok = true;
            for (Query.Term t : group) {
                if (t.kind == Query.Kind.OPERATOR) continue;
                int r = matchTerm(e, t, fuzzy);
                if (t.negated) {
                    if (r >= 0) { ok = false; break; }
                } else {
                    if (r < 0) { ok = false; break; }
                    rank = Math.max(rank, r);
                }
            }
            if (ok && (best < 0 || rank < best)) best = rank;
        }
        return best;
    }

    private static int matchTerm(SearchEntry e, Query.Term t, boolean fuzzy) {
        switch (t.kind) {
            case MOD:
                return matches(e.namespace, t) || matches(e.modName, t) ? 0 : -1;
            case TAG:
                for (String tag : e.tags) if (matches(tag, t)) return 0;
                return -1;
            case ID:
                return matches(e.id, t) ? 0 : -1;
            case TOOLTIP: {
                String tip = e.tooltip;
                if (tip == null) return -1;
                if (t.regex != null) return t.regex.matcher(tip).find() ? 0 : -1;
                for (String v : t.variants) if (tip.contains(v)) return 0;
                return -1;
            }
            default:
                return matchName(e, t, fuzzy);
        }
    }

    private static boolean matches(String haystack, Query.Term t) {
        if (t.regex != null) return t.regex.matcher(haystack).find();
        return haystack.contains(t.text);
    }

    private static int matchName(SearchEntry e, Query.Term t, boolean fuzzy) {
        if (t.regex != null) {
            if (t.regex.matcher(e.name).find()) return 2;
            for (String alt : e.altNames) if (t.regex.matcher(alt).find()) return 3;
            return -1;
        }
        int best = -1;
        for (int v = 0; v < t.variants.size(); v++) {
            String term = t.variants.get(v);
            int penalty = v == 0 ? 0 : 4;
            int r = rankIn(e.name, term);
            if (r >= 0) best = min(best, r + penalty);
            if (best == 0) return 0;
            for (String alt : e.altNames) {
                r = rankIn(alt, term);
                if (r >= 0) best = min(best, r + 1 + penalty);
            }
            if (e.id.contains(term)) best = min(best, 3 + penalty);
        }
        if (best >= 0 || !fuzzy || t.text.length() < 4) return best;
        for (String term : t.variants) {
            if (Fuzzy.containsWithinOne(e.name, term)) return 8;
            for (String alt : e.altNames) if (Fuzzy.containsWithinOne(alt, term)) return 9;
        }
        return -1;
    }

    private static int min(int best, int r) {
        return best < 0 ? r : Math.min(best, r);
    }

    /** 0 = начало названия, 1 = начало слова, 2 = где-то внутри. */
    private static int rankIn(String text, String term) {
        int at = text.indexOf(term);
        if (at < 0) return -1;
        if (at == 0) return 0;
        while (at > 0) {
            if (!Character.isLetterOrDigit(text.charAt(at - 1))) return 1;
            at = text.indexOf(term, at + 1);
        }
        return 2;
    }
}
