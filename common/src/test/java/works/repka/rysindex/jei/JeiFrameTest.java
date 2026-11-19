package works.repka.rysindex.jei;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Рамка рецепта JEI под ячейки за краем категории: шкала энергии Modular Machinery Reborn и спрятанные ячейки. */
class JeiFrameTest {
    /** Шкала энергии MMR 3.0.25: ячейка 16×52 в точке (8, 8) (JeiEnergyPerTickComponent, requireEnergyPerTick(..., 8, 8)). */
    private static final int[] MMR_ENERGY = {8, 8, 16, 52};

    @Test
    void mmrEnergyBarStretchesFrameDown() {
        // квантовый сборщик: рецепты 162×52, шкала до 60
        int[] pads = JeiFrame.pads(162, 52, List.of(MMR_ENERGY, new int[] {30, 8, 18, 18}, new int[] {120, 8, 18, 18}));
        assertArrayEquals(new int[] {0, 0, 0, 8}, pads);
        // орбитальная кузня и прочие: категория запомнила высоту первого рецепта 34, шкала уходит на 26 ниже
        assertArrayEquals(new int[] {0, 0, 0, 26}, JeiFrame.pads(162, 34, List.of(MMR_ENERGY)));
        // высокий рецепт (88): шкала внутри, рамка не меняется
        assertFalse(JeiFrame.any(JeiFrame.pads(162, 88, List.of(MMR_ENERGY))));
    }

    @Test
    void stretchedFrameCoversEverySlot() {
        // рамку JEI рисует по категории с отступом 4: (-4, -4, 170, 60) от угла категории
        int[] pads = JeiFrame.pads(162, 52, List.of(MMR_ENERGY));
        int[] frame = JeiFrame.stretch(-4, -4, 170, 60, pads);
        assertArrayEquals(new int[] {-4, -4, 170, 68}, frame);
        // MMR рисует шкалу на единицу шире ячейки с каждой стороны (18×54 с (-1, -1)): низ 61, внутри рамки до 64
        assertTrue(8 + 53 <= frame[1] + frame[3]);
        // выступы со всех сторон
        int[] all = JeiFrame.pads(100, 50, List.of(new int[] {-6, -3, 16, 16}, new int[] {95, 45, 16, 16}));
        assertArrayEquals(new int[] {6, 3, 11, 11}, all);
        assertArrayEquals(new int[] {-6 - 4, -3 - 4, 108 + 17, 58 + 14}, JeiFrame.stretch(-4, -4, 108, 58, all));
    }

    @Test
    void hiddenSlotsDoNotStretch() {
        // JEI Mekanism Multiblocks прячет затраты калькулятора в (9999, 9999)
        assertFalse(JeiFrame.any(JeiFrame.pads(180, 120, List.of(new int[] {9999, 9999, 16, 16}))));
        // ячейка на границе запаса ещё растягивает, дальше нет
        assertArrayEquals(new int[] {0, 0, 0, 16}, JeiFrame.pads(100, 50, List.of(new int[] {0, 50, 16, 16})));
        assertFalse(JeiFrame.any(JeiFrame.pads(100, 50, List.of(new int[] {0, 50 + JeiFrame.SLOT_REACH, 16, 16}))));
    }
}
