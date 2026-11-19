package works.repka.rysindex.runtime;

/** Когда {@link Ae2Terminal} перечитывает список сети открытого терминала. Без классов игры: проверяется юнит-тестом. */
final class NetworkRefresh {
    private NetworkRefresh() {}

    /** Без перехвата обновлений AE2 список перечитывается по этому таймеру. */
    static final long REFRESH_MS = 500;
    /**
     * Чаще этого список не перечитывается, даже если сервер шлёт изменения каждый тик (большая сеть с автокрафтом):
     * полный проход по списку стоил 2–5 мс главного потока клиента на каждое обновление (разбор логики 19.11, 4.4).
     */
    static final long MIN_REFRESH_MS = 250;

    /** Пора ли перечитать: другой терминал сразу; иначе по событию (или таймеру без перехвата), но не чаще {@link #MIN_REFRESH_MS}. */
    static boolean due(boolean sameMenu, boolean hooked, boolean changed, long sinceLast) {
        if (!sameMenu) return true;
        if (sinceLast < MIN_REFRESH_MS) return false;
        return hooked ? changed : sinceLast >= REFRESH_MS;
    }
}
