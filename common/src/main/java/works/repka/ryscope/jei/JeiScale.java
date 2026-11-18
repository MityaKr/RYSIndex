package works.repka.ryscope.jei;

/**
 * Масштаб рецептов JEI в окне RYScope. Их ячейки и мелкий текст рассчитаны на единицы GUI игры и рядом со шрифтами и
 * плашками окна выглядели мелко. Рецепт увеличивается примерно в {@link #TARGET} раза так, чтобы на пиксель JEI
 * приходилось целое число пикселей экрана: тогда текст JEI не мылится.
 */
public final class JeiScale {
    /** Желаемый множитель; настоящий ближайший к нему с целым числом пикселей. */
    public static final double TARGET = 1.35;
    /** Шире этого рецепт не увеличивается, а более широкий уменьшается: карточка с ним остаётся на экране рядом со списком. */
    public static final int MAX_W = 300;
    /** Выше этого рецепт не увеличивается (высокие листаются прокруткой и не уменьшаются). */
    public static final int MAX_H = 220;
    /** Наименьшая ширина, до которой ужимается предел на тесном экране. */
    public static final int MIN_W = 140;
    /** Место окна, которое занято не рецептом: список справа, рейка, поля и кнопки карточки. */
    public static final int WINDOW_CHROME = 360;

    private JeiScale() {}

    /** Предел ширины рецепта для экрана шириной {@code screenW} единиц. */
    public static int maxWidth(int screenW) {
        return Math.max(MIN_W, Math.min(MAX_W, screenW - WINDOW_CHROME));
    }

    /**
     * Множитель для рецепта {@code w}×{@code h} единиц окна при {@code px} пикселях экрана в единице.
     * Обычный рецепт: наибольшее целое число пикселей на пиксель JEI, не больше {@code px·TARGET} (с округлением),
     * при котором рецепт не шире {@code maxW} и не выше {@code maxH}. Не меньше 1; если такого целого нет, 1.
     * Рецепт шире {@code maxW} уменьшается до целого числа пикселей, а если так выходит мельче половины, ровно по ширине (не мельче половины).
     */
    public static double pick(double px, int w, int h, int maxW, int maxH) {
        if (px <= 0 || w <= 0 || h <= 0) return 1;
        double fitW = (double) maxW / w;
        if (fitW < 1) {
            int p = (int) Math.floor(px * fitW + 1e-6);
            double s = p / px;
            // целого числа пикселей не мельче половины нет: тогда ровно по ширине, лишь бы влез
            return s >= 0.5 ? s : Math.max(0.5, fitW);
        }
        double most = Math.min(fitW, Math.max(1, (double) maxH / h));
        for (long p = Math.round(px * TARGET); p / px >= 1 - 1e-6; p--) {
            double s = p / px;
            if (s <= most + 1e-6) return s;
        }
        return 1;
    }
}
