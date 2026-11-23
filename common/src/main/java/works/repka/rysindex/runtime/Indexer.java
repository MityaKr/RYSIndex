package works.repka.rysindex.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.content.LootContent;
import works.repka.rysindex.content.VanillaContent;
import works.repka.rysindex.core.search.SearchEntry;
import works.repka.rysindex.model.ItemRes;
import works.repka.rysindex.model.Stack;
import works.repka.rysindex.platform.Platform;

/**
 * Строит снимок в фоновом потоке. Главный поток занят только одним коротким шагом:
 * сбором содержимого креативных вкладок (игра не позволяет делать это вне главного потока).
 */
public final class Indexer {
    public enum State { IDLE, BUILDING, READY }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RYSIndex-Index");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private static volatile Snapshot snapshot = Snapshot.EMPTY;
    private static volatile State state = State.IDLE;
    private static volatile String step = "";
    private static volatile float progress;
    private static volatile List<ClientLanguage> extraLanguages = List.of();
    private static volatile String extraLanguagesKey = "";

    private Indexer() {}

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static State state() {
        return state;
    }

    public static String step() {
        return step;
    }

    public static float progress() {
        return progress;
    }

    /**
     * Мир уже покинут, а новый ещё не открыт. Пока останавливается встроенный сервер, игра крутит тики со старым миром
     * и игроком, а JEI останавливается в своём потоке: без этого признака оба запроса строили новый снимок по
     * покинутому миру, и он лежал в памяти до следующего входа.
     */
    private static volatile boolean closed;

    /** Сбрасывает снимок при выходе из мира. До {@link #reopen} новые снимки не строятся. */
    public static void clear() {
        closed = true;
        GENERATION.incrementAndGet();
        snapshot = Snapshot.EMPTY;
        state = State.IDLE;
        SearchService.forget();
        SearchService.onSnapshot();
    }

    /** Мира нет (главное меню, подключение): следующий мир снова строит снимки. */
    public static void reopen() {
        closed = false;
    }

    public static void invalidateLanguages() {
        extraLanguagesKey = "";
    }

    public static void request(String reason) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || closed) return;
        int gen = GENERATION.incrementAndGet();
        state = State.BUILDING;
        progress = 0f;
        step = "…";
        EXEC.execute(() -> {
            if (GENERATION.get() != gen) return;
            long start = System.nanoTime();
            try {
                Snapshot built = build(gen);
                if (built == null || GENERATION.get() != gen) return;
                snapshot = built;
                // наборы операторов прежнего снимка больше не совпадут ни с одним запросом, а держат его целиком
                Operators.clearMemo();
                Board.invalidate();
                state = State.READY;
                progress = 1f;
                RYSIndex.LOG.info("Index ready in {} ms ({}): resources {}, recipes {}, categories {}",
                    built.buildMillis, reason, built.stacks.size(), built.recipeCount, built.categories.size());
                SearchService.onSnapshot();
            } catch (Throwable t) {
                RYSIndex.LOG.error("Indexing failed ({} ms)", (System.nanoTime() - start) / 1_000_000, t);
                if (GENERATION.get() == gen) state = State.READY;
            }
        });
    }

    private static boolean stale(int gen) {
        return GENERATION.get() != gen;
    }

    private static Snapshot build(int gen) {
        long start = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return null;
        Snapshot.Builder b = new Snapshot.Builder();

        step("rysindex.index.items", 0.05f);
        List<Stack> stacks = new ArrayList<>();
        Foreign.Source foreign = Foreign.get();
        if (foreign != null) {
            try {
                stacks.addAll(foreign.stacks());
            } catch (Throwable t) {
                RYSIndex.LOG.warn("Source {} gave no resource list, own list is used", foreign.name(), t);
                stacks.clear();
            }
        }
        if (stacks.isEmpty() && (JeiAsync.loading() || JeiAsync.expected())) {
            // JEI сейчас сам собирает креативные вкладки в своём потоке или вот-вот начнёт: не трогаем их параллельно
            // и не останавливаем главный поток ради списка, который JEI всё равно заменит своим.
            // До его готовности показываем предметы по реестру, это мгновенно.
            for (Item item : BuiltInRegistries.ITEM) {
                if (item != net.minecraft.world.item.Items.AIR) stacks.add(new ItemRes(new ItemStack(item)));
            }
        } else if (stacks.isEmpty()) {
            List<ItemStack> items = mc.submit(() -> collectCreative(mc, level, player)).join();
            for (ItemStack s : items) stacks.add(new ItemRes(s));
        }
        if (stale(gen)) return null;

        step("rysindex.index.names", 0.25f);
        List<ClientLanguage> languages = languages(mc);
        Platform platform = Platform.get();
        Strings strings = new Strings();
        stacks = ListSort.apply(stacks, platform);
        int n = stacks.size();
        for (int i = 0; i < n; i++) {
            if ((i & 255) == 0) {
                if (stale(gen)) return null;
                progress = 0.25f + 0.45f * i / Math.max(1, n);
            }
            Stack s = stacks.get(i);
            try {
                b.addStack(s, entry(b.stackCount(), s, languages, platform, strings));
            } catch (Throwable t) {
                RYSIndex.LOG.debug("Resource skipped during indexing: {}", t.toString());
            }
        }
        works.repka.rysindex.core.search.Query.setModKeys(strings.allModKeys);

        Groups.assign(stacks);
        Stages.assign(stacks);
        step("rysindex.index.recipes", 0.72f);
        if (foreign != null) {
            try {
                java.util.Set<ResourceLocation> hidden = foreign.hiddenRecipeIds();
                if (!hidden.isEmpty()) {
                    b.hide(hidden);
                    RYSIndex.LOG.info("Hidden recipes by data {}: {}", foreign.name(), hidden.size());
                }
            } catch (Throwable t) {
                RYSIndex.LOG.debug("Hidden recipes were not obtained: {}", t.toString());
            }
        }
        try {
            VanillaContent.provide(b, level.getRecipeManager(), level.registryAccess());
        } catch (Throwable t) {
            RYSIndex.LOG.error("Could not read the game recipes", t);
        }
        try {
            works.repka.rysindex.content.JsonLayout.provide(b, level.getRecipeManager(), level.registryAccess());
        } catch (Throwable t) {
            RYSIndex.LOG.error("Could not apply the category layouts", t);
        }
        if (stale(gen)) return null;

        step("rysindex.index.loot", 0.85f);
        try {
            LootContent.provide(b, LootSource.tables());
        } catch (Throwable t) {
            RYSIndex.LOG.error("Could not build the item sources", t);
        }
        try {
            works.repka.rysindex.content.TradeContent.provide(b);
            works.repka.rysindex.content.OreContent.provide(b);
        } catch (Throwable t) {
            RYSIndex.LOG.error("Could not build trades and ore generation", t);
        }
        try {
            works.repka.rysindex.content.HelpContent.provide(b);
        } catch (Throwable t) {
            RYSIndex.LOG.error("Could not build the help pages", t);
        }
        if (stale(gen)) return null;

        step("rysindex.index.finish", 0.95f);
        b.buildMillis = (System.nanoTime() - start) / 1_000_000;
        return b.build();
    }

    private static void step(String key, float p) {
        step = key;
        progress = p;
    }

    /**
     * Общие строки одной сборки: теги, мод и его имя у тысяч записей одинаковы, в индексе лежит по одной копии.
     * Теги предмета зависят только от самого предмета, поэтому у его вариантов (зелья, книги) считаются один раз.
     */
    private static final class Strings {
        final Map<String, String> pool = new HashMap<>();
        final Map<Object, String[]> itemTags = new HashMap<>();

        String of(String value) {
            String known = pool.putIfAbsent(value, value);
            return known != null ? known : value;
        }

        final Map<List<String>, String[]> modKeyArrays = new HashMap<>();
        final java.util.Set<String> allModKeys = new java.util.HashSet<>();

        /** Сжатые ключи мода записи; у тысяч записей одного мода массив общий. */
        String[] modKeys(List<String> raw) {
            String[] known = modKeyArrays.get(raw);
            if (known != null) return known;
            List<String> out = new ArrayList<>(raw.size());
            for (String r : raw) {
                String k = of(works.repka.rysindex.core.search.Query.squash(r));
                if (!k.isEmpty() && !out.contains(k)) out.add(k);
            }
            allModKeys.addAll(out);
            String[] arr = out.toArray(new String[0]);
            modKeyArrays.put(raw, arr);
            return arr;
        }

        String[] tags(Stack s) {
            boolean byItem = s instanceof ItemRes;
            if (byItem) {
                String[] known = itemTags.get(s.key());
                if (known != null) return known;
            }
            List<String> list = s.tags();
            String[] out = new String[list.size()];
            for (int i = 0; i < out.length; i++) out[i] = of(list.get(i));
            if (byItem) itemTags.put(s.key(), out);
            return out;
        }
    }

    private static SearchEntry entry(int index, Stack s, List<ClientLanguage> languages, Platform platform, Strings strings) {
        String name = s.name().getString().toLowerCase(Locale.ROOT);
        ResourceLocation id = s.id();
        List<String> alt = new ArrayList<>(languages.size());
        ItemStack item = s.item();
        if (!item.isEmpty()) {
            String key = item.getDescriptionId();
            for (ClientLanguage lang : languages) {
                String translated = lang.getOrDefault(key, "");
                if (!translated.isEmpty()) {
                    String lower = translated.toLowerCase(Locale.ROOT);
                    if (!lower.equals(name) && !alt.contains(lower)) alt.add(lower);
                }
            }
        }
        // чары зачарованной книги ищутся обычным поиском, не только по подсказке
        if (!item.isEmpty()) {
            var stored = item.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS);
            if (stored != null) {
                for (var entry : stored.entrySet()) {
                    String full = net.minecraft.world.item.enchantment.Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString().toLowerCase(Locale.ROOT);
                    if (!alt.contains(full)) alt.add(full);
                }
            }
        }
        // мод для поиска через @: и по id, и тот, к которому предмет относит JEI (книги Patchouli — к своему моду)
        String namespace = id.getNamespace(), modName = platform.modName(namespace).toLowerCase(Locale.ROOT);
        // те же id, названия и псевдонимы по отдельности и без пунктуации: «@applied energistics 2», «@aatiers»
        List<String> modKeys = new ArrayList<>(4);
        modKeys.add(namespace);
        modKeys.add(modName);
        Foreign.Source foreign = Foreign.get();
        String shownMod = foreign == null ? null : foreign.displayModId(s);
        if (shownMod != null && !shownMod.equals(namespace)) {
            String shownName = platform.modName(shownMod).toLowerCase(Locale.ROOT);
            namespace += " " + shownMod;
            modName += " " + shownName;
            modKeys.add(shownMod);
            modKeys.add(shownName);
        }
        if (foreign != null) {
            // псевдонимы, которые моды зарегистрировали в JEI: по ним ищет и JEI
            for (String alias : foreign.aliases(s)) if (!alias.equals(name) && !alt.contains(alias)) alt.add(alias);
            for (String alias : foreign.modAliases(id.getNamespace())) {
                modName += " " + alias.toLowerCase(Locale.ROOT);
                modKeys.add(alias);
            }
        }
        return new SearchEntry(index, name, alt.toArray(new String[0]), id.getPath(), strings.of(namespace), strings.of(modName),
            strings.modKeys(modKeys), strings.tags(s));
    }

    /** Языки для поиска помимо текущего. Читаются один раз, пока не сменится список или ресурсы. */
    private static List<ClientLanguage> languages(Minecraft mc) {
        List<String> wanted = new ArrayList<>(Config.get().searchLanguages);
        String current = mc.getLanguageManager().getSelected();
        if (wanted.contains("*")) {
            wanted = new ArrayList<>(mc.getLanguageManager().getLanguages().keySet());
        }
        wanted.remove(current);
        String key = current + "|" + String.join(",", wanted);
        if (key.equals(extraLanguagesKey)) return extraLanguages;
        List<ClientLanguage> out = new ArrayList<>();
        for (String code : wanted) {
            try {
                out.add(ClientLanguage.loadFrom(mc.getResourceManager(), List.of(code), false));
            } catch (Throwable t) {
                RYSIndex.LOG.debug("Language {} was not loaded for search: {}", code, t.toString());
            }
        }
        extraLanguages = out;
        extraLanguagesKey = key;
        return out;
    }

    private static List<ItemStack> collectCreative(Minecraft mc, ClientLevel level, LocalPlayer player) {
        Set<ItemStack> seen = ItemStackLinkedSet.createTypeAndComponentsSet();
        Set<Item> items = new HashSet<>();
        try {
            boolean op = mc.options.operatorItemsTab().get() && player.canUseGameMasterBlocks();
            CreativeModeTabs.tryRebuildTabContents(player.connection.enabledFeatures(), op, level.registryAccess());
            for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
                if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
                for (ItemStack s : tab.getDisplayItems()) {
                    if (!s.isEmpty() && seen.add(s)) items.add(s.getItem());
                }
            }
        } catch (Throwable t) {
            RYSIndex.LOG.warn("Could not read the creative tabs, the list is built from the registry", t);
        }
        List<ItemStack> out = new ArrayList<>(seen);
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == net.minecraft.world.item.Items.AIR || items.contains(item)) continue;
            out.add(new ItemStack(item));
        }
        return out;
    }
}
