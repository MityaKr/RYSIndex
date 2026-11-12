package works.repka.ryscope.client;

/**
 * Состояние обрезки, как его видит GlStateManager: включена ли она и последний прямоугольник. Заполняется миксином
 * загрузчика. Спрашивать то же у драйвера (glIsEnabled, glGetIntegerv) в каждом кадре дорого: запрос ждёт, пока
 * драйвер выполнит очередь команд. Пока миксин не сработал ни разу, {@link #tracked()} ложно и состояние нужно
 * брать у драйвера.
 */
public final class Scissor {
    private static boolean tracked;
    private static boolean enabled;
    private static final int[] BOX = new int[4];

    private Scissor() {}

    public static void onEnable() {
        tracked = true;
        enabled = true;
    }

    public static void onDisable() {
        tracked = true;
        enabled = false;
    }

    public static void onBox(int x, int y, int width, int height) {
        tracked = true;
        BOX[0] = x;
        BOX[1] = y;
        BOX[2] = width;
        BOX[3] = height;
    }

    public static boolean tracked() {
        return tracked;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Копия последнего прямоугольника: x, y, ширина, высота в пикселях окна. */
    public static int[] box() {
        return BOX.clone();
    }
}
