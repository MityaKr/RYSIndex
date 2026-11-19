package works.repka.rysindex.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Группы горшков Botany Pots и Botany Pots Tiers: каждый горшок ровно в одной группе своего уровня и типа. */
class BotanyPotGroupsTest {
    private static final String[] TIERS = {"", "elite", "ultra", "mega"};
    private static final String[] TYPES = {"", "hopper", "waxed"};
    private static final Pattern MATCH = Pattern.compile("\"match\"\\s*:\\s*\"([^\"]+)\"");

    private static Map<String, Pattern> groups() throws IOException {
        Map<String, Pattern> out = new LinkedHashMap<>();
        for (String tier : TIERS) {
            for (String type : TYPES) {
                String name = "botany_pots" + (tier.isEmpty() ? "" : "_" + tier) + (type.isEmpty() ? "" : "_" + type);
                try (InputStream in = BotanyPotGroupsTest.class.getResourceAsStream("/assets/rysindex/rysindex_groups/" + name + ".json")) {
                    assertNotNull(in, "нет файла группы " + name);
                    Matcher m = MATCH.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    assertEquals(true, m.find(), "в группе " + name + " нет match");
                    out.put(name, Pattern.compile(m.group(1)));
                }
            }
        }
        return out;
    }

    private static List<String> matching(Map<String, Pattern> groups, String id) {
        List<String> out = new ArrayList<>();
        groups.forEach((name, p) -> {
            if (p.matcher(id).matches()) out.add(name);
        });
        return out;
    }

    @Test
    void everyPotLandsInItsTierAndType() throws IOException {
        Map<String, Pattern> groups = groups();
        String[] materials = {"terracotta", "black_concrete", "light_blue_glazed_terracotta", "red_terracotta", "mossy_stone_bricks",
            "red_nether_bricks", "nether_bricks", "polished_blackstone_bricks"};
        for (String tier : TIERS) {
            for (String type : TYPES) {
                String expected = "botany_pots" + (tier.isEmpty() ? "" : "_" + tier) + (type.isEmpty() ? "" : "_" + type);
                for (String material : materials) {
                    String id = (tier.isEmpty() ? "botanypots:" : "botanypotstiers:" + tier + "_") + material
                        + (type.isEmpty() ? "" : "_" + type) + "_botany_pot";
                    assertEquals(List.of(expected), matching(groups, id), id);
                }
            }
        }
    }

    @Test
    void otherItemsStayOut() throws IOException {
        Map<String, Pattern> groups = groups();
        for (String id : new String[] {"botanypotstiers:elite_upgrade", "botanypots:soil_tester", "minecraft:flower_pot",
            "botanypotstiers:ultra_upgrade", "mechanical_botany:mechanical_insolator", "minecraft:terracotta"}) {
            assertEquals(List.of(), matching(groups, id), id);
        }
    }
}
