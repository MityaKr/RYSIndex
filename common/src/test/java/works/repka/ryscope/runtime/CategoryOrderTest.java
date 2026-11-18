package works.repka.ryscope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Порядок вкладок категорий: служебные (содержимое пакетов PackagedAuto) после настоящих машин. */
class CategoryOrderTest {
    private static int rank(String id, List<String> service) {
        String[] p = id.split(":", 2);
        return CategoryOrder.rank(p[0], p[1], false, false, "ryscope", service);
    }

    /** Сортировка устойчивая, как в Lookup.find: внутри одного места порядок источника. */
    private static List<String> sorted(List<String> ids, List<String> service) {
        List<String> out = new ArrayList<>(ids);
        out.sort(Comparator.comparingInt(id -> rank(id, service)));
        return out;
    }

    @Test
    void packagedAutoGoesAfterMachines() {
        // снимок автора: «Плазма сырой звёздной материи», «Рецепты 6», первой стояла распаковка жидкостного пакета
        List<String> ids = List.of("packagedauto:fluid_package_contents", "ufo:star_matter_condenser", "mekanism:chemical_infusing",
            "packagedmekemicals:chemical_package_contents", "minecraft:crafting", "jei:information", "packagedauto:package_contents");
        List<String> order = sorted(ids, CategoryOrder.DEFAULT_SERVICE);
        assertEquals(List.of("minecraft:crafting", "ufo:star_matter_condenser", "mekanism:chemical_infusing",
            "packagedauto:fluid_package_contents", "packagedmekemicals:chemical_package_contents", "jei:information",
            "packagedauto:package_contents"), order);
        // первая вкладка, которая откроется, настоящая
        assertFalse(order.get(0).startsWith("packaged"));
    }

    @Test
    void serviceStaysBeforeLoot() {
        assertTrue(rank("packagedauto:packaging", CategoryOrder.DEFAULT_SERVICE) < rank("jeresources:mob", CategoryOrder.DEFAULT_SERVICE));
        assertTrue(rank("packagedauto:packaging", CategoryOrder.DEFAULT_SERVICE) > rank("minecraft:trades", CategoryOrder.DEFAULT_SERVICE));
    }

    @Test
    void listIsConfigurable() {
        // пустой список: PackagedAuto снова среди машин
        assertEquals(CategoryOrder.MACHINE, rank("packagedauto:package_contents", List.of()));
        // запись «пространство:путь» уводит только эту категорию, без учёта регистра и пробелов
        List<String> one = List.of(" PackagedAuto:Package_Contents ");
        assertEquals(CategoryOrder.SERVICE, rank("packagedauto:package_contents", one));
        assertEquals(CategoryOrder.MACHINE, rank("packagedauto:packaging", one));
        // мусор в настройках не мешает
        List<String> junk = new ArrayList<>();
        junk.add(null);
        junk.add("");
        assertEquals(CategoryOrder.MACHINE, rank("mekanism:crushing", junk));
        assertFalse(CategoryOrder.isService("packagedauto", "x", null));
    }

    @Test
    void stationAndCraftingStayFirst() {
        // у распаковщика в «Использованиях» своя категория всё равно первая
        assertEquals(CategoryOrder.STATION, CategoryOrder.rank("packagedauto", "package_processing", true, false, "ryscope", CategoryOrder.DEFAULT_SERVICE));
        assertEquals(CategoryOrder.CRAFTING, rank("ryscope:crafting", CategoryOrder.DEFAULT_SERVICE));
    }
}
