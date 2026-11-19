package works.repka.rysindex.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Источники добычи одним сжатым блоком с хешем: туда и обратно без потерь, хеш стабилен, мусор не читается. */
class LootWireTest {
    private static List<LootData.Table> sample(int count) {
        List<LootData.Table> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new LootData.Table("minecraft:blocks/ore_" + i, List.of(
                new LootData.Drop("minecraft:raw_iron", false, 1, 3, 1f, LootData.NOTE_FORTUNE),
                new LootData.Drop("c:gems", true, 0, 1, 0.05f, null),
                new LootData.Drop("minecraft:iron_ore", false, 1, 1, 1f, LootData.NOTE_SILK))));
        }
        return out;
    }

    @Test
    void roundTrip() throws IOException {
        List<LootData.Table> tables = sample(50);
        LootWire.Packed packed = LootWire.pack(tables);
        assertEquals(tables, LootWire.unpack(packed.data()));
        assertEquals(32, packed.hash().length());
    }

    @Test
    void hashFollowsContent() {
        assertEquals(LootWire.pack(sample(10)).hash(), LootWire.pack(sample(10)).hash());
        assertNotEquals(LootWire.pack(sample(10)).hash(), LootWire.pack(sample(11)).hash());
    }

    @Test
    void compressesRepeatedNames() {
        // 15 тысяч таблиц, как в RepYS: словарь и сжатие держат блок маленьким
        LootWire.Packed packed = LootWire.pack(sample(15_000));
        assertTrue(packed.data().length < 400_000, "сжато " + packed.data().length);
    }

    @Test
    void unknownNoteBecomesPlain() throws IOException {
        List<LootData.Table> in = List.of(new LootData.Table("x:y", List.of(new LootData.Drop("x:z", false, 1, 1, 1f, "чужая"))));
        assertEquals(null, LootWire.unpack(LootWire.pack(in).data()).get(0).drops().get(0).note());
    }

    @Test
    void garbageIsRejected() {
        assertThrows(IOException.class, () -> LootWire.unpack(new byte[] {1, 2, 3, 4, 5}));
        byte[] data = LootWire.pack(sample(3)).data();
        byte[] cut = java.util.Arrays.copyOf(data, data.length / 2);
        assertThrows(IOException.class, () -> LootWire.unpack(cut));
    }
}
