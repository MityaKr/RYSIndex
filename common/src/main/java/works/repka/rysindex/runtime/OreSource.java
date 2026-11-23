package works.repka.rysindex.runtime;

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
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.content.OreData;
import works.repka.rysindex.platform.Platform;

/**
 * Откуда клиент берёт генерацию руд. Данные генерации клиенту не синхронизируются, поэтому по порядку:
 * встроенный сервер одиночной игры; данные от сервера, если RYSIndex стоит и там; файл
 * {@code config/rysindex/ores-cache.json}, записанный в одиночной игре с тем же набором модов.
 */
public final class OreSource {
    public enum Origin { LIVE, SERVER, CACHE, NONE }

    /** {@code biomes}: где искать руду на карте ({@link works.repka.rysindex.content.OreBiomes}), может быть пусто. */
    public record Result(Map<String, List<OreData.Range>> ores, Origin origin, Map<String, works.repka.rysindex.content.OreBiomes.Where> biomes) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE = "ores-cache.json";

    @Nullable
    private static volatile Map<String, List<OreData.Range>> synced;
    /** Реестры мира, по которым прочитано live: слабая ссылка, чтобы не держать закрытый мир в памяти. */
    private static java.lang.ref.WeakReference<Object> liveFor = new java.lang.ref.WeakReference<>(null);
    private static Map<String, List<OreData.Range>> live = Map.of();
    private static Map<String, works.repka.rysindex.content.OreBiomes.Where> liveBiomes = Map.of();
    @Nullable
    private static volatile Map<String, works.repka.rysindex.content.OreBiomes.Where> syncedBiomes;
    private static Map<String, works.repka.rysindex.content.OreBiomes.Where> fileBiomes = Map.of();
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
        Indexer.request("ore generation received from the server");
    }

    /** Биомы руд от сервера (пакет приходит отдельно от жил: старый клиент его не знает). */
    public static void setSyncedBiomes(Map<String, works.repka.rysindex.content.OreBiomes.Where> biomes) {
        syncedBiomes = biomes;
        Indexer.request("ore biomes received from the server");
    }

    public static void clear() {
        synced = null;
        syncedBiomes = null;
    }

    /** Вызывается из потока индексации. */
    public static synchronized Result current() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            // реестры генерации загружаются при запуске мира и после /reload не меняются
            Object k = server.registryAccess();
            if (k != liveFor.get()) {
                Map<String, List<net.minecraft.world.level.levelgen.placement.PlacedFeature>> features = new java.util.HashMap<>();
                live = OreData.extract(server, features);
                liveBiomes = works.repka.rysindex.content.OreBiomeData.extract(server, features);
                liveFor = new java.lang.ref.WeakReference<>(k);
                if (!live.isEmpty()) save(live, liveBiomes);
            }
            return new Result(live, Origin.LIVE, liveBiomes);
        }
        Map<String, List<OreData.Range>> fromServer = synced;
        if (fromServer != null) {
            Map<String, works.repka.rysindex.content.OreBiomes.Where> b = syncedBiomes;
            return new Result(fromServer, Origin.SERVER, b != null ? b : Map.of());
        }
        Map<String, List<OreData.Range>> cached = load();
        if (cached != null && !cached.isEmpty()) return new Result(cached, Origin.CACHE, fileBiomes);
        return new Result(Map.of(), Origin.NONE, Map.of());
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
                RYSIndex.LOG.info("The ore generation cache is from another mod set, not used");
                return null;
            }
            file = OreData.fromJson(root.getAsJsonObject("ores"));
            // биомов нет в кеше до 0.2.15: тогда без подсветки на карте
            fileBiomes = root.has("biomes") && root.get("biomes").isJsonObject()
                ? works.repka.rysindex.content.OreBiomeData.fromJson(root.getAsJsonObject("biomes")) : Map.of();
            RYSIndex.LOG.info("Ore generation taken from the singleplayer cache: {} blocks", file.size());
        } catch (Exception e) {
            RYSIndex.LOG.warn("Ore generation cache could not be read: {}", e.toString());
        }
        return file;
    }

    private static void save(Map<String, List<OreData.Range>> ores, Map<String, works.repka.rysindex.content.OreBiomes.Where> biomes) {
        JsonObject root = new JsonObject();
        root.addProperty("format", OreData.FORMAT);
        root.addProperty("key", key());
        root.add("ores", OreData.toJson(ores));
        root.add("biomes", works.repka.rysindex.content.OreBiomeData.toJson(biomes));
        String text = GSON.toJson(root);
        Path p = path();
        try {
            if (Files.exists(p) && text.equals(Files.readString(p, StandardCharsets.UTF_8))) {
                remember(ores, biomes);
                return;
            }
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(text);
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            remember(ores, biomes);
            RYSIndex.LOG.info("Ore generation cache written: {} blocks", ores.size());
        } catch (Exception e) {
            RYSIndex.LOG.warn("Ore generation cache could not be written: {}", e.toString());
        }
    }

    private static void remember(Map<String, List<OreData.Range>> ores, Map<String, works.repka.rysindex.content.OreBiomes.Where> biomes) {
        file = ores;
        fileBiomes = biomes;
        fileRead = true;
    }
}
