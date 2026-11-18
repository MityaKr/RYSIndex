package works.repka.ryscope.jei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Подбор масштаба рецептов JEI: целое число пикселей на пиксель JEI и пределы карточки. */
class JeiScaleTest {
    private static final double EPS = 1e-9;

    /** На пиксель JEI целое число пикселей экрана. */
    private static void assertWholePixels(double px, double scale) {
        double p = px * scale;
        assertEquals(Math.round(p), p, 1e-6, "пикселей на пиксель JEI: " + p);
    }

    @Test
    void craftingGrowsToWholePixels() {
        // 125 %: 2,5 пикселя в единице; верстак JEI 116×54 с рамкой 4
        int area = JeiScale.maxWidth();
        double s = JeiScale.pick(2.5, 124, 62, area, 220);
        assertEquals(1.2, s, EPS);
        assertWholePixels(2.5, s);
        // 100 %: 2 пикселя, растёт в полтора раза и всё ещё влезает в карточку
        assertEquals(1.5, JeiScale.pick(2, 124, 62, area, 220), EPS);
        assertTrue(124 * 1.5 <= area);
        // 150 %: 3 пикселя, четыре на пиксель JEI
        assertEquals(4 / 3.0, JeiScale.pick(3, 124, 62, area, 220), EPS);
    }

    @Test
    void wideCalculatorStaysInCard() {
        // калькулятор JEI Mekanism Multiblocks 180×120 с рамкой
        // занимает всё место карточки стандартного окна: остаётся своего размера и окно не раздвигает
        assertEquals(1, JeiScale.pick(2.5, 188, 128, JeiScale.maxWidth(), 220), EPS);
        assertEquals(1, JeiScale.pick(2, 188, 128, JeiScale.maxWidth(), 220), EPS);
        // с местом пошире рос бы до целого числа пикселей
        double s = JeiScale.pick(2.5, 188, 128, 300, 220);
        assertTrue(188 * s <= 300);
        assertWholePixels(2.5, s);
    }

    @Test
    void tooWideShrinksToWholePixels() {
        double s = JeiScale.pick(2.5, 360, 200, 300, 220);
        assertTrue(360 * s <= 300 + EPS, "ширина " + 360 * s);
        assertWholePixels(2.5, s);
        // совсем широкий: целого числа пикселей не мельче половины нет, ровно по ширине
        assertEquals(0.75, JeiScale.pick(2.5, 400, 200, 300, 220), EPS);
        assertEquals(0.5, JeiScale.pick(2.5, 1000, 200, 300, 220), EPS);
    }

    @Test
    void tallDoesNotGrowPastLimit() {
        double s = JeiScale.pick(2, 124, 200, 300, 220);
        assertTrue(200 * s <= 220 || s == 1, "высота " + 200 * s);
    }

    @Test
    void limitIsStandardCardArea() {
        // карточка 260: рейка 28, поля 7 и 5 с двух сторон, кнопка «+» 14 и зазор 6
        assertEquals(188, JeiScale.maxWidth());
    }
}
