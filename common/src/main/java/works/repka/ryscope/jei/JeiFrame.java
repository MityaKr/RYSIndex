package works.repka.ryscope.jei;

import java.util.List;

/**
 * Рамка рецепта JEI, когда ячейки мода выходят за размер его категории. Без классов игры: считается юнит-тестом.
 * <p>
 * Пример: Modular Machinery Reborn ставит шкалу энергии 16×52 в точку (8, 8), а категория запоминает высоту первого
 * рецепта машины (34 или 52). Шкала уходит на 8–26 единиц ниже края, а рамку JEI рисует сам по размеру категории,
 * и шкала торчит из неё вниз.
 */
public final class JeiFrame {
    private JeiFrame() {}

    /** Ячейка, которая не касается рецепта даже с этим запасом, спрятана модом для поиска и в размер не идёт. */
    public static final int SLOT_REACH = 32;

    /**
     * Насколько ячейки {@code rects} ({x, y, w, h} от угла категории) выходят за категорию {@code w}×{@code h}:
     * {слева, сверху, справа, снизу}, не меньше нуля.
     */
    public static int[] pads(int w, int h, List<int[]> rects) {
        int left = 0, top = 0, right = w, bottom = h;
        for (int[] r : rects) {
            int x = r[0], y = r[1], rw = r[2], rh = r[3];
            if (x >= w + SLOT_REACH || y >= h + SLOT_REACH || x + rw <= -SLOT_REACH || y + rh <= -SLOT_REACH) continue;
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x + rw);
            bottom = Math.max(bottom, y + rh);
        }
        return new int[] {-left, -top, right - w, bottom - h};
    }

    /** Прямоугольник рамки JEI {x, y, w, h}, растянутый на выступы {@code pads}: в нём помещаются все ячейки. */
    public static int[] stretch(int x, int y, int w, int h, int[] pads) {
        return new int[] {x - pads[0], y - pads[1], w + pads[0] + pads[2], h + pads[1] + pads[3]};
    }

    public static boolean any(int[] pads) {
        return pads[0] > 0 || pads[1] > 0 || pads[2] > 0 || pads[3] > 0;
    }
}
