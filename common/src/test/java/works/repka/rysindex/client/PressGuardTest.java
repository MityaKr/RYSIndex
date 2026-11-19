package works.repka.rysindex.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PressGuardTest {
    private final Object chest = new Object();
    private final Object other = new Object();

    @Test
    void releaseAfterOurPressIsOurs() {
        PressGuard guard = new PressGuard();
        guard.press(0, chest);
        assertTrue(guard.release(0, chest), "отпускание после выдачи не должно дойти до окна");
    }

    @Test
    void releaseWithoutOurPressGoesToScreen() {
        PressGuard guard = new PressGuard();
        assertFalse(guard.release(0, chest), "клик по окну игры остаётся за игрой");
    }

    @Test
    void pairIsConsumedOnce() {
        PressGuard guard = new PressGuard();
        guard.press(0, chest);
        assertTrue(guard.release(0, chest));
        assertFalse(guard.release(0, chest), "второе отпускание без нажатия уже не наше");
    }

    @Test
    void buttonsAreTrackedSeparately() {
        PressGuard guard = new PressGuard();
        guard.press(1, chest);
        assertFalse(guard.release(0, chest));
        assertTrue(guard.release(1, chest));
    }

    @Test
    void releaseOverAnotherScreenIsNotOurs() {
        PressGuard guard = new PressGuard();
        guard.press(0, chest);
        assertFalse(guard.release(0, other), "нажатие открыло другое окно: его отпускание не трогаем");
        assertFalse(guard.release(0, chest), "захват снят и после отпускания над другим окном");
    }

    @Test
    void oddButtonsAreIgnored() {
        PressGuard guard = new PressGuard();
        guard.press(-1, chest);
        guard.press(42, chest);
        assertFalse(guard.release(-1, chest));
        assertFalse(guard.release(42, chest));
    }
}
