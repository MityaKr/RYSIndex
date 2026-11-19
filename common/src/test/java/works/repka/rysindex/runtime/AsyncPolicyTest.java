package works.repka.rysindex.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Фоновая загрузка JEI: один сбой не отключает фон, два подряд отключают, удачный запуск прощает. */
class AsyncPolicyTest {
    @Test
    void oneCrashIsForgiven() {
        // журнал автора 18.11: фон упал (гонка с творческими вкладками), обычная загрузка дала 5 ошибок
        AsyncPolicy.Verdict v = AsyncPolicy.failure(true, 0, 5, 0, 5);
        assertFalse(v.unsafe());
        assertEquals(1, v.strikes());
    }

    @Test
    void twoInARowTurnBackgroundOff() {
        AsyncPolicy.Verdict first = AsyncPolicy.failure(true, 0, 5, 0, 5);
        AsyncPolicy.Verdict second = AsyncPolicy.failure(false, 7, 5, first.strikes(), first.tolerated());
        assertTrue(second.unsafe());
    }

    @Test
    void successResetsStrikes() {
        AsyncPolicy.Verdict first = AsyncPolicy.failure(true, 0, 5, 0, 5);
        int strikes = AsyncPolicy.success(first.tolerated()).strikes();
        assertEquals(0, strikes);
        assertFalse(AsyncPolicy.failure(true, 0, 5, strikes, 5).unsafe());
    }

    @Test
    void samePluginErrorsRaiseTolerance() {
        // в фоне 6 ошибок при терпимости 5, обычным способом тоже 6: это плагины, фон остаётся, терпимость 6
        AsyncPolicy.Verdict v = AsyncPolicy.failure(false, 6, 6, 1, 5);
        assertFalse(v.unsafe());
        assertEquals(0, v.strikes());
        assertEquals(6, v.tolerated());
        // терпимость не падает, если обычная загрузка вдруг дала меньше
        assertEquals(6, AsyncPolicy.failure(false, 3, 4, 0, 6).tolerated());
    }
}
