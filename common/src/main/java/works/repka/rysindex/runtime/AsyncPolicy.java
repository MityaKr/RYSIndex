package works.repka.rysindex.runtime;

/**
 * Когда фоновая загрузка JEI отключается сама. Без классов игры: правило проверяется юнит-тестом.
 * <p>
 * До 0.2.16 хватало одного сбоя: у автора 18.11 JEI в фоне упал на гонке (NPE в {@code ItemStackListFactory.addFromTab}:
 * главный поток в это время пересобирал творческие вкладки), обычная загрузка прошла, и фон выключился навсегда,
 * хотя до того полсотни запусков подряд шли в фоне с теми же 5 ошибками плагинов. Теперь фон отключается только после
 * {@link #STRIKES} неудачных запусков подряд, удачный запуск счёт сбрасывает, а терпимость к ошибкам плагинов
 * только растёт.
 */
public final class AsyncPolicy {
    private AsyncPolicy() {}

    /** Столько неудачных фоновых запусков подряд отключают фон. */
    public static final int STRIKES = 2;

    /** Итог: отключить ли фон, новый счёт неудач и терпимость к ошибкам плагинов. */
    public record Verdict(boolean unsafe, int strikes, int tolerated) {}

    /** Фон загрузился с допустимым числом ошибок: счёт неудач сбрасывается. */
    public static Verdict success(int tolerated) {
        return new Verdict(false, 0, tolerated);
    }

    /**
     * Фон не удался (сбой или ошибок больше терпимого), после него обычная загрузка дала {@code syncErrors}.
     * Если ошибок столько же и фон не падал, это ошибки самих плагинов: терпимость поднимается, фон остаётся.
     * Иначе ещё одна неудача; на {@link #STRIKES}-й подряд фон отключается.
     */
    public static Verdict failure(boolean asyncThrew, int asyncErrors, int syncErrors, int strikes, int tolerated) {
        if (!asyncThrew && syncErrors >= asyncErrors) return new Verdict(false, 0, Math.max(tolerated, syncErrors));
        int next = strikes + 1;
        return new Verdict(next >= STRIKES, next, tolerated);
    }
}
