package works.repka.ryscope.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Чистая логика сведений модов семьи: разряды, полоса, группы списка, обновление категорий поставщика, высоты. */
class LinkMathTest {
    @Test
    void thousandsSplitsBySpaces() {
        assertEquals("0", LinkMath.thousands(0));
        assertEquals("14", LinkMath.thousands(14));
        assertEquals("192", LinkMath.thousands(192));
        assertEquals("1 000", LinkMath.thousands(1000));
        assertEquals("1 984", LinkMath.thousands(1984));
        assertEquals("12 000", LinkMath.thousands(12000));
        assertEquals("1 234 567", LinkMath.thousands(1234567));
        assertEquals("-1 500", LinkMath.thousands(-1500));
    }

    @Test
    void fractionIsClamped() {
        assertEquals(0.5f, LinkMath.fraction(7, 14), 1e-6);
        assertEquals(1f, LinkMath.fraction(200, 192), 1e-6, "сверх нормы полоса полная");
        assertEquals(0f, LinkMath.fraction(-3, 14), 1e-6);
        assertEquals(0f, LinkMath.fraction(0, 0), 1e-6);
        assertEquals(1f, LinkMath.fraction(1, 0), 1e-6);
    }

    @Test
    void groupsKeepFirstAppearanceOrder() {
        Map<String, List<String>> g = LinkMath.byGroup(List.of("задача:a", "магазин:b", "задача:c"), s -> s.substring(0, s.indexOf(':')));
        assertEquals(List.of("задача", "магазин"), List.copyOf(g.keySet()));
        assertEquals(List.of("задача:a", "задача:c"), g.get("задача"));
    }

    private record G(String category, boolean link, int version) {}

    @Test
    void replaceTouchesOnlyProviderGroups() {
        List<G> current = List.of(new G("crafting", false, 0), new G("shop", true, 1), new G("loot", false, 0), new G("rewards", true, 1));
        // магазин обновился, награды исчезли, появилась новая категория поставщика
        List<G> fresh = List.of(new G("shop", true, 2), new G("tasks", true, 2));
        List<G> out = LinkMath.replace(current, fresh, G::category, G::link);
        assertEquals(List.of(new G("crafting", false, 0), new G("shop", true, 2), new G("loot", false, 0), new G("tasks", true, 2)), out);
        assertEquals(List.of(new G("crafting", false, 0)), LinkMath.replace(List.of(new G("crafting", false, 0), new G("shop", true, 1)), List.of(), G::category, G::link),
            "без поставщика его категории уходят");
    }

    @Test
    void stackedHeights() {
        assertEquals(0, LinkMath.stacked(new int[0], 5));
        assertEquals(22, LinkMath.stacked(new int[] {22}, 5));
        // макет 1: шапка, заголовок, три строки, монеты, замок, ссылка, кнопка
        assertEquals(22 + 6 + 18 * 3 + 8 + 16 + 8 + 15 + 5 * 8, LinkMath.stacked(new int[] {22, 6, 18, 18, 18, 8, 16, 8, 15}, 5));
    }

    @Test
    void windowLimitsVisibleList() {
        assertArrayEquals(new int[] {120, 0}, LinkMath.window(120, 200));
        assertArrayEquals(new int[] {200, 100}, LinkMath.window(300, 200));
        assertArrayEquals(new int[] {0, 50}, LinkMath.window(50, -5));
    }
}
