package works.repka.rysindex.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.RecipeView;
import works.repka.rysindex.model.Stack;

/**
 * Рецепт по умолчанию для предмета (идея EMI): им дерево крафта, расчёт и быстрый крафт делают предмет.
 * Игрок задаёт его галочкой у рецепта или выбором в дереве, сборка задаёт заранее файлами
 * {@code assets/<ns>/rysindex_recipe_defaults/*.json}: {"minecraft:stick": "minecraft:stick", ...}.
 * Выбор игрока главнее выбора сборки.
 */
public final class RecipeDefaults {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FOLDER = "rysindex_recipe_defaults";
    private static final Map<String, RecipeRef> PLAYER = new LinkedHashMap<>();
    private static volatile Map<String, RecipeRef> pack = Map.of();
    private static boolean loaded;

    private RecipeDefaults() {}

    private static String key(Stack output) {
        return output.id().toString();
    }

    @Nullable
    public static synchronized RecipeRef get(Stack output) {
        ensureLoaded();
        RecipeRef own = PLAYER.get(key(output));
        return own != null ? own : pack.get(key(output));
    }

    public static boolean isDefault(Stack output, Category category, RecipeView recipe) {
        RecipeRef ref = get(output);
        return ref != null && ref.matches(category, recipe);
    }

    /** @return стал ли рецепт рецептом по умолчанию (false: снят) */
    public static synchronized boolean toggle(Stack output, Category category, RecipeView recipe) {
        ensureLoaded();
        RecipeRef ref = RecipeRef.of(category, recipe);
        if (ref == null) return false;
        String key = key(output);
        RecipeRef current = PLAYER.get(key);
        boolean set = current == null || !current.matches(category, recipe);
        if (set) PLAYER.put(key, ref);
        else PLAYER.remove(key);
        save();
        Board.invalidate();
        return set;
    }

    /** Выбор рецепта в дереве крафта запоминается; «сырьё» снимает выбор игрока. */
    public static synchronized void remember(Stack output, @Nullable Category category, @Nullable RecipeView recipe) {
        ensureLoaded();
        RecipeRef ref = category == null || recipe == null ? null : RecipeRef.of(category, recipe);
        if (ref == null) PLAYER.remove(key(output));
        else PLAYER.put(key(output), ref);
        save();
    }

    public static synchronized void reset() {
        PLAYER.clear();
        loaded = false;
    }

    /** Заготовки сборки из ресурсов клиента. */
    public static void reload(ResourceManager manager) {
        Map<String, RecipeRef> out = new HashMap<>();
        try {
            Map<ResourceLocation, Resource> found = manager.listResources(FOLDER, p -> p.getPath().endsWith(".json"));
            found.keySet().stream().sorted().forEach(file -> {
                try (Reader reader = found.get(file).openAsReader()) {
                    JsonElement root = JsonParser.parseReader(reader);
                    if (!root.isJsonObject()) return;
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                        RecipeRef ref = RecipeRef.fromJson(e.getValue());
                        if (ref != null) out.put(e.getKey(), ref);
                    }
                } catch (Exception e) {
                    RYSIndex.LOG.warn("Рецепты по умолчанию {} не прочитаны: {}", file, e.toString());
                }
            });
        } catch (Throwable t) {
            RYSIndex.LOG.warn("Рецепты по умолчанию сборки не прочитаны: {}", t.toString());
        }
        pack = out;
    }

    private static Path file() {
        return Config.dir().resolve(Profiles.file("recipe_defaults"));
    }

    private static void ensureLoaded() {
        if (loaded || net.minecraft.client.Minecraft.getInstance().level == null) return;
        loaded = true;
        PLAYER.clear();
        Path file = file();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(r, JsonElement.class);
            if (root == null || !root.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                RecipeRef ref = RecipeRef.fromJson(e.getValue());
                if (ref != null) PLAYER.put(e.getKey(), ref);
            }
        } catch (Exception e) {
            RYSIndex.LOG.warn("Не удалось прочитать рецепты по умолчанию: {}", e.toString());
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        PLAYER.forEach((k, v) -> root.add(k, v.toJson()));
        try {
            Files.createDirectories(Config.dir());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Exception e) {
            RYSIndex.LOG.warn("Не удалось сохранить рецепты по умолчанию: {}", e.toString());
        }
    }
}
