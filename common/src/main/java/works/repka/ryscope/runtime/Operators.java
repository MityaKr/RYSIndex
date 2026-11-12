package works.repka.ryscope.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import net.minecraft.world.item.Item;
import works.repka.ryscope.core.search.Query;
import works.repka.ryscope.core.search.SearchEntry;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.Stack;

/**
 * Операторы языка поиска, которым нужны рецепты, а не только названия:
 * {@code uses:X} (в рецепте есть X), {@code from:X} (делается из X), {@code in:категория},
 * {@code craftable} (всё есть в инвентаре), {@code almost} (не хватает одного).
 */
public final class Operators {
    private Operators() {}

    /** @return фильтр по номеру записи или null, если операторов в запросе нет. */
    public static IntPredicate filter(Query query, Snapshot snap) {
        List<Query.Term> ops = query.operators();
        if (ops.isEmpty()) return null;
        List<Set<Object>> allowed = new ArrayList<>();
        List<Boolean> negated = new ArrayList<>();
        for (Query.Term t : ops) {
            Set<Object> keys = keys(t, snap);
            if (keys == null) continue;
            allowed.add(keys);
            negated.add(t.negated);
        }
        if (allowed.isEmpty()) return null;
        return i -> {
            Object key = snap.stacks.get(i).key();
            for (int k = 0; k < allowed.size(); k++) {
                if (allowed.get(k).contains(key) == negated.get(k)) return false;
            }
            return true;
        };
    }

    /**
     * Наборы операторов, зависящие только от снимка (и инвентаря у {@code craftable}, {@code almost}): пока игрок
     * дописывает запрос или вкладка «Доступно» добавляет {@code craftable} к каждому запросу, набор не считается
     * заново. Последние восемь; наборы общие, их никто не меняет.
     */
    private record MemoKey(String operator, String text, Snapshot snap, int inventory) {}

    private static final Map<MemoKey, Set<Object>> MEMO = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<MemoKey, Set<Object>> eldest) {
            return size() > 8;
        }
    };

    private static Set<Object> keys(Query.Term t, Snapshot snap) {
        int inventory;
        switch (t.operator) {
            case "from", "uses", "in" -> inventory = 0;
            case "craftable", "almost" -> inventory = InventoryView.version();
            // стадии открываются по ходу игры, их наборы дешёвые и считаются каждый раз
            default -> {
                return compute(t, snap);
            }
        }
        MemoKey key = new MemoKey(t.operator, t.text, snap, inventory);
        synchronized (MEMO) {
            Set<Object> known = MEMO.get(key);
            if (known != null) return known;
        }
        Set<Object> out = compute(t, snap);
        if (out != null) {
            synchronized (MEMO) {
                MEMO.put(key, out);
            }
        }
        return out;
    }

    private static Set<Object> compute(Query.Term t, Snapshot snap) {
        Set<Object> out = new HashSet<>();
        switch (t.operator) {
            case "from": {
                // всё, что делается из X: выходы рецептов, где X на входе
                for (Stack x : named(t.text, snap)) {
                    for (Snapshot.Entry e : snap.consuming(x)) {
                        if (e.recipe().isCrafting()) for (Stack o : e.recipe().outputs()) out.add(o.key());
                    }
                }
                return out;
            }
            case "uses": {
                // всё, что нужно для X: входы рецептов, дающих X
                for (Stack x : named(t.text, snap)) {
                    for (Snapshot.Entry e : snap.producing(x)) {
                        if (!e.recipe().isCrafting()) continue;
                        for (Ing in : e.recipe().inputs()) for (Stack o : in.options) out.add(o.key());
                    }
                }
                return out;
            }
            case "in": {
                snap.categories.forEach(c -> {
                    if (!c.id.toString().contains(t.text) && !c.id.getPath().contains(t.text)) return;
                    snap.recipes(c).forEach(r -> r.outputs().forEach(o -> out.add(o.key())));
                });
                return out;
            }
            case "locked":
            case "unlocked": {
                boolean wantLocked = t.operator.equals("locked");
                for (Stack s : snap.stacks) {
                    if ((Stages.lockOf(s) != null) == wantLocked) out.add(s.key());
                }
                return out;
            }
            case "stage": {
                for (Stack s : snap.stacks) {
                    Stages.Stage st = Stages.stageOf(s);
                    if (st == null) continue;
                    String name = st.title().getString().toLowerCase(java.util.Locale.ROOT);
                    if (name.contains(t.text) || st.id.contains(t.text) || Integer.toString(st.order).equals(t.text)) out.add(s.key());
                }
                return out;
            }
            case "craftable":
            case "almost": {
                int allowedMissing = t.operator.equals("almost") ? 1 : 0;
                var inventory = InventoryView.snapshot();
                for (Stack s : snap.stacks) {
                    for (Snapshot.Entry e : snap.producing(s)) {
                        if (!e.recipe().isCrafting()) continue;
                        int missing = 0;
                        boolean any = false;
                        for (Ing in : e.recipe().inputs()) {
                            if (in.isEmpty()) continue;
                            any = true;
                            boolean have = false;
                            for (Stack o : in.options) {
                                if (o.key() instanceof Item item && inventory.getOrDefault(item, 0) > 0) {
                                    have = true;
                                    break;
                                }
                            }
                            if (!have && ++missing > allowedMissing) break;
                        }
                        boolean ok = any && (allowedMissing == 0 ? missing == 0 : missing == 1);
                        if (ok) {
                            out.add(s.key());
                            break;
                        }
                    }
                }
                return out;
            }
            default:
                return null;
        }
    }

    /** Ресурсы, название или id которых содержит текст. */
    private static List<Stack> named(String text, Snapshot snap) {
        List<Stack> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        for (int i = 0; i < snap.index.size(); i++) {
            SearchEntry e = snap.index.entry(i);
            if (e.name.contains(text) || e.id.contains(text)) {
                out.add(snap.stacks.get(i));
                if (out.size() >= 64) break;
            }
        }
        return out;
    }
}
