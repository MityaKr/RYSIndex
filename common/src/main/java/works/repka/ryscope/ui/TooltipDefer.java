package works.repka.ryscope.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Подсказки, которые мод рисует прямо во время рисования своего рецепта (так делает значок Almost Unified), попадали под рамку
 * прокрутки списка рецептов и обрезались по краю окна. Пока рецепты рисуются, такие подсказки откладываются (перехват в
 * {@code GuiGraphicsMixin}) и рисуются потом, вместе с остальными подсказками окна, поверх всего.
 */
public final class TooltipDefer {
    private static final List<Runnable> PENDING = new ArrayList<>();
    private static boolean capturing;
    /** Сколько подсказок было отложено в последнем кадре (для автотеста). */
    public static int lastCount;

    private TooltipDefer() {
    }

    /** Начало рисования рецептов: подсказки, нарисованные внутри, откладываются. */
    public static void begin() {
        PENDING.clear();
        capturing = true;
    }

    public static void end() {
        capturing = false;
    }

    public static boolean capturing() {
        return capturing;
    }

    /**
     * Рисует {@code body} в увеличенной позе так, чтобы подсказки из неё легли в список отложенных и потом нарисовались
     * обычного размера у курсора. Если сейчас ничего не откладывается, отложенное рисуется сразу после {@code body}:
     * к этому времени {@code body} должен вернуть позу, как была.
     */
    public static void unscaled(Runnable body) {
        boolean was = capturing;
        capturing = true;
        try {
            body.run();
        } finally {
            capturing = was;
        }
        if (!was) flush();
    }

    public static void add(Runnable tooltip) {
        PENDING.add(tooltip);
    }

    /** Рисует отложенные подсказки; координаты у них уже экранные. */
    public static void flush() {
        capturing = false;
        lastCount = PENDING.size();
        if (PENDING.isEmpty()) return;
        List<Runnable> tips = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Runnable tip : tips) {
            try {
                tip.run();
            } catch (Throwable ignored) {
                // подсказка чужого мода
            }
        }
    }

    /** Отложенные подсказки не нужны (курсор ушёл на панель или окно закрылось). */
    public static void drop() {
        capturing = false;
        PENDING.clear();
    }
}
