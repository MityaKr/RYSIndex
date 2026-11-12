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
        /** Когда результат готов, мс по часам системы. */
        public final long readyAt = System.currentTimeMillis();

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
    /** «Доступно» считается отдельно: долгий пересчёт не должен задерживать ответ на запрос. */
    private static final ExecutorService CRAFTABLE_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RYScope-Craftable");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger GENERATION = new AtomicInteger();
    /** Сколько главного потока за тик отдаётся заранее собираемым подсказкам. */
    private static final long WARM_NANOS_PER_TICK = 2_000_000L;
    private static volatile long submittedAt;
    private static volatile long craftableMillis;
    private static volatile int examined;

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

    /** Сколько идёт текущий поиск, мс; 0, если результат готов. */
    public static long busyMillis() {
        return busy ? System.currentTimeMillis() - submittedAt : 0;
    }

    /** Сколько предметов текущий поиск уже просмотрел. */
    public static int examined() {
        return examined;
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

    /** Сколько занял последний пересчёт «Доступно», мс. */
    public static long craftableMillis() {
        return craftableMillis;
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
        CRAFTABLE_EXEC.execute(() -> {
            long start = System.nanoTime();
            try {
                Snapshot snap = Indexer.snapshot();
                IntPredicate test = Operators.filter(Query.parse("craftable"), snap);
                int n = 0;
                if (test != null) for (int i = 0; i < snap.index.size(); i++) if (test.test(i)) n++;
                craftable = n;
                craftableMillis = (System.nanoTime() - start) / 1_000_000;
            } catch (Throwable t) {
                RYScope.LOG.warn("Не посчитано «Доступно»", t);
            }
        });
    }

    public static void submit(String newText) {
        text = newText;
        int gen = GENERATION.incrementAndGet();
        if (!busy) submittedAt = System.currentTimeMillis();
        examined = 0;
        busy = true;
        String tab = filter;
        EXEC.execute(() -> {
            if (GENERATION.get() != gen) return;
            try {
                run(newText, tab, gen);
            } catch (Throwable t) {
                RYScope.LOG.warn("Поиск «{}» завершился ошибкой", newText, t);
            } finally {
                if (GENERATION.get() == gen) busy = false;
            }
        });
    }

    /** @param tab добавка вкладки фильтра: действует на весь запрос, а не на последнюю группу ИЛИ */
    private static void run(String typed, String tab, int gen) {
        long start = System.nanoTime();
        String text = tab.isEmpty() ? typed : (typed + " " + tab).trim();
        Snapshot snap = Indexer.snapshot();
        SearchIndex index = snap.index;
        Query own = Query.parse(typed);
        Query query = tab.isEmpty() ? own : Query.parse(text);
        if (own.needsTooltip()) fillTooltips(snap, gen);
        IntPredicate filter = tab.isEmpty() ? null : Operators.filter(Query.parse(tab), snap);
        IntPredicate[] groups = Operators.groupFilters(own, snap);
        int total = index.size();
        examined = 0;
        SearchIndex.Result r = index.search(own, () -> {
            // опрос идёт каждые 512 записей; нечёткий проход идёт вторым кругом, счёт не переваливает за всё
            examined = Math.min(total, examined + 512);
            return GENERATION.get() != gen;
        }, filter, groups, Config.get().fuzzySearch);
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
            SearchIndex.Result r = snap.index.search(q, () -> false, null, Operators.groupFilters(q, snap), false);
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
            if ((i & 127) == 0) {
                if (GENERATION.get() != gen) return;
                examined = i;
            }
            SearchEntry e = index.entry(i);
            if (e.tooltip == null) e.tooltip = tooltipText(snap.stacks.get(i));
        }
    }

    private static String tooltipText(Stack stack) {
        StringBuilder sb = new StringBuilder();
        try {
            List<Component> lines = stack.tooltipLines();
            for (int l = 1; l < lines.size(); l++) sb.append(lines.get(l).getString()).append('\n');
        } catch (Throwable ignored) {
            // подсказка чужого мода может не работать вне главного потока: оставляем пустой
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static Snapshot warmFor;
    private static int warmNext;

    /**
     * Тексты подсказок собираются заранее, понемногу каждый тик в главном потоке: первый поиск с {@code $}
     * не ждёт сборки по всем предметам, а подсказки модов считаются там, где им положено.
     */
    public static void warmTooltips() {
        if (Indexer.state() != Indexer.State.READY) return;
        Snapshot snap = Indexer.snapshot();
        if (snap != warmFor) {
            warmFor = snap;
            warmNext = 0;
        }
        SearchIndex index = snap.index;
        long deadline = System.nanoTime() + WARM_NANOS_PER_TICK;
        while (warmNext < index.size() && System.nanoTime() < deadline) {
            SearchEntry e = index.entry(warmNext);
            if (e.tooltip == null) e.tooltip = tooltipText(snap.stacks.get(warmNext));
            warmNext++;
        }
    }

    /** Доля предметов, чьи подсказки уже собраны заранее. */
    public static float tooltipsWarmed() {
        Snapshot snap = warmFor;
        return snap == null || snap.index.size() == 0 ? 0f : warmNext / (float) snap.index.size();
    }
}
