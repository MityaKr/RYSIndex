package works.repka.ryscope.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.content.OreData;
import works.repka.ryscope.platform.Platform;

/**
 * Откуда клиент берёт генерацию руд. Данные генерации клиенту не синхронизируются, поэтому по порядку:
 * встроенный сервер одиночной игры; данные от сервера, если RYScope стоит и там; файл
 * {@code config/ryscope/ores-cache.json}, записанный в одиночной игре с тем же набором модов.
 */
public final class OreSource {
    public enum Origin { LIVE, SERVER, CACHE, NONE }

    public record Result(Map<String, List<OreData.Range>> ores, Origin origin) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE = "ores-cache.json";

    @Nullable
    private static volatile Map<String, List<OreData.Range>> synced;
    /** Реестры мира, по которым прочитано live: слабая ссылка, чтобы не держать закрытый мир в памяти. */
    private static java.lang.ref.WeakReference<Object> liveFor = new java.lang.ref.WeakReference<>(null);
    private static Map<String, List<OreData.Range>> live = Map.of();
    /** Файл кеша читается один раз за сеанс игры; запись в одиночной игре обновляет и эту копию. */
    private static boolean fileRead;
    @Nullable
    private static Map<String, List<OreData.Range>> file;
    @Nullable
    private static String key;

    private OreSource() {}

    /** Данные от сервера в сетевой игре. */
    public static void setSynced(Map<String, List<OreData.Range>> ores) {
        synced = ores;
        Indexer.request("получена генерация руд от сервера");
    }

    public static void clear() {
        synced = null;
    }

    /** Вызывается из потока индексации. */
    public static synchronized Result current() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            // реестры генерации загружаются при запуске мира и после /reload не меняются
            Object k = server.registryAccess();
            if (k != liveFor.get()) {
                live = OreData.extract(server);
                liveFor = new java.lang.ref.WeakReference<>(k);
                if (!live.isEmpty()) save(live);
            }
            return new Result(live, Origin.LIVE);
        }
        Map<String, List<OreData.Range>> fromServer = synced;
        if (fromServer != null) return new Result(fromServer, Origin.SERVER);
        Map<String, List<OreData.Range>> cached = load();
        if (cached != null && !cached.isEmpty()) return new Result(cached, Origin.CACHE);
        return new Result(Map.of(), Origin.NONE);
    }

    /** Ключ набора модов: хеш отсортированного списка «modId:версия». Другой набор модов даёт другие руды. */
    private static String key() {
        if (key == null) {
            List<String> mods = new ArrayList<>(Platform.get().modVersions());
            mods.sort(null);
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(String.join("\n", mods).getBytes(StandardCharsets.UTF_8));
                key = HexFormat.of().formatHex(hash, 0, 16);
            } catch (Exception e) {
                key = Integer.toHexString(mods.hashCode());
            }
        }
        return key;
    }

    private static Path path() {
        return Config.dir().resolve(FILE);
    }

    @Nullable
    private static Map<String, List<OreData.Range>> load() {
        if (fileRead) return file;
        fileRead = true;
        Path p = path();
        if (!Files.exists(p)) return null;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            boolean fits = root.has("format") && root.get("format").getAsInt() == OreData.FORMAT
                && root.has("key") && key().equals(root.get("key").getAsString()) && root.has("ores");
            if (!fits) {
                RYScope.LOG.info("Кеш генерации руд от другого набора модов, не используется");
                return null;
            }
            file = OreData.fromJson(root.getAsJsonObject("ores"));
            RYScope.LOG.info("Генерация руд взята из кеша одиночной игры: {} блоков", file.size());
        } catch (Exception e) {
            RYScope.LOG.warn("Кеш генерации руд не прочитан: {}", e.toString());
        }
        return file;
    }

    private static void save(Map<String, List<OreData.Range>> ores) {
        JsonObject root = new JsonObject();
        root.addProperty("format", OreData.FORMAT);
        root.addProperty("key", key());
        root.add("ores", OreData.toJson(ores));
        String text = GSON.toJson(root);
        Path p = path();
        try {
            if (Files.exists(p) && text.equals(Files.readString(p, StandardCharsets.UTF_8))) {
                remember(ores);
                return;
            }
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(text);
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            remember(ores);
            RYScope.LOG.info("Кеш генерации руд записан: {} блоков", ores.size());
        } catch (Exception e) {
            RYScope.LOG.warn("Кеш генерации руд не записан: {}", e.toString());
        }
    }

    private static void remember(Map<String, List<OreData.Range>> ores) {
        file = ores;
        fileRead = true;
    }
}
