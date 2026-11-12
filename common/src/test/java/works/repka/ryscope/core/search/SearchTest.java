package works.repka.ryscope.core.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTest {
    private static SearchEntry e(int i, String name, String id, String mod, String... tags) {
        return new SearchEntry(i, name, new String[0], id, mod, mod, tags);
    }

    private static SearchIndex index() {
        List<SearchEntry> list = new ArrayList<>();
        list.add(e(0, "piston", "piston", "minecraft"));
        list.add(e(1, "sticky piston", "sticky_piston", "minecraft"));
        list.add(e(2, "oak planks", "oak_planks", "minecraft", "minecraft:planks"));
        list.add(e(3, "birch planks", "birch_planks", "minecraft", "minecraft:planks"));
        list.add(e(4, "mechanical press", "mechanical_press", "create"));
        list.add(new SearchEntry(5, "iron ingot", new String[] {"железный слиток"}, "iron_ingot", "minecraft", "minecraft", new String[] {"c:ingots"}));
        return new SearchIndex(list);
    }

    private static int[] search(String text) {
        return index().search(Query.parse(text), () -> false, null, true).indices;
    }

    @Test
    void substringAndRanking() {
        // «piston» в начале названия идёт раньше, чем в середине
        assertArrayEquals(new int[] {0, 1}, search("piston"));
    }

    @Test
    void wordsAreAnded() {
        assertArrayEquals(new int[] {2}, search("oak plank"));
    }

    @Test
    void orGroups() {
        assertArrayEquals(new int[] {0, 4}, search("@create | \"piston\" -sticky"));
    }

    @Test
    void modAndTagPrefixes() {
        assertArrayEquals(new int[] {4}, search("@create"));
        assertArrayEquals(new int[] {2, 3}, search("#planks"));
        assertArrayEquals(new int[] {3}, search("#planks -oak"));
    }

    @Test
    void regex() {
        assertArrayEquals(new int[] {2, 3}, search("/^(oak|birch) /"));
    }

    @Test
    void oneTypoIsForgiven() {
        assertArrayEquals(new int[] {0, 1}, search("pistn"));
        assertEquals(0, index().search(Query.parse("pistn"), () -> false, null, false).indices.length);
    }

    @Test
    void wrongKeyboardLayout() {
        // «железный» набрано в английской раскладке
        assertArrayEquals(new int[] {5}, search(";tktpysq"));
    }

    @Test
    void otherLanguageName() {
        assertArrayEquals(new int[] {5}, search("слиток"));
    }

    @Test
    void fuzzyMatcher() {
        assertTrue(Fuzzy.containsWithinOne("mechanical press", "mechanicl"));
        assertTrue(Fuzzy.containsWithinOne("mechanical press", "mexhanical"));
        assertTrue(Fuzzy.containsWithinOne("mechanical press", "mechaniical"));
        assertFalse(Fuzzy.containsWithinOne("mechanical press", "mxchxnical"));
    }

    /** Быстрый разобранный шаблон отвечает так же, как таблица правок, на случайных строках. */
    @Test
    void compiledFuzzyMatchesTable() {
        assertTrue(Fuzzy.compile("mechanicl").within("mechanical press"));
        assertFalse(Fuzzy.compile("mxchxnical").within("mechanical press"));
        assertTrue(Fuzzy.compile("жел").within("железо"));
        java.util.Random r = new java.util.Random(7);
        String alpha = "abcжз ё中";
        for (int it = 0; it < 200_000; it++) {
            StringBuilder t = new StringBuilder(), p = new StringBuilder();
            for (int i = r.nextInt(12); i > 0; i--) t.append(alpha.charAt(r.nextInt(alpha.length())));
            for (int i = 1 + r.nextInt(6); i > 0; i--) p.append(alpha.charAt(r.nextInt(alpha.length())));
            String text = t.toString(), pattern = p.toString();
            assertEquals(Fuzzy.containsWithinOne(text, pattern), Fuzzy.compile(pattern).within(text), text + " / " + pattern);
        }
    }

    @Test
    void operatorsAreParsed() {
        Query q = Query.parse("uses:piston craftable -almost");
        assertEquals(3, q.operators().size());
        assertEquals("uses", q.operators().get(0).operator);
        assertEquals("piston", q.operators().get(0).text);
        assertTrue(q.operators().get(2).negated);
    }

    @Test
    void cancellationReturnsNull() {
        assertEquals(null, index().search(Query.parse("piston"), () -> true, null, true));
    }
}
