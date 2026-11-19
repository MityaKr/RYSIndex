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
        /** Номера записей снимка, которые нашёл сам запрос, до скрытого и закрытых стадий. */
        public final int[] matched;
        public final int exactCount;
        public final Snapshot snapshot;
        public final long micros;
        /** Когда результат готов, мс по часам системы. */
        public final long readyAt = System.currentTimeMillis();

        Results(String text, Query query, List<Stack> stacks, int[] matched, int exactCount, Snapshot snapshot, long micros) {
            this.text = text;
            this.query = query;
            this.stacks = stacks;
            this.matched = matched;
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
    private static final long WARM_NANOS_PER_TICK = 1_000_000L;
    /**
     * Прогрев начинается не сразу после готовности индекса: первые секунды в мире главный поток занят JEI, чанками и
     * пакетами модов (разбор логики 19.11, 4.4). Поиск с {@code $} не ждёт: он прогревает быстрее и сразу.
     */
    private static final long WARM_DELAY_NANOS = 5_000_000_000L;
    /** То же, пока игрок ищет по {@code $} и ждёт подсказок, которые ещё не собраны. */
    private static final long WARM_NANOS_WANTED = 6_000_000L;
    /** Запрос с {@code $} пришёл раньше, чем собраны подсказки: сборка ускоряется, поиск повторяется по мере сборки. */
    private static volatile boolean tooltipsWanted;
    private static int wantedTicks;
    private static volatile long submittedAt;
    private static volatile long craftableMillis;
    private static volatile int examined;

    private static volatile Results results = new Results("", Query.parse(""), List.of(), new int[0], 0, Snapshot.EMPTY, 0);
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
        // подсказки модов собираются только в главном потоке (warmTooltips): из потока поиска они падают или
        // гоняются с самим модом. Пока сборка не дошла до конца, ищем по уже собранным и повторяем позже
        if (own.needsTooltip() && !tooltipsReady(snap)) tooltipsWanted = true;
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
            if (hideLocked && Stages.lockOf(s) != null) continue;
            out.add(s);
        }
        // при поиске группы не сворачиваются: игрок ищет конкретный предмет
        if (Config.get().groupItems && query.isEmpty()) out = Groups.collapse(out);
        results = new Results(text, query, out, r.indices, r.exactCount, snap, (System.nanoTime() - start) / 1000);
    }

    private static Snapshot hiddenFor;
    private static String hiddenKey = "";
    private static boolean[] hiddenMask;

    /**
     * Что скрыто игроком: запросами и по одному предмету (общий список JEI или свой).
     * Пересчитывается при смене списков или снимка: скрытие в JEI и так переиндексирует список.
     */
    private static boolean[] hiddenMask(Snapshot snap) {
        List<String> queries = Config.get().hiddenQueries;
        List<String> items = Config.get().hiddenItems;
        Foreign.Source foreign = Foreign.get();
        String key = String.join("\n", queries) + "\u0000" + String.join("\n", items) + "\u0000" + (foreign == null ? "" : foreign.name())
            // маска с запросом по $, посчитанная до конца сборки подсказок, пересчитается, когда сборка дойдёт до конца
            + "\u0000" + tooltipsReady(snap);
        if (snap == hiddenFor && key.equals(hiddenKey)) return hiddenMask;
        boolean[] mask = new boolean[snap.index.size()];
        java.util.Set<String> itemKeys = new java.util.HashSet<>(items);
        // ключ с компонентами кодируется только для предметов, у которых такие записи есть, а не для всего списка
        java.util.Set<String> variantIds = HiddenItems.variantIds(itemKeys);
        for (int i = 0; i < mask.length && i < snap.stacks.size(); i++) {
            Stack s = snap.stacks.get(i);
            try {
                mask[i] = (!itemKeys.isEmpty() && HiddenItems.hiddenLocally(s, itemKeys, variantIds)) || (foreign != null && foreign.isHidden(s));
            } catch (Throwable ignored) {
                // чужой ресурс
            }
        }
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

    /** Подсказки всех записей текущего снимка уже собраны: до этого поиск по {@code $} видит не всё. */
    public static boolean tooltipsReady() {
        return tooltipsReady(Indexer.snapshot());
    }

    /** Подсказки всех записей этого снимка уже собраны в главном потоке. */
    private static boolean tooltipsReady(Snapshot snap) {
        return warmFor == snap && warmNext >= snap.index.size();
    }

    /** Текст подсказки без первой строки (названия) или null, если подсказка чужого мода упала. */
    private static String tooltipText(Stack stack) {
        StringBuilder sb = new StringBuilder();
        try {
            List<Component> lines = stack.tooltipLines();
            for (int l = 1; l < lines.size(); l++) sb.append(lines.get(l).getString()).append('\n');
        } catch (Throwable ignored) {
            // часть строк не сохраняем: по ней поиск находил бы предмет то по одним словам, то по другим
            return null;
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static volatile Snapshot warmFor;
    private static volatile int warmNext;
    /** Раньше этого момента прогрев без запроса не идёт ({@link #WARM_DELAY_NANOS}). */
    private static long warmFrom;

    /** Снимок сброшен (выход из мира): прогрев подсказок не держит прежний снимок до следующего мира. */
    static void forget() {
        warmFor = null;
        warmNext = 0;
    }

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
            warmFrom = System.nanoTime() + WARM_DELAY_NANOS;
        }
        SearchIndex index = snap.index;
        boolean wanted = tooltipsWanted;
        if (!wanted && System.nanoTime() < warmFrom) return;
        long deadline = System.nanoTime() + (wanted ? WARM_NANOS_WANTED : WARM_NANOS_PER_TICK);
        int next = warmNext, before = next;
        while (next < index.size() && System.nanoTime() < deadline) {
            SearchEntry e = index.entry(next);
            if (e.tooltip == null) e.tooltip = tooltipText(snap.stacks.get(next));
            next++;
        }
        warmNext = next;
        // сборка дошла до конца: скрытые запросы по $ дособирают маску (её ключ учитывает готовность подсказок)
        if (before < index.size() && next >= index.size() && !Config.get().showHidden && hidesByTooltip()) submit(text);
        if (!wanted) return;
        // раз в секунду и в конце сборки поиск с $ повторяется: результат дополняется собранными подсказками
        boolean done = next >= index.size();
        if (done || ++wantedTicks % 20 == 0) {
            if (done) tooltipsWanted = false;
            String current = text;
            String full = filter.isEmpty() ? current : current + " " + filter;
            if (Query.parse(full).needsTooltip()) submit(current);
            else tooltipsWanted = false;
        }
    }

    private static boolean hidesByTooltip() {
        for (String q : Config.get().hiddenQueries) if (Query.parse(q).needsTooltip()) return true;
        return false;
    }

    /** Доля предметов, чьи подсказки уже собраны заранее. */
    public static float tooltipsWarmed() {
        Snapshot snap = warmFor;
        return snap == null || snap.index.size() == 0 ? 0f : warmNext / (float) snap.index.size();
    }
}
