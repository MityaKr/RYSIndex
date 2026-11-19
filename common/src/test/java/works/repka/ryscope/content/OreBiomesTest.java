package works.repka.ryscope.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** «Где искать» руду на карте: биомы, в которых стоят её жилы, только если не везде. */
class OreBiomesTest {
    private static final Map<String, Set<String>> DIMS = new LinkedHashMap<>();

    static {
        DIMS.put("minecraft:overworld", Set.of("minecraft:plains", "minecraft:windswept_hills", "minecraft:meadow", "minecraft:badlands",
            "minecraft:wooded_badlands", "minecraft:forest"));
        DIMS.put("minecraft:the_nether", Set.of("minecraft:nether_wastes", "minecraft:basalt_deltas", "minecraft:crimson_forest"));
    }

    @Test
    void restrictedOreGetsItsBiomes() {
        // изумруд: только горы и луга
        OreBiomes.Where emerald = OreBiomes.restrict(List.of("minecraft:windswept_hills", "minecraft:meadow"), DIMS);
        assertEquals(new OreBiomes.Where("minecraft:overworld", List.of("minecraft:meadow", "minecraft:windswept_hills")), emerald);
        // доп. золото: пустоши
        assertEquals(List.of("minecraft:badlands", "minecraft:wooded_badlands"),
            OreBiomes.restrict(Set.of("minecraft:wooded_badlands", "minecraft:badlands"), DIMS).biomes());
    }

    @Test
    void everywhereIsNotHighlighted() {
        // железо во всех биомах Верхнего мира: подсветка всей карты ничего не говорит
        assertNull(OreBiomes.restrict(DIMS.get("minecraft:overworld"), DIMS));
        // незерит во всех биомах Нижнего мира, хотя биомы Верхнего тоже есть в списке жил
        List<String> nether = new ArrayList<>(DIMS.get("minecraft:the_nether"));
        assertNull(OreBiomes.restrict(nether, DIMS));
        // ни одного биома
        assertNull(OreBiomes.restrict(List.of(), DIMS));
        assertNull(OreBiomes.restrict(List.of("othermod:unknown"), DIMS));
    }

    @Test
    void dimensionWithMostBiomesWins() {
        // кварц Нижнего мира в двух биомах, а один чужой биом Верхнего мира не перетягивает
        OreBiomes.Where w = OreBiomes.restrict(List.of("minecraft:nether_wastes", "minecraft:crimson_forest", "minecraft:plains"), DIMS);
        assertEquals("minecraft:the_nether", w.dim());
        assertEquals(List.of("minecraft:crimson_forest", "minecraft:nether_wastes"), w.biomes());
    }

    @Test
    void tooManyBiomesIsNotHighlighted() {
        Map<String, Set<String>> big = new LinkedHashMap<>();
        Set<String> all = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) all.add("mod:b" + i);
        big.put("minecraft:overworld", all);
        List<String> some = new ArrayList<>();
        for (int i = 0; i <= OreBiomes.MAX_BIOMES; i++) some.add("mod:b" + i);
        assertNull(OreBiomes.restrict(some, big));
        assertEquals(OreBiomes.MAX_BIOMES, OreBiomes.restrict(some.subList(0, OreBiomes.MAX_BIOMES), big).biomes().size());
    }
}
