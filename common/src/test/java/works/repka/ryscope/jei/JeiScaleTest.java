package works.repka.ryscope.jei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Подбор масштаба рецептов JEI: целое число пикселей на пиксель JEI и пределы карточки. */
class JeiScaleTest {
    private static final double EPS = 1e-9;
    private static final int AREA = JeiScale.maxWidth(), HARD = JeiScale.hardWidth();

    /** На пиксель JEI целое число пикселей экрана. */
    private static void assertWholePixels(double px, double scale) {
        double p = px * scale;
        assertEquals(Math.round(p), p, 1e-6, "пикселей на пиксель JEI: " + p);
    }

    @Test
    void craftingGrowsToWholePixels() {
        // 125 %: 2,5 пикселя в единице; верстак JEI 116×54 с рамкой 4
        int area = JeiScale.maxWidth();
        double s = JeiScale.pick(2.5, 124, 62, area, HARD, 220);
        assertEquals(1.2, s, EPS);
        assertWholePixels(2.5, s);
        // 100 %: 2 пикселя, растёт в полтора раза и всё ещё влезает в карточку
        assertEquals(1.5, JeiScale.pick(2, 124, 62, area, HARD, 220), EPS);
        assertTrue(124 * 1.5 <= area);
        // 150 %: 3 пикселя, четыре на пиксель JEI
        assertEquals(4 / 3.0, JeiScale.pick(3, 124, 62, area, HARD, 220), EPS);
    }

    @Test
    void wideCalculatorStaysInCard() {
        // калькулятор JEI Mekanism Multiblocks 180×120 с рамкой
        // занимает всё место карточки стандартного окна: остаётся своего размера и окно не раздвигает
        assertEquals(1, JeiScale.pick(2.5, 188, 128, JeiScale.maxWidth(), HARD, 220), EPS);
        assertEquals(1, JeiScale.pick(2, 188, 128, JeiScale.maxWidth(), HARD, 220), EPS);
        // с местом пошире рос бы до целого числа пикселей
        double s = JeiScale.pick(2.5, 188, 128, 300, 300, 220);
        assertTrue(188 * s <= 300);
        assertWholePixels(2.5, s);
    }

    @Test
    void tooWideShrinksToWholePixels() {
        double s = JeiScale.pick(2.5, 360, 200, 300, 300, 220);
        assertTrue(360 * s <= 300 + EPS, "ширина " + 360 * s);
        assertWholePixels(2.5, s);
        // целое число пикселей вышло бы мельче 0.8 места: ровно по ширине
        assertEquals(0.75, JeiScale.pick(2.5, 400, 200, 300, 300, 220), EPS);
        // совсем широкий тоже влезает целиком, а не обрезается на половине
        assertEquals(0.3, JeiScale.pick(2.5, 1000, 200, 300, 300, 220), EPS);
    }

    @Test
    void slightlyWideStaysNative() {
        // 100 %: рецепт 190 раньше ужимался до 0.5 (95 единиц), теперь своего размера и чуть раздвигает окно
        for (double px : new double[] {2, 2.5, 3}) {
            for (int w = AREA + 1; w <= HARD; w++) {
                assertEquals(1, JeiScale.pick(px, w, 120, AREA, HARD, 220), EPS, "ширина " + w + " при " + px);
            }
        }
    }

    @Test
    void wideAlwaysFitsWindowAndStaysReadable() {
        // что бы ни было, рецепт не шире места окна наибольшей ширины: под «+» и строку наличия не заходит
        for (double px : new double[] {1, 2, 2.5, 3, 4}) {
            for (int w = HARD + 1; w <= 1200; w++) {
                double s = JeiScale.pick(px, w, 120, AREA, HARD, 220);
                assertTrue(Math.ceil(w * s - 1e-6) <= HARD, "ширина " + w * s + " из " + w + " при " + px);
                double fit = HARD / (double) w;
                // не мельче 0.8 того, что влезло бы ровно по ширине
                assertTrue(s >= fit * JeiScale.WHOLE_MIN - 1e-9, "масштаб " + s + " для " + w + " при " + px);
                // целое число пикселей, если оно не мельчит
                if (Math.floor(px * fit + 1e-6) / px >= fit * JeiScale.WHOLE_MIN) assertWholePixels(px, s);
            }
        }
        // 100 %: 400 влезает в 208 при 0.5, это целое число пикселей и не мельче 0.8
        assertEquals(0.5, JeiScale.pick(2, 400, 120, AREA, HARD, 220), EPS);
        // 100 %: 250 при целом 0.5 стал бы 125 из 208 места: ровно по ширине
        assertEquals(208 / 250.0, JeiScale.pick(2, 250, 120, AREA, HARD, 220), EPS);
        // 150 %: 250 при целых 2/3 даёт 167 из 208, это ещё не мельче 0.8: целое число пикселей
        assertEquals(2 / 3.0, JeiScale.pick(3, 250, 120, AREA, HARD, 220), EPS);
        // 150 %: 330 при целой 1/3 дал бы 110 из 208, это мельче 0.8: ровно по ширине
        assertEquals(208 / 330.0, JeiScale.pick(3, 330, 120, AREA, HARD, 220), EPS);
    }

    @Test
    void cornerSnapsToPixel() {
        // 125 %: единица 2,5 пикселя, нечётный угол попадал на полпикселя
        double c = JeiScale.snap(2.5, 101);
        assertEquals(Math.round(c * 2.5), c * 2.5, 1e-9);
        assertTrue(Math.abs(c - 101) <= 0.5 / 2.5 + 1e-9);
        // 150 %: и чётный, и нечётный угол на целом пикселе
        assertEquals(101, JeiScale.snap(3, 101), 1e-9);
        double h = JeiScale.snap(1.5, 7);
        assertEquals(Math.round(h * 1.5), h * 1.5, 1e-9);
        // целая единица не сдвигается
        assertEquals(57, JeiScale.snap(2, 57), 1e-9);
    }

    @Test
    void tallDoesNotGrowPastLimit() {
        double s = JeiScale.pick(2, 124, 200, 300, 300, 220);
        assertTrue(200 * s <= 220 || s == 1, "высота " + 200 * s);
    }

    @Test
    void limitIsStandardCardArea() {
        // карточка 260: рейка 28, поля 7 и 5 с двух сторон, кнопка «+» 14 и зазор 6
        assertEquals(188, JeiScale.maxWidth());
        // окно наибольшей ширины 280
        assertEquals(208, JeiScale.hardWidth());
    }

}
