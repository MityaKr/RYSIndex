package works.repka.rysindex.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.Reader;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.Stack;

/**
 * Станции игрока: есть ли у него то, на чём делаются рецепты категории. По этому дерево крафта выбирает способ
 * по умолчанию, а рейка категорий приглушает недоступные.
 *
 * Источники первой версии: катализатор категории лежит в инвентаре или в запомненном хранилище; меню категории
 * игрок открывал в этом мире (сверка с обработчиками переноса JEI); ручные отметки правым кликом по значку на рейке
 * (хранятся по миру). Блоки вокруг игрока не обходятся: поставленная машина без окна видна только по отметке.
 * Категория без катализатора, мультиблок и Custom Machinery считаются «неизвестно», а не «нет».
 */
public final class Stations {
    public enum State { OWNED, MISSING, UNKNOWN }

    /** Меню, которое принимает рецепты стольких категорий, считается универсальным (рюкзаки, терминалы) и не учитывается. */
    private static final int UNIVERSAL = 6;
    private static final int MENUS_KEPT = 16;
    /** Моды, чьи машины собираются из блоков в мире: наличие по катализатору у них не определить. */
    private static final Set<String> MULTIBLOCK_MODS = Set.of("custommachinery", "modular_machinery_reborn", "modularmachinery", "masterfulmachinery", "mm");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Ручные отметки: true «у меня есть», false «нет». */
    private static final Map<ResourceLocation, Boolean> MARKS = new LinkedHashMap<>();
    /** Категории, чьё меню игрок открывал в этом мире. */
    private static final Set<ResourceLocation> OPENED = new LinkedHashSet<>();
    private static final Map<ResourceLocation, State> CACHE = new HashMap<>();
    /** Категории, о которых уже спрашивали: с ними сверяется каждое новое открытое меню. */
    private static final Map<ResourceLocation, Category> KNOWN = new HashMap<>();
    /** Предметы-катализаторы уже спрошенных категорий: только их наличие меняет ответы. */
    private static final Set<Item> TRACKED = new HashSet<>();

    private record MenuRef(WeakReference<AbstractContainerMenu> menu) {}

    /** Недавно открытые меню: категории, о которых спросят позже, сверяются и с ними. */
    private static final List<MenuRef> MENUS = new ArrayList<>();
    /** Какие из отслеживаемых катализаторов есть у игрока сейчас: по смене этого растёт версия. */
    private static Set<Item> present = new HashSet<>();
    private static volatile int version;
    private static boolean loaded;
    @Nullable
    private static AbstractContainerMenu lastMenu;
    private static int age;

    private Stations() {}

    /** Номер состояния станций: растёт, когда меняется ответ хотя бы для одной категории. Входит в признаки дерева. */
    public static int version() {
        return version;
    }

    public static State state(@Nullable Category category) {
        if (category == null) return State.UNKNOWN;
        ensureLoaded();
        State cached = CACHE.get(category.id);
        if (cached != null) return cached;
        State s;
        try {
            s = compute(category);
        } catch (Throwable t) {
            s = State.UNKNOWN;
        }
        CACHE.put(category.id, s);
        return s;
    }

    /** Отметка игрока для категории: true «есть», false «нет», null «определять самому». */
    @Nullable
    public static Boolean mark(Category category) {
        ensureLoaded();
        return MARKS.get(category.id);
    }

    /** Правый клик по значку на рейке: «у меня есть» → «нет» → определять самому. @return новое состояние */
    public static State cycleMark(Category category) {
        ensureLoaded();
        Boolean mark = MARKS.get(category.id);
        if (mark == null) MARKS.put(category.id, true);
        else if (mark) MARKS.put(category.id, false);
        else MARKS.remove(category.id);
        changed();
        save();
        return state(category);
    }

    private static State compute(Category c) {
        Boolean mark = MARKS.get(c.id);
        if (mark != null) return mark ? State.OWNED : State.MISSING;
        if (OPENED.contains(c.id)) return State.OWNED;
        if (c.stations.isEmpty() || MULTIBLOCK_MODS.contains(c.id.getNamespace()) || c.id.getPath().contains("multiblock")) return State.UNKNOWN;
        KNOWN.put(c.id, c);
        boolean anyItem = false;
        for (Stack s : c.stations) {
            if (!(s.key() instanceof Item item) || item == Items.AIR) continue;
            anyItem = true;
            // верстак есть у всех: сетка 2×2 в инвентаре и стол из четырёх досок
            if (item == Items.CRAFTING_TABLE) return State.OWNED;
            TRACKED.add(item);
            // новое отслеживание не должно выглядеть изменением: наличие сразу записывается в present
            if (has(item)) {
                present.add(item);
                return State.OWNED;
            }
            present.remove(item);
        }
        if (!anyItem) return State.UNKNOWN;
        if (openedMatches(c)) {
            OPENED.add(c.id);
            save();
            return State.OWNED;
        }
        return State.MISSING;
    }

    private static boolean has(Item item) {
        return InventoryView.snapshot().getOrDefault(item, 0) > 0 || Storages.counts().getOrDefault(item, 0L) > 0;
    }

    /** Принимает ли рецепты этой категории какое-нибудь из недавно открытых меню. */
    private static boolean openedMatches(Category c) {
        Foreign.Source source = Foreign.get();
        if (source == null) return false;
        for (Iterator<MenuRef> it = MENUS.iterator(); it.hasNext(); ) {
            AbstractContainerMenu menu = it.next().menu().get();
            if (menu == null) {
                it.remove();
                continue;
            }
            if (Boolean.TRUE.equals(source.transfersTo(menu, c))) return true;
        }
        return false;
    }

    /** Каждый тик: новое открытое меню сверяется с категориями; раз в секунду проверяется наличие катализаторов. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != player.inventoryMenu && mc.screen instanceof AbstractContainerScreen<?> && menu != lastMenu) {
            lastMenu = menu;
            try {
                onMenuOpened(menu);
            } catch (Throwable t) {
                RYSIndex.LOG.debug("Станции по открытому меню не определены: {}", t.toString());
            }
        }
        if (++age < 20) return;
        age = 0;
        Set<Item> now = new HashSet<>();
        for (Item item : TRACKED) if (has(item)) now.add(item);
        if (!now.equals(present)) {
            present = now;
            changed();
        }
    }

    private static void onMenuOpened(AbstractContainerMenu menu) {
        Foreign.Source source = Foreign.get();
        // терминал AE2 принимает рецепты любой категории: станцией он не является
        if (source == null || Ae2Terminal.isTerminal(menu)) return;
        ensureLoaded();
        Map<ResourceLocation, Category> candidates = new HashMap<>(KNOWN);
        for (Category c : Indexer.snapshot().categories) candidates.putIfAbsent(c.id, c);
        List<ResourceLocation> matched = new ArrayList<>();
        for (Category c : candidates.values()) {
            // без катализатора состояние всё равно «неизвестно»
            if (c.stations.isEmpty()) continue;
            if (!Boolean.TRUE.equals(source.transfersTo(menu, c))) continue;
            matched.add(c.id);
            if (matched.size() > UNIVERSAL) return;
        }
        MENUS.add(new MenuRef(new WeakReference<>(menu)));
        if (MENUS.size() > MENUS_KEPT) MENUS.remove(0);
        if (OPENED.addAll(matched)) {
            changed();
            save();
        }
    }

    private static void changed() {
        CACHE.clear();
        version++;
    }

    /** Выход из мира: отметки и открытые меню принадлежат миру. */
    public static void reset() {
        MARKS.clear();
        OPENED.clear();
        KNOWN.clear();
        TRACKED.clear();
        MENUS.clear();
        present = new HashSet<>();
        lastMenu = null;
        loaded = false;
        changed();
    }

    private static Path file() {
        return Config.dir().resolve(Profiles.worldFile("stations"));
    }

    private static void ensureLoaded() {
        if (loaded || Minecraft.getInstance().level == null) return;
        loaded = true;
        MARKS.clear();
        OPENED.clear();
        Path file = file();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root == null) return;
            readIds(root, "owned", id -> MARKS.put(id, true));
            readIds(root, "missing", id -> MARKS.put(id, false));
            readIds(root, "opened", OPENED::add);
        } catch (Exception e) {
            RYSIndex.LOG.warn("Не удалось прочитать станции: {}", e.toString());
        }
        // ответы до загрузки (без отметок) устарели; если их не было, версия не меняется и деревья не сбрасываются
        if (!CACHE.isEmpty()) changed();
    }

    private static void readIds(JsonObject root, String key, java.util.function.Consumer<ResourceLocation> sink) {
        if (!root.has(key) || !root.get(key).isJsonArray()) return;
        for (JsonElement e : root.getAsJsonArray(key)) {
            ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
            if (id != null) sink.accept(id);
        }
    }

    private static void save() {
        if (Minecraft.getInstance().level == null) return;
        JsonObject root = new JsonObject();
        JsonArray owned = new JsonArray(), missing = new JsonArray(), opened = new JsonArray();
        MARKS.forEach((id, has) -> (has ? owned : missing).add(id.toString()));
        OPENED.forEach(id -> opened.add(id.toString()));
        root.add("owned", owned);
        root.add("missing", missing);
        root.add("opened", opened);
        try {
            Files.createDirectories(Config.dir());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Exception e) {
            RYSIndex.LOG.warn("Не удалось сохранить станции: {}", e.toString());
        }
    }
}
