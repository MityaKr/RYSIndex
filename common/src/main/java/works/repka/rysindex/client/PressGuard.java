package works.repka.rysindex.client;

/**
 * Пара «нажатие и отпускание» кнопки мыши. Если нажатие забрал наш слой, отпускание той же кнопки над тем же окном
 * тоже наше и до окна игры не доходит, как у JEI (GuiInputController держит захват кнопки до отпускания).
 * Иначе окно-контейнер получает отпускание без своего нажатия: если к этому времени на курсоре уже лежит выданный
 * предмет, а мышь вне окна, игра считает это кликом мимо окна и выбрасывает предмет на землю.
 */
final class PressGuard {
    private static final int BUTTONS = 8;
    private final Object[] pressed = new Object[BUTTONS];

    /** Нажатие кнопки над окном {@code screen} забрал наш слой. */
    void press(int button, Object screen) {
        if (button >= 0 && button < BUTTONS) pressed[button] = screen;
    }

    /** @return отпускание парное нашему нажатию над тем же окном: его тоже забираем */
    boolean release(int button, Object screen) {
        if (button < 0 || button >= BUTTONS) return false;
        Object was = pressed[button];
        pressed[button] = null;
        return was != null && was == screen;
    }
}
