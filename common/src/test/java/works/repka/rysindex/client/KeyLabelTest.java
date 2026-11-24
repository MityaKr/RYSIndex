package works.repka.rysindex.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Подписи клавиш без раскладки: при русской раскладке R не превращается в «К», Ctrl+F в «Ctrl + А». */
class KeyLabelTest {
    private static final Map<String, String> LANG = Map.of("key.keyboard.semicolon", ";", "key.keyboard.tab", "Tab");

    @Test
    void letterKeysUseLatinNames() {
        assertEquals("R", KeyNames.stableName("key.keyboard.r", LANG::get));
        assertEquals("7", KeyNames.stableName("key.keyboard.7", LANG::get));
    }

    @Test
    void signsAndNamedKeysComeFromTheGameTranslation() {
        assertEquals(";", KeyNames.stableName("key.keyboard.semicolon", LANG::get));
        assertEquals("Tab", KeyNames.stableName("key.keyboard.tab", LANG::get));
        assertNull(KeyNames.stableName("key.keyboard.world.1", LANG::get));
        assertNull(KeyNames.stableName("key.keyboard.unknown", LANG::get));
        assertNull(KeyNames.stableName("key.mouse.left", LANG::get));
    }

    @Test
    void onlyTheKeyPartIsReplacedModifiersStay() {
        assertEquals("R", KeyNames.replaceKeyPart("К", "К", "R"));
        assertEquals("Ctrl + F", KeyNames.replaceKeyPart("Ctrl + А", "А", "F"));
        assertEquals("Ж", KeyNames.replaceKeyPart("Ж", "Ж", null));
        assertEquals("R", KeyNames.replaceKeyPart("R", "R", "R"));
    }
}
