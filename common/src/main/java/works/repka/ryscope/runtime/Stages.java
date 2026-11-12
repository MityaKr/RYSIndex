package works.repka.ryscope.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;

/**
 * Стадии прогрессии. Стадия перечисляет предметы, рецепты и категории, которые закрыты,
 * пока игрок не выполнил условие (сейчас: достижение). Закрытое помечается замком.
 *
 * Источники: {@code assets/<namespace>/ryscope_stages/*.json} и {@code config/ryscope/stages/*.json}.
 * Это клиентская подсказка: сервер рецепты не скрывает, и сами рецепты игрок выполнить может.
 */
public final class Stages {
    public static final String FOLDER = "ryscope_stages";

    public static final class Stage {
        public final String id;
        final String name;
        public final int order;
        @Nullable
        public final ResourceLocation advancement;
        /** Подсказка автора сборки, как открыть стадию: текст или ключ перевода. */
        @Nullable
        final String hint;
        final Set<String> items = new HashSet<>();
        final Set<String> tags = new HashSet<>();
        final Set<String> mods = new HashSet<>();
        final Set<String> recipes = new HashSet<>();
        final List<Pattern> recipePatterns = new ArrayList<>();
        final Set<String> categories = new HashSet<>();
        /** Сервер не даёт выполнить закрытый рецепт (как {@code StageDef.enforce}, по умолчанию true). */
        public final boolean enforce;

        Stage(String id, JsonObject json) {
            this.id = id;
            this.enforce = !json.has("enforce") || json.get("enforce").getAsBoolean();
            this.name = json.has("name") ? json.get("name").getAsString() : id;
            this.order = json.has("order") ? json.get("order").getAsInt() : 0;
            this.advancement = json.has("advancement") ? ResourceLocation.tryParse(json.get("advancement").getAsString()) : null;
            this.hint = json.has("hint") ? json.get("hint").getAsString() : null;
            if (json.has("items")) {
                for (JsonElement e : json.getAsJsonArray("items")) {
                    String s = e.getAsString();
                    if (s.startsWith("#")) tags.add(s.substring(1));
                    else if (s.startsWith("@")) mods.add(s.substring(1));
                    else items.add(s);
                }
            }
            if (json.has("recipes")) {
                for (JsonElement e : json.getAsJsonArray("recipes")) {
                    String s = e.getAsString();
                    if (s.length() > 2 && s.startsWith("/") && s.endsWith("/")) recipePatterns.add(Pattern.compile(s.substring(1, s.length() - 1)));
                    else recipes.add(s);
                }
            }
            if (json.has("categories")) for (JsonElement e : json.getAsJsonArray("categories")) categories.add(e.getAsString());
        }

        public Component title() {
            return Language.getInstance().has(name) ? Component.translatable(name) : Component.literal(name);
        }

        boolean ownsItem(Stack stack) {
            return ownsItem(stack.id().toString(), stack.namespace(), stack.tags());
        }

        /** id, мод и теги ресурса считаются один раз на ресурс, а не на каждую стадию. */
        boolean ownsItem(String id, String namespace, List<String> stackTags) {
            if (items.contains(id) || mods.contains(namespace)) return true;
            if (!tags.isEmpty()) for (String t : stackTags) if (tags.contains(t)) return true;
            return false;
        }

        boolean ownsRecipe(@Nullable ResourceLocation id, Category category) {
            if (categories.contains(category.id.toString())) return true;
            if (id == null) return false;
            String s = id.toString();
            if (recipes.contains(s)) return true;
            for (Pattern p : recipePatterns) if (p.matcher(s).matches()) return true;
            return false;
        }
    }

    private static volatile List<Stage> stages = List.of();
    /** Стадии из файлов клиента: действуют, пока сервер не прислал свои. */
    private static volatile List<Stage> localStages = List.of();
    /** Сервер прислал стадии и состояние: клиент показывает его решение и сам ничего не считает. */
    private static volatile boolean serverMode;
    private static volatile Map<Object, Stage> byKey = Map.of();
    private static volatile Set<String> unlocked = Set.of();
    /** Стадии, открытые другим модом через {@link #setUnlocked}. */
    private static final Set<String> external = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static int age;
    private static Field progressField;
    private static boolean reflectionFailed;

    private Stages() {}

    public static boolean any() {
        return !stages.isEmpty();
    }

    public static List<Stage> all() {
        return stages;
    }

    public static void reload(ResourceManager manager) {
        List<Stage> loaded = new ArrayList<>();
        for (var e : manager.listResources(FOLDER, p -> p.getPath().endsWith(".json")).entrySet()) {
            String path = e.getKey().getPath();
            String id = e.getKey().getNamespace() + ":" + path.substring(FOLDER.length() + 1, path.length() - 5);
            try (Reader reader = e.getValue().openAsReader()) {
                loaded.add(new Stage(id, JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception ex) {
                RYScope.LOG.warn("Стадия {} не прочитана: {}", e.getKey(), ex.toString());
            }
        }
        Path dir = Config.dir().resolve("stages");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".json")).sorted().forEach(f -> {
                    String name = f.getFileName().toString();
                    try (Reader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                        loaded.add(new Stage("config:" + name.substring(0, name.length() - 5), JsonParser.parseReader(reader).getAsJsonObject()));
                    } catch (Exception ex) {
                        RYScope.LOG.warn("Стадия {} не прочитана: {}", f, ex.toString());
                    }
                });
            } catch (Exception ex) {
                RYScope.LOG.warn("Папка стадий не прочитана: {}", ex.toString());
            }
        }
        loaded.sort((a, b) -> Integer.compare(a.order, b.order));
        localStages = loaded;
        if (!serverMode) stages = loaded;
    }

    /** Привязывает ресурсы к стадиям. Вызывается из потока индексации. */
    public static void assign(List<Stack> stacks) {
        List<Stage> defs = stages;
        Map<Object, Stage> map = new HashMap<>();
        if (!defs.isEmpty()) {
            // у предметов id, мод и теги зависят только от самого предмета: каждый предмет проверяется один раз
            java.util.Set<Object> seenItems = new java.util.HashSet<>();
            for (Stack s : stacks) {
                if (!s.item().isEmpty() && !seenItems.add(s.key())) continue;
                String id, namespace;
                List<String> stackTags;
                try {
                    id = s.id().toString();
                    namespace = s.namespace();
                    stackTags = s.tags();
                } catch (Throwable ignored) {
                    // теги чужого ресурса
                    continue;
                }
                for (Stage st : defs) {
                    if (st.ownsItem(id, namespace, stackTags)) {
                        map.put(s.key(), st);
                        break;
                    }
                }
            }
        }
        byKey = map;
    }

    /** Для других модов: открыть или закрыть стадию по своему условию (квест, уровень, что угодно). */
    public static void setUnlocked(String stageId, boolean value) {
        if (value) external.add(stageId);
        else external.remove(stageId);
    }

    public static boolean isUnlocked(Stage stage) {
        return unlocked.contains(stage.id) || external.contains(stage.id);
    }

    /** Стадия ресурса независимо от того, открыта ли она. */
    @Nullable
    public static Stage stageOf(Stack stack) {
        return byKey.get(stack.key());
    }

    /** Стадия, которая сейчас закрывает ресурс, или null. */
    @Nullable
    public static Stage lockOf(Stack stack) {
        Map<Object, Stage> map = byKey;
        if (map.isEmpty()) return null;
        Stage st = map.get(stack.key());
        return st == null || isUnlocked(st) ? null : st;
    }

    /** Стадия, которая сейчас закрывает рецепт: по id, по категории или по закрытому результату. */
    @Nullable
    public static Stage lockOf(RecipeView recipe, Category category) {
        List<Stage> defs = stages;
        if (defs.isEmpty() || !recipe.isCrafting()) return null;
        ResourceLocation id = recipe.id();
        for (Stage st : defs) {
            if (!isUnlocked(st) && st.ownsRecipe(id, category)) return st;
        }
        for (Stack out : recipe.outputs()) {
            Stage st = lockOf(out);
            if (st != null) return st;
        }
        return null;
    }

    /** Готовая строка «как открыть стадию»: подсказка автора сборки или название достижения, если клиент его знает. */
    public static String requirement(Stage stage) {
        Minecraft mc = Minecraft.getInstance();
        if (stage.hint != null) {
            return Language.getInstance().has(stage.hint) ? Language.getInstance().getOrDefault(stage.hint) : stage.hint;
        }
        if (stage.advancement == null || mc.getConnection() == null) return "";
        // клиент знает только достижения, которые ему уже показаны: о прочих молчим, а не выводим id
        AdvancementHolder holder = mc.getConnection().getAdvancements().get(stage.advancement);
        if (holder == null) return "";
        return holder.value().display()
            .map(d -> Component.translatable("ryscope.stage.requires", d.getTitle()).getString()).orElse("");
    }

    /** Раз в две секунды сверяет прогресс достижений. */
    @SuppressWarnings("unchecked")
    public static void tick() {
        if (serverMode || stages.isEmpty() || ++age % 40 != 1) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || reflectionFailed) return;
        try {
            if (progressField == null) {
                progressField = ClientAdvancements.class.getDeclaredField("progress");
                progressField.setAccessible(true);
            }
            Map<AdvancementHolder, AdvancementProgress> progress =
                (Map<AdvancementHolder, AdvancementProgress>) progressField.get(mc.getConnection().getAdvancements());
            Set<ResourceLocation> done = new HashSet<>();
            for (Map.Entry<AdvancementHolder, AdvancementProgress> e : progress.entrySet()) {
                if (e.getValue().isDone()) done.add(e.getKey().id());
            }
            Set<String> open = new HashSet<>();
            for (Stage st : stages) {
                if (st.advancement == null || done.contains(st.advancement)) open.add(st.id);
            }
            if (!open.equals(unlocked)) {
                unlocked = open;
                // список мог измениться, если закрытое скрыто
                if (Config.get().hideLocked) SearchService.submit(SearchService.text());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionFailed = true;
            RYScope.LOG.warn("Прогресс достижений недоступен, стадии считаются закрытыми: {}", e.toString());
        }
    }

    public static void reset() {
        unlocked = Set.of();
        age = 0;
        serverMode = false;
        stages = localStages;
    }

    /** Сервер отказал в крафте: показываем, какая стадия мешает и чем она открывается. */
    public static void showBlocked(String stageId) {
        for (Stage st : stages) {
            if (!st.id.equals(stageId)) continue;
            String text = Component.translatable("ryscope.stage.blocked", st.title()).getString();
            String need = requirement(st);
            if (!need.isEmpty()) text += " · " + need;
            works.repka.ryscope.ui.Overlay.toast(text);
            return;
        }
    }

    public static boolean serverMode() {
        return serverMode;
    }

    public static Set<String> unlockedIds() {
        return unlocked;
    }

    /** Стадии и их состояние от сервера. Вызывается в главном потоке при входе и при каждом изменении. */
    public static void applyServer(List<String> ids, List<String> jsons, Set<String> open) {
        List<Stage> list = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            try {
                list.add(new Stage(ids.get(i), JsonParser.parseString(jsons.get(i)).getAsJsonObject()));
            } catch (Exception e) {
                RYScope.LOG.warn("Стадия {} от сервера не разобрана: {}", ids.get(i), e.toString());
            }
        }
        list.sort((a, b) -> Integer.compare(a.order, b.order));
        serverMode = true;
        stages = list;
        unlocked = Set.copyOf(open);
        assign(Indexer.snapshot().stacks);
        SearchService.submit(SearchService.text());
    }
}
