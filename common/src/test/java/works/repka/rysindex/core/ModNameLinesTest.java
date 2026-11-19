package works.repka.rysindex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModNameLinesTest {
    private static final String MOD = "Productive Metalworks";

    @Test
    void appendsWhenMissing() {
        var plan = ModNameLines.plan(List.of("Жёлтый бак литейной", "Удерживайте [W] для размышления"), MOD);
        assertTrue(plan.append());
        assertTrue(plan.remove().isEmpty());
    }

    @Test
    void keepsSingleExistingLine() {
        // Jade уже дописал имя: второй раз не пишем
        var plan = ModNameLines.plan(List.of("Жёлтый бак литейной", MOD), MOD);
        assertTrue(plan.isEmpty());
    }

    @Test
    void existingLineNotLastStillCounts() {
        // кто-то дописал строку после Jade: имя мода уже есть, второе не нужно
        var plan = ModNameLines.plan(List.of("Бак", MOD, "Shift: подробнее"), MOD);
        assertTrue(plan.isEmpty());
    }

    @Test
    void duplicateKeepsLast() {
        var plan = ModNameLines.plan(List.of("Бак", "Удерживайте [W]", MOD, MOD), MOD);
        assertFalse(plan.append());
        assertEquals(List.of(2), plan.remove());
    }

    @Test
    void formattingCodesAndCaseIgnored() {
        var plan = ModNameLines.plan(List.of("Бак", "§9§oProductive Metalworks ", MOD.toUpperCase()), MOD);
        assertEquals(List.of(1), plan.remove());
        assertFalse(plan.append());
    }

    @Test
    void textMentioningModIsKept() {
        // мод пишет о себе в описании: это не подпись, её не трогаем, а подпись дописываем
        var plan = ModNameLines.plan(List.of("Бак", "Часть литейной Productive Metalworks"), MOD);
        assertTrue(plan.append());
        assertTrue(plan.remove().isEmpty());
    }

    @Test
    void emptyModNameDoesNothing() {
        assertTrue(ModNameLines.plan(List.of("Бак"), " ").isEmpty());
    }
}
