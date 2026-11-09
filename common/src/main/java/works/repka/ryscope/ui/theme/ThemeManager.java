package works.repka.ryscope.ui.theme;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.runtime.Config;

/**
 * Темы читаются из ресурспаков: {@code assets/<namespace>/ryscope_themes/<имя>.json}.
 * Перечитываются при перезагрузке ресурсов (F3+T), перезапуск не нужен.
 */
public final class ThemeManager {
    public static final String FOLDER = "ryscope_themes";
    private static final String[] BUILTIN = {"slate", "vanilla", "glass"};

    private static final Map<String, Theme> THEMES = new LinkedHashMap<>();
    private static Theme current;

    private ThemeManager() {}

    public static synchronized Theme get() {
        if (current == null) {
            if (THEMES.isEmpty()) loadBuiltins();
            current = THEMES.get(Config.get().theme);
            if (current == null) current = THEMES.get("ryscope:slate");
            if (current == null) current = THEMES.values().iterator().next();
        }
        return current;
    }

    public static synchronized List<Theme> all() {
        if (THEMES.isEmpty()) loadBuiltins();
        return new ArrayList<>(THEMES.values());
    }

    public static synchronized void select(String id) {
        Config.get().theme = id;
        Config.save();
        current = null;
    }

    /** Следующая тема по кругу: для горячей клавиши и кнопки в настройках. */
    public static synchronized Theme cycle() {
        List<Theme> list = all();
        int i = list.indexOf(get());
        Theme next = list.get((i + 1) % list.size());
        select(next.id);
        return next;
    }

    public static synchronized void reload(ResourceManager manager) {
        Map<String, Theme> loaded = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> found = manager.listResources(FOLDER, p -> p.getPath().endsWith(".json"));
        List<ResourceLocation> ids = new ArrayList<>(found.keySet());
        // встроенные темы идут первыми и в порядке макетов
        ids.sort((a, b) -> Integer.compare(order(a), order(b)) != 0 ? Integer.compare(order(a), order(b)) : a.compareTo(b));
        for (ResourceLocation file : ids) {
            String path = file.getPath();
            String name = path.substring(FOLDER.length() + 1, path.length() - 5);
            String id = file.getNamespace() + ":" + name;
            try (Reader reader = found.get(file).openAsReader()) {
                loaded.put(id, new Theme(id, JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception e) {
                RYScope.LOG.warn("Не удалось прочитать тему {}: {}", file, e.toString());
            }
        }
        if (loaded.isEmpty()) return;
        THEMES.clear();
        THEMES.putAll(loaded);
        current = null;
        RYScope.LOG.info("Загружено тем: {}", THEMES.size());
    }

    private static int order(ResourceLocation file) {
        if (!file.getNamespace().equals(RYScope.MOD_ID)) return 100;
        for (int i = 0; i < BUILTIN.length; i++) if (file.getPath().endsWith("/" + BUILTIN[i] + ".json")) return i;
        return 50;
    }

    private static void loadBuiltins() {
        for (String name : BUILTIN) {
            String path = "/assets/" + RYScope.MOD_ID + "/" + FOLDER + "/" + name + ".json";
            try (InputStream in = ThemeManager.class.getResourceAsStream(path)) {
                if (in == null) continue;
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                String id = RYScope.MOD_ID + ":" + name;
                THEMES.put(id, new Theme(id, json));
            } catch (Exception e) {
                RYScope.LOG.warn("Не удалось прочитать встроенную тему {}: {}", name, e.toString());
            }
        }
        if (THEMES.isEmpty()) {
            THEMES.put("ryscope:fallback", new Theme("ryscope:fallback", new JsonObject()));
        }
    }
}
