package works.repka.rysindex.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Цели страницы справки: массив раскрывается по одной цели, объект с компонентами остаётся целым. */
class HelpTargetsTest {
    @Test
    void arrayIsSplitAndComponentObjectKept() {
        JsonElement e = JsonParser.parseString(
            "[\"kubejs:gate_fragment\", \"#c:ingots\", {\"id\": \"modular_machinery_reborn:controller\","
                + " \"components\": {\"modular_machinery_reborn:machine\": \"repys:creation_engine\"}}]");
        List<JsonElement> parts = HelpTargets.parts(e);
        assertEquals(3, parts.size());
        assertEquals("kubejs:gate_fragment", parts.get(0).getAsString());
        assertEquals("#c:ingots", parts.get(1).getAsString());
        assertTrue(HelpTargets.hasComponents(parts.get(2)), "объект с компонентами не разбирается на части");
        assertTrue(!HelpTargets.hasComponents(parts.get(0)));
    }

    @Test
    void singleAndMissingTargets() {
        assertEquals(1, HelpTargets.parts(JsonParser.parseString("\"minecraft:piston\"")).size());
        assertEquals(0, HelpTargets.parts(null).size());
        assertEquals(0, HelpTargets.parts(JsonParser.parseString("[]")).size());
    }
}
