package works.repka.rysindex.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.platform.Platform;

/** Настройки клиента: {@code config/rysindex/config.json}. */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    /** compact (16, без плашек), comfortable (23, как в макете), large (28). */
    public String density = "comfortable";
    public boolean overlayEnabled = true;
    public boolean bookmarksPanel = true;
    /** Закладки откреплены от панели справа и показываются отдельной колонкой слева, как в JEI. */
    public boolean bookmarksDetached = false;
    /** В творческом режиме клик по предмету в списке выдаёт его. */
    public boolean cheatMode = false;
    /** Дополнительные языки для поиска помимо текущего. "*" = все загруженные. */
    public List<String> searchLanguages = new ArrayList<>(List.of("en_us"));
    public boolean fuzzySearch = true;
    /**
     * Линза по Alt в мире (блок или существо под прицелом). С 0.2.16 выключена по умолчанию: Alt в мире занят у Create
     * (меню инструментов, пояс), SFM и других. В окнах линза работает всегда.
     */
    public boolean lensInWorld = false;
    public boolean animations = true;
    /** Максимальная ширина панели списка в колонках; 0 = сколько помещается. */
    public int maxColumns = 0;
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
    /** Неудачных фоновых загрузок JEI подряд ({@link AsyncPolicy}): фон отключается на второй. */
    public int asyncJeiStrikes = 0;
    /** Показывать кнопки FTB нашей панелью вместо их собственной. */
    public boolean replaceFtbSidebar = true;
    /** Учитывать в дереве крафта и на доске то, что игрок видел в хранилищах рядом. */
    public boolean countStorages = true;
    /** Дерево крафта не раскрывает то, что умеет делать сеть AE2, а пишет это в «Заказать в сети». */
    public boolean treeAutocraft = true;
    /** Радиус в блоках, в котором запомненные хранилища считаются доступными. */
    public int storageRadius = 32;
    /** Сохранённые поисковые запросы. */
    public java.util.List<String> savedSearches = new java.util.ArrayList<>();
    /** Показывать закреплённые цели поверх мира. */
    public boolean boardHud = true;
    /** Стикеры целей: "goals" (по стикеру на цель) или "sum" (один стикер с общей суммой по всем целям). */
    public String stickerMode = "goals";
    /** В каких единицах показывать энергию: FE, RF, J, EU. */
    public String energyUnit = "FE";
    /** Где хранить закладки и цели: "global" (общие) или "world" (свои для каждого мира и сервера). */
    public String profileScope = "global";
    /** Поле поиска внизу списка, над строкой счётчика. */
    public boolean searchBottom = false;
    /** Список предметов страницами вместо бесконечной прокрутки. */
    public boolean listPaged = true;
    /** Версия настроек вида: при первом запуске новой версии значения приводятся к макету. */
    public int designVersion = 0;
    /** Перекрашивать серые окна рецептов модов под тему. */
    public boolean restyleRecipes = true;
    /**
     * Увеличивать рецепты JEI примерно в 1,35 раза (0.2.10). По умолчанию выключено: автор оставил родной размер,
     * предметы 16 (18.11).
     */
    public boolean enlargeJeiRecipes = false;
    /** Сворачивать однотипные предметы (шерсть, зелья, яйца призыва) в одну ячейку. */
    public boolean groupItems = true;
    public List<String> expandedGroups = new ArrayList<>();
    /** Поисковые запросы, результаты которых скрыты из списка (Ctrl+H по результатам поиска). */
    public List<String> hiddenQueries = new ArrayList<>();
    /** Предметы, скрытые по одному, если JEI нет (с JEI они пишутся в его blacklist.json): id или id#компоненты. */
    public List<String> hiddenItems = new ArrayList<>();
    public boolean showHidden = false;
    /** Порядок списка: "mod" (как JEI по умолчанию), "name" или "source" (как отдаёт источник). */
    public String listSort = "mod";
    /** Не показывать в списке предметы закрытых стадий (иначе они видны с замком). */
    public boolean hideLocked = false;
    /**
     * Служебные категории рецептов, которые уходят в конец вкладок (рядом со «Сведениями»): «пространство имён» или
     * «пространство:путь». По умолчанию распаковка и содержимое пакетов PackagedAuto и его аддонов.
     */
    public List<String> serviceCategories = new ArrayList<>(CategoryOrder.DEFAULT_SERVICE);

    public int slotSize() {
        return switch (density) {
            case "compact" -> 16;
            case "large" -> 28;
            default -> 23;
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
        return Platform.get().configDir().resolve(RYSIndex.MOD_ID);
    }

    public static synchronized Config get() {
        if (instance == null) {
            Path file = dir().resolve("config.json");
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    instance = GSON.fromJson(r, Config.class);
                } catch (Exception e) {
                    RYSIndex.LOG.warn("Не удалось прочитать настройки, используются значения по умолчанию: {}", e.toString());
                }
            }
            if (instance == null) instance = new Config();
            if (instance.designVersion < 1) {
                instance.designVersion = 1;
                instance.maxColumns = 8;
                instance.listPaged = true;
            }
            if (instance.designVersion < 2) {
                // столбцы подбираются под ширину экрана
                instance.designVersion = 2;
                if (instance.maxColumns == 8) instance.maxColumns = 0;
            }
            if (instance.designVersion < 3) {
                // 0.2.16: фон отключался после одного сбоя (у автора разовая гонка 18.11); даём ему ещё попытку по новому правилу
                instance.designVersion = 3;
                instance.asyncJeiUnsafe = false;
                instance.asyncJeiStrikes = 0;
            }
            if (instance.designVersion < 4) {
                // 0.2.16: линза в мире выключена по умолчанию; прежнее «включено» было значением по умолчанию, а не выбором
                instance.designVersion = 4;
                instance.lensInWorld = false;
            }
            if (instance.searchLanguages == null) instance.searchLanguages = new ArrayList<>(List.of("en_us"));
            if (instance.expandedGroups == null) instance.expandedGroups = new ArrayList<>();
            if (instance.hiddenQueries == null) instance.hiddenQueries = new ArrayList<>();
            // повторы одного запроса: убранный крестиком или Ctrl+Z запрос иначе продолжает скрывать
            instance.hiddenQueries = new ArrayList<>(new java.util.LinkedHashSet<>(instance.hiddenQueries));
            if (instance.hiddenItems == null) instance.hiddenItems = new ArrayList<>();
            if (instance.serviceCategories == null) instance.serviceCategories = new ArrayList<>(CategoryOrder.DEFAULT_SERVICE);
            if (!"sum".equals(instance.stickerMode)) instance.stickerMode = "goals";
            // записи старого формата ни с чем не совпадают и копятся: компоненты в них строкой с хешами объектов
            if (instance.hiddenItems.removeIf(HiddenItems::legacy)) RYSIndex.LOG.info("Убраны скрытые предметы в старом формате");
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
            RYSIndex.LOG.warn("Не удалось сохранить настройки: {}", e.toString());
        }
    }
}
