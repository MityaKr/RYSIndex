package works.repka.ryscope.jei;

/**
 * Масштаб рецептов JEI в окне RYScope. Их ячейки и мелкий текст рассчитаны на единицы GUI игры и рядом со шрифтами и
 * плашками окна выглядели мелко. Рецепт увеличивается примерно в {@link #TARGET} раза так, чтобы на пиксель JEI
 * приходилось целое число пикселей экрана: тогда текст JEI не мылится. Широкие рецепты вписываются в карточку целиком.
 */
public final class JeiScale {
    /** Желаемый множитель; настоящий ближайший к нему с целым числом пикселей. */
    public static final double TARGET = 1.35;
    /** Выше этого рецепт не увеличивается (высокие листаются прокруткой и не уменьшаются). */
    public static final int MAX_H = 220;

    private JeiScale() {}

    /**
     * Место под рецепт в карточке стандартного окна. Рецепт до этой ширины увеличивается, а окно остаётся стандартным:
     * масштаб подстраивается под карточку, а не окно под рецепт.
     */
    public static int maxWidth() {
        return works.repka.ryscope.ui.RecipeScreen.RECIPE_AREA;
    }

    /** Место под рецепт в окне наибольшей ширины: рецепт до этой ширины остаётся своего размера и чуть раздвигает окно. */
    public static int hardWidth() {
        return works.repka.ryscope.ui.RecipeScreen.RECIPE_AREA_MAX;
    }

    /** Целое число пикселей на пиксель JEI допустимо, если рецепт выходит не мельче этой доли места. */
    static final double WHOLE_MIN = 0.8;

    /**
     * Множитель для рецепта {@code w}×{@code h} единиц окна при {@code px} пикселях экрана в единице.
     * <ul>
     * <li>Рецепт не шире {@code maxW}: наибольшее целое число пикселей на пиксель JEI, не больше {@code px·TARGET}
     * (с округлением), при котором рецепт не шире {@code maxW} и не выше {@code maxH}. Не меньше 1; если такого целого нет, 1.</li>
     * <li>Чуть шире, до {@code hardW}: своего размера, окно раздвигается (так было до 0.2.10, рецепт не мельчает вдвое).</li>
     * <li>Шире {@code hardW}: уменьшается до целого числа пикселей, если так выходит не мельче {@link #WHOLE_MIN} места,
     * иначе ровно по ширине. Всегда влезает, под кнопку «+» и строку наличия не заходит.</li>
     * </ul>
     */
    public static double pick(double px, int w, int h, int maxW, int hardW, int maxH) {
        if (px <= 0 || w <= 0 || h <= 0) return 1;
        hardW = Math.max(hardW, maxW);
        if (w > hardW) {
            double fit = (double) hardW / w;
            double s = Math.floor(px * fit + 1e-6) / px;
            return s >= fit * WHOLE_MIN - 1e-9 ? s : fit;
        }
        if (w > maxW) return 1;
        double fitW = (double) maxW / w;
        double most = Math.min(fitW, Math.max(1, (double) maxH / h));
        for (long p = Math.round(px * TARGET); p / px >= 1 - 1e-6; p--) {
            double s = p / px;
            if (s <= most + 1e-6) return s;
        }
        return 1;
    }

    /** Угол рецепта, привязанный к пикселю экрана: при 125 % и 150 % единица окна дробная, и угол не должен мылить текст JEI. */
    public static double snap(double px, int v) {
        if (px <= 0) return v;
        return Math.round(v * px) / px;
    }
}
