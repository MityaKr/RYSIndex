package works.repka.rysindex.runtime;

import java.util.List;
import java.util.Set;

/**
 * Порядок вкладок категорий: у машины в «Использованиях» сначала то, что она делает; затем верстак;
 * дальше машины и процессы в своём порядке; сведения модов семьи (магазин книги); служебное (теги, квесты, сведения,
 * содержимое пакетов PackagedAuto) и лут (сундуки, зачарования, мобы, генерация мира) в самом конце.
 * Без классов игры: порядок проверяется юнит-тестом.
 */
public final class CategoryOrder {
    private CategoryOrder() {}

    /** Сделки и награды модов семьи: после машин, перед торговлей жителей и лутом. */
    public static final int STATION = 0, CRAFTING = 1, MACHINE = 5, LINK = 6, TRADES = 7, SERVICE = 8, LOOT = 9;

    /**
     * Служебные категории по умолчанию ({@code Config.serviceCategories}): «пространство имён» или «пространство:путь».
     * PackagedAuto (id сверены по jar 4.0.8.21): package_recipe, packaging, package_processing, package_contents,
     * fluid_package_filling, fluid_package_contents; PackagedMekemicals 2.0.0.7: chemical_package_contents и
     * chemical_package_filling. Аддоны packagedexcrafting, packageddraconic, packagedavaritia своих категорий JEI не
     * заводят (рецепты идут в package_recipe), но стоят в списке на случай, если заведут.
     */
    public static final List<String> DEFAULT_SERVICE = List.of(
        "packagedauto", "packagedmekemicals", "packagedexcrafting", "packageddraconic", "packagedavaritia");

    /** Лут и сведения о мире: всегда в конце. Машины вроде «loot_fabricator» сюда не попадают. */
    private static final Set<String> LOOT_NAMESPACES = Set.of("jeresources", "lootjs", "jei_biome", "jei_structures");
    private static final Set<String> LOOT_PATHS = Set.of("loot", "loot_crate", "chest_loot", "mob_drops", "dungeon",
        "enchantment", "villager", "worldgen", "plant", "mob");

    /**
     * Место категории {@code ns:path}: чем меньше, тем левее. {@code station} — категория станции, открытой в
     * «Использованиях»; {@code link} — сведения модов семьи; {@code service} — список служебных из настроек.
     */
    public static int rank(String ns, String path, boolean station, boolean link, String ownMod, List<String> service) {
        if (station) return STATION;
        if (link) return LINK;
        if (path.equals("crafting") && (ns.equals("minecraft") || ns.equals(ownMod))) return CRAFTING;
        if (LOOT_NAMESPACES.contains(ns) || LOOT_PATHS.contains(path) || path.endsWith("_loot") || path.endsWith("_drops")) return LOOT;
        if (path.startsWith("tag_recipes") || path.equals("information") || path.equals("info") || ns.equals("ftbquests") || ns.equals("rysquests")) return SERVICE;
        if (isService(ns, path, service)) return SERVICE;
        if (path.equals("trades")) return TRADES;
        return MACHINE;
    }

    /** Подходит ли категория под список: запись «пространство имён» или «пространство:путь», без учёта регистра. */
    public static boolean isService(String ns, String path, List<String> service) {
        if (service == null) return false;
        for (String rule : service) {
            if (rule == null) continue;
            String r = rule.trim().toLowerCase(java.util.Locale.ROOT);
            if (r.isEmpty()) continue;
            int colon = r.indexOf(':');
            if (colon < 0 ? r.equals(ns) : r.substring(0, colon).equals(ns) && r.substring(colon + 1).equals(path)) return true;
        }
        return false;
    }
}
