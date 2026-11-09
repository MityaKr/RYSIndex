package works.repka.ryscope.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import net.minecraft.network.chat.Component;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.core.search.Query;
import works.repka.ryscope.core.search.SearchEntry;
import works.repka.ryscope.core.search.SearchIndex;
import works.repka.ryscope.model.Stack;

/** Поиск в фоновом потоке. Интерфейс читает последний готовый результат и никогда не ждёт. */
public final class SearchService {
    public static final class Results {
        public final String text;
        public final Query query;
        public final List<Stack> stacks;
        public final int exactCount;
        public final Snapshot snapshot;
        public final long micros;

        Results(String text, Query query, List<Stack> stacks, int exactCount, Snapshot snapshot, long micros) {
            this.text = text;
            this.query = query;
            this.stacks = stacks;
            this.exactCount = exactCount;
            this.snapshot = snapshot;
            this.micros = micros;
        }
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RYScope-Search");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private static volatile Results results = new Results("", Query.parse(""), List.of(), 0, Snapshot.EMPTY, 0);
    private static volatile String text = "";
    private static volatile boolean busy;
    /** Добавка к запросу от вкладки фильтра, например {@code craftable}. */
    private static volatile String filter = "";
    private static volatile int craftable;
    private static int craftableHash;
    private static long craftableAt;

    private SearchService() {}

    public static Results results() {
        return results;
    }

    public static String text() {
        return text;
    }

    public static boolean busy() {
        return busy;
    }

    public static void onSnapshot() {
        submit(text);
    }

    public static void setFilter(String suffix) {
        filter = suffix;
        submit(text);
    }

    public static int craftable() {
        return craftable;
    }

    /** Считает «Доступно» в фоне, не чаще раза в две секунды и только при изменении инвентаря. */
    public static void updateCraftable() {
        long now = System.currentTimeMillis();
        if (now - craftableAt < 2000) return;
        craftableAt = now;
        var inventory = InventoryView.snapshot();
        int hash = inventory.hashCode() * 31 + Indexer.snapshot().hashCode();
        if (hash == craftableHash) return;
        craftableHash = hash;
        EXEC.execute(() -> {
            try {
                Snapshot snap = Indexer.snapshot();
                IntPredicate test = Operators.filter(Query.parse("craftable"), snap);
                int n = 0;
                if (test != null) for (int i = 0; i < snap.index.size(); i++) if (test.test(i)) n++;
                craftable = n;
            } catch (Throwable t) {
                RYScope.LOG.warn("Не посчитано «Доступно»", t);
            }
        });
    }

    public static void submit(String newText) {
        text = newText;
        int gen = GENERATION.incrementAndGet();
        busy = true;
        EXEC.execute(() -> {
            if (GENERATION.get() != gen) return;
            try {
                run(filter.isEmpty() ? newText : (newText + " " + filter).trim(), gen);
            } catch (Throwable t) {
                RYScope.LOG.warn("Поиск «{}» завершился ошибкой", newText, t);
            } finally {
                if (GENERATION.get() == gen) busy = false;
            }
        });
    }

    private static void run(String text, int gen) {
        long start = System.nanoTime();
        Snapshot snap = Indexer.snapshot();
        SearchIndex index = snap.index;
        Query query = Query.parse(text);
        if (query.needsTooltip()) fillTooltips(snap, gen);
        IntPredicate filter = Operators.filter(query, snap);
        SearchIndex.Result r = index.search(query, () -> GENERATION.get() != gen, filter, Config.get().fuzzySearch);
        if (r == null || GENERATION.get() != gen) return;
        List<Stack> out = new ArrayList<>(r.indices.length);
        Foreign.Source foreign = Foreign.get();
        boolean[] mask = Config.get().showHidden ? null : hiddenMask(snap);
        boolean hideLocked = Config.get().hideLocked && Stages.any();
        for (int i : r.indices) {
            if (mask != null && mask[i]) continue;
            Stack s = snap.stacks.get(i);
            if (foreign != null && foreign.isHidden(s)) continue;
            if (hideLocked && Stages.lockOf(s) != null) continue;
            out.add(s);
        }
        // при поиске группы не сворачиваются: игрок ищет конкретный предмет
        if (Config.get().groupItems && query.isEmpty()) out = Groups.collapse(out);
        results = new Results(text, query, out, r.exactCount, snap, (System.nanoTime() - start) / 1000);
    }

    private static Snapshot hiddenFor;
    private static String hiddenKey = "";
    private static boolean[] hiddenMask;

    /** Что скрыто запросами игрока. Пересчитывается при смене списка запросов или снимка. */
    private static boolean[] hiddenMask(Snapshot snap) {
        List<String> queries = Config.get().hiddenQueries;
        if (queries.isEmpty()) return null;
        String key = String.join("\n", queries);
        if (snap == hiddenFor && key.equals(hiddenKey)) return hiddenMask;
        boolean[] mask = new boolean[snap.index.size()];
        for (String text : new ArrayList<>(queries)) {
            Query q = Query.parse(text);
            if (q.isEmpty()) continue;
            SearchIndex.Result r = snap.index.search(q, () -> false, Operators.filter(q, snap), false);
            if (r != null) for (int i : r.indices) mask[i] = true;
        }
        hiddenFor = snap;
        hiddenKey = key;
        hiddenMask = mask;
        return mask;
    }

    /** Тексты подсказок считаются только при первом запросе с {@code $}: это самая дорогая часть индекса. */
    private static void fillTooltips(Snapshot snap, int gen) {
        SearchIndex index = snap.index;
        for (int i = 0; i < index.size(); i++) {
            if ((i & 127) == 0 && GENERATION.get() != gen) return;
            SearchEntry e = index.entry(i);
            if (e.tooltip != null) continue;
            StringBuilder sb = new StringBuilder();
            try {
                List<Component> lines = snap.stacks.get(i).tooltipLines();
                for (int l = 1; l < lines.size(); l++) sb.append(lines.get(l).getString()).append('\n');
            } catch (Throwable ignored) {
                // подсказка чужого мода может не работать вне главного потока: оставляем пустой
            }
            e.tooltip = sb.toString().toLowerCase(Locale.ROOT);
        }
    }
}
