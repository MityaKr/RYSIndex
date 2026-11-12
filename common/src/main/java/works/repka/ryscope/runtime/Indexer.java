package works.repka.ryscope.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import works.repka.ryscope.RYScope;
import works.repka.ryscope.content.LootContent;
import works.repka.ryscope.content.VanillaContent;
import works.repka.ryscope.core.search.SearchEntry;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.platform.Platform;

/**
 * Строит снимок в фоновом потоке. Главный поток занят только одним коротким шагом:
 * сбором содержимого креативных вкладок (игра не позволяет делать это вне главного потока).
 */
public final class Indexer {
    public enum State { IDLE, BUILDING, READY }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RYScope-Index");
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

    /** Сбрасывает снимок при выходе из мира. */
    public static void clear() {
        GENERATION.incrementAndGet();
        snapshot = Snapshot.EMPTY;
        state = State.IDLE;
        SearchService.onSnapshot();
    }

    public static void invalidateLanguages() {
        extraLanguagesKey = "";
    }

    public static void request(String reason) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
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
                Board.invalidate();
                state = State.READY;
                progress = 1f;
                RYScope.LOG.info("Индекс готов за {} мс ({}): ресурсов {}, рецептов {}, категорий {}",
                    built.buildMillis, reason, built.stacks.size(), built.recipeCount, built.categories.size());
                SearchService.onSnapshot();
            } catch (Throwable t) {
                RYScope.LOG.error("Индексация не удалась ({} мс)", (System.nanoTime() - start) / 1_000_000, t);
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

        step("ryscope.index.items", 0.05f);
        List<Stack> stacks = new ArrayList<>();
        Foreign.Source foreign = Foreign.get();
        if (foreign != null) {
            try {
                stacks.addAll(foreign.stacks());
            } catch (Throwable t) {
                RYScope.LOG.warn("Источник {} не отдал список ресурсов, используется свой", foreign.name(), t);
                stacks.clear();
            }
        }
        if (stacks.isEmpty() && JeiAsync.loading()) {
            // JEI сейчас сам собирает креативные вкладки в своём потоке: не трогаем их параллельно.
            // До его готовности показываем предметы по реестру, это мгновенно.
            for (Item item : BuiltInRegistries.ITEM) {
                if (item != net.minecraft.world.item.Items.AIR) stacks.add(new ItemRes(new ItemStack(item)));
            }
        } else if (stacks.isEmpty()) {
            List<ItemStack> items = mc.submit(() -> collectCreative(mc, level, player)).join();
            for (ItemStack s : items) stacks.add(new ItemRes(s));
        }
        if (stale(gen)) return null;

        step("ryscope.index.names", 0.25f);
        List<ClientLanguage> languages = languages(mc);
        Platform platform = Platform.get();
        int n = stacks.size();
        for (int i = 0; i < n; i++) {
            if ((i & 255) == 0) {
                if (stale(gen)) return null;
                progress = 0.25f + 0.45f * i / Math.max(1, n);
            }
            Stack s = stacks.get(i);
            try {
                b.addStack(s, entry(b.stackCount(), s, languages, platform));
            } catch (Throwable t) {
                RYScope.LOG.debug("Пропущен ресурс при индексации: {}", t.toString());
            }
        }

        Groups.assign(stacks);
        Stages.assign(stacks);
        step("ryscope.index.recipes", 0.72f);
        if (foreign != null) {
            try {
                java.util.Set<ResourceLocation> hidden = foreign.hiddenRecipeIds();
                if (!hidden.isEmpty()) {
                    b.hide(hidden);
                    RYScope.LOG.info("Скрытых рецептов по данным {}: {}", foreign.name(), hidden.size());
                }
            } catch (Throwable t) {
                RYScope.LOG.debug("Скрытые рецепты не получены: {}", t.toString());
            }
        }
        try {
            VanillaContent.provide(b, level.getRecipeManager(), level.registryAccess());
        } catch (Throwable t) {
            RYScope.LOG.error("Не удалось прочитать рецепты игры", t);
        }
        try {
            works.repka.ryscope.content.JsonLayout.provide(b, level.getRecipeManager(), level.registryAccess());
        } catch (Throwable t) {
            RYScope.LOG.error("Не удалось применить раскладки категорий", t);
        }
        if (stale(gen)) return null;

        step("ryscope.index.loot", 0.85f);
        try {
            LootContent.provide(b, LootSource.tables());
        } catch (Throwable t) {
            RYScope.LOG.error("Не удалось построить источники предметов", t);
        }
        try {
            works.repka.ryscope.content.TradeContent.provide(b);
            works.repka.ryscope.content.OreContent.provide(b);
        } catch (Throwable t) {
            RYScope.LOG.error("Не удалось построить торговлю и генерацию руд", t);
        }
        try {
            works.repka.ryscope.content.HelpContent.provide(b);
        } catch (Throwable t) {
            RYScope.LOG.error("Не удалось построить страницы справки", t);
        }
        if (stale(gen)) return null;

        step("ryscope.index.finish", 0.95f);
        b.buildMillis = (System.nanoTime() - start) / 1_000_000;
        return b.build();
    }

    private static void step(String key, float p) {
        step = key;
        progress = p;
    }

    private static SearchEntry entry(int index, Stack s, List<ClientLanguage> languages, Platform platform) {
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
        Foreign.Source foreign = Foreign.get();
        String shownMod = foreign == null ? null : foreign.displayModId(s);
        if (shownMod != null && !shownMod.equals(namespace)) {
            namespace += " " + shownMod;
            modName += " " + platform.modName(shownMod).toLowerCase(Locale.ROOT);
        }
        if (foreign != null) {
            // псевдонимы, которые моды зарегистрировали в JEI: по ним ищет и JEI
            for (String alias : foreign.aliases(s)) if (!alias.equals(name) && !alt.contains(alias)) alt.add(alias);
            for (String alias : foreign.modAliases(id.getNamespace())) modName += " " + alias.toLowerCase(Locale.ROOT);
        }
        List<String> tags = s.tags();
        return new SearchEntry(index, name, alt.toArray(new String[0]), id.getPath(), namespace, modName, tags.toArray(new String[0]));
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
                RYScope.LOG.debug("Язык {} не загружен для поиска: {}", code, t.toString());
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
            RYScope.LOG.warn("Не удалось прочитать креативные вкладки, список строится по реестру", t);
        }
        List<ItemStack> out = new ArrayList<>(seen);
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == net.minecraft.world.item.Items.AIR || items.contains(item)) continue;
            out.add(new ItemStack(item));
        }
        return out;
    }
}
