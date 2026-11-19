package works.repka.rysindex.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Список сети AE2: по событию, но не чаще 250 мс; другой терминал сразу. */
class NetworkRefreshTest {
    @Test
    void throttlesFloodOfUpdates() {
        // сервер шлёт изменения каждый тик: через 50 мс не перечитываем, через 250 мс да
        assertFalse(NetworkRefresh.due(true, true, true, 50));
        assertTrue(NetworkRefresh.due(true, true, true, NetworkRefresh.MIN_REFRESH_MS));
        // без изменений не перечитываем никогда
        assertFalse(NetworkRefresh.due(true, true, false, 10_000));
    }

    @Test
    void otherTerminalRightAway() {
        assertTrue(NetworkRefresh.due(false, true, false, 0));
    }

    @Test
    void timerWithoutHook() {
        assertFalse(NetworkRefresh.due(true, false, false, 300));
        assertTrue(NetworkRefresh.due(true, false, false, NetworkRefresh.REFRESH_MS));
    }
}
