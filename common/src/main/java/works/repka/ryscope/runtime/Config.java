package works.repka.ryscope.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.platform.Platform;

/** Настройки клиента: {@code config/ryscope/config.json}. */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    public String theme = "ryscope:slate";
    /** compact (16), comfortable (18), large (24). */
    public String density = "comfortable";
    public boolean overlayEnabled = true;
    public boolean bookmarksPanel = true;
    /** В творческом режиме клик по предмету в списке выдаёт его. */
    public boolean cheatMode = false;
    /** Дополнительные языки для поиска помимо текущего. "*" = все загруженные. */
    public List<String> searchLanguages = new ArrayList<>(List.of("en_us"));
    public boolean fuzzySearch = true;
    public boolean lensInWorld = true;
    public boolean animations = true;
    /** Максимальная ширина панели списка в колонках; 0 = сколько помещается. */
    public int maxColumns = 9;
    public boolean showModName = true;
    public String lastSearch = "";
    public boolean keepSearch = true;
    /** Если установлен JEI: заменять его список и окно рецептов нашими. */
    public boolean replaceJeiInterface = true;
    /** Загружать плагины JEI в фоновом потоке, чтобы игра не замирала при входе в мир. */
    public boolean asyncJei = true;
    /** Ставится автоматически, если фоновая загрузка JEI дала больше ошибок, чем обычная: дальше JEI грузится обычным способом. */
    public boolean asyncJeiUnsafe = false;
    /** Сколько ошибок плагинов даёт и обычная загрузка JEI в этой сборке: столько в фоне считается нормой. */
    public int asyncJeiTolerated = 0;
    /** Показывать кнопки FTB нашей панелью вместо их собственной. */
    public boolean replaceFtbSidebar = true;
    /** Учитывать в дереве крафта и на доске то, что игрок видел в хранилищах рядом. */
    public boolean countStorages = true;
    /** Радиус в блоках, в котором запомненные хранилища считаются доступными. */
    public int storageRadius = 32;
    /** Сохранённые поисковые запросы. */
    public java.util.List<String> savedSearches = new java.util.ArrayList<>();
    /** Показывать закреплённые цели поверх мира. */
    public boolean boardHud = true;
    /** В каких единицах показывать энергию: FE, RF, J, EU. */
    public String energyUnit = "FE";
    /** Где хранить закладки и цели: "global" (общие) или "world" (свои для каждого мира и сервера). */
    public String profileScope = "global";
    /** Поле поиска внизу списка, над строкой счётчика. */
    public boolean searchBottom = false;
    /** Список предметов страницами вместо бесконечной прокрутки. */
    public boolean listPaged = false;
    /** Перекрашивать серые окна рецептов модов под тему. */
    public boolean restyleRecipes = true;
    /** Сворачивать однотипные предметы (шерсть, зелья, яйца призыва) в одну ячейку. */
    public boolean groupItems = true;
    public List<String> expandedGroups = new ArrayList<>();
    /** Поисковые запросы, результаты которых скрыты из списка (Ctrl+H по результатам поиска). */
    public List<String> hiddenQueries = new ArrayList<>();
    public boolean showHidden = false;
    /** Не показывать в списке предметы закрытых стадий (иначе они видны с замком). */
    public boolean hideLocked = false;

    public int slotSize() {
        return switch (density) {
            case "compact" -> 16;
            case "large" -> 24;
            default -> 18;
        };
    }

    public void cycleDensity() {
        density = switch (density) {
            case "compact" -> "comfortable";
            case "comfortable" -> "large";
            default -> "compact";
        };
    }

    public static Path dir() {
        return Platform.get().configDir().resolve(RYScope.MOD_ID);
    }

    public static synchronized Config get() {
        if (instance == null) {
            Path file = dir().resolve("config.json");
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    instance = GSON.fromJson(r, Config.class);
                } catch (Exception e) {
                    RYScope.LOG.warn("Не удалось прочитать настройки, используются значения по умолчанию: {}", e.toString());
                }
            }
            if (instance == null) instance = new Config();
            if (instance.searchLanguages == null) instance.searchLanguages = new ArrayList<>(List.of("en_us"));
            if (instance.expandedGroups == null) instance.expandedGroups = new ArrayList<>();
            if (instance.hiddenQueries == null) instance.hiddenQueries = new ArrayList<>();
        }
        return instance;
    }

    public static synchronized void save() {
        if (instance == null) return;
        try {
            Files.createDirectories(dir());
            try (Writer w = Files.newBufferedWriter(dir().resolve("config.json"), StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (Exception e) {
            RYScope.LOG.warn("Не удалось сохранить настройки: {}", e.toString());
        }
    }
}
