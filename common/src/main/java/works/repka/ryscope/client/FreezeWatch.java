package works.repka.ryscope.client;

import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import works.repka.ryscope.RYScope;

/**
 * Сторож зависаний: если кадр не заканчивается дольше {@link #FREEZE_MS}, несколько раз снимает стек потока
 * отрисовки и пишет в logs/ryscope-freeze-*.txt, где стоит игра, сколько занято памяти и сколько было сборок мусора.
 * Работает на любой мод, не только на RYScope: ищет причину падения FPS, которую по обычному журналу не видно.
 * Сам поток почти ничего не стоит: раз в полсекунды сравнивает одно число.
 */
public final class FreezeWatch {
    private static final long FREEZE_MS = 2000;
    private static final int SAMPLES = 20;
    /** Между отчётами не меньше: долгое зависание не должно забить диск. */
    private static final long REPORT_GAP_MS = 5 * 60_000L;
    /** Не больше отчётов за запуск игры. */
    private static final int MAX_REPORTS = 5;

    private static volatile long lastFrame;
    /** Сколько кадров подряд шли дольше {@link #SLOW_MS}: FPS ниже 4 тоже стоит разобрать, хоть кадры и идут. */
    private static volatile int slowStreak;
    private static final long SLOW_MS = 250;
    private static final int SLOW_FRAMES = 8;
    private static volatile Thread renderThread;
    private static long lastReport;
    private static int reports;
    private static boolean started;

    private FreezeWatch() {}

    /** Конец кадра: вызывать из потока отрисовки. */
    public static void frame() {
        long now = System.currentTimeMillis();
        long last = lastFrame;
        slowStreak = last != 0 && now - last > SLOW_MS ? slowStreak + 1 : 0;
        lastFrame = now;
        if (!started) start();
    }

    private static synchronized void start() {
        if (started) return;
        started = true;
        renderThread = Thread.currentThread();
        Thread t = new Thread(FreezeWatch::watch, "RYScope-FreezeWatch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void watch() {
        while (true) {
            try {
                Thread.sleep(500);
                long now = System.currentTimeMillis();
                boolean frozen = lastFrame != 0 && now - lastFrame >= FREEZE_MS;
                boolean slow = slowStreak >= SLOW_FRAMES;
                if (!frozen && !slow) continue;
                if (reports >= MAX_REPORTS || now - lastReport < REPORT_GAP_MS) continue;
                Minecraft mc = Minecraft.getInstance();
                // меню загрузки и выход из игры кадрами не считаются
                if (mc == null || mc.level == null || mc.getOverlay() != null) continue;
                // окно не в фокусе: частоту кадров мог снизить мод вроде Dynamic FPS, это не зависание
                if (slow && !frozen && !mc.isWindowActive()) continue;
                report(frozen ? now - lastFrame : -slowStreak);
                lastReport = System.currentTimeMillis();
                reports++;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                RYScope.LOG.warn("Сторож зависаний: {}", t.toString());
            }
        }
    }

    private static void report(long stuckFor) throws InterruptedException, IOException {
        Thread thread = renderThread;
        if (thread == null) return;
        long gcBefore = gcCount(), gcTimeBefore = gcTime();
        Map<String, Integer> hits = new HashMap<>();
        List<StackTraceElement[]> samples = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            StackTraceElement[] stack = thread.getStackTrace();
            samples.add(stack);
            for (int k = 0; k < Math.min(stack.length, 12); k++) hits.merge(stack[k].toString(), 1, Integer::sum);
            Thread.sleep(100);
        }
        Runtime rt = Runtime.getRuntime();
        StringBuilder out = new StringBuilder();
        if (stuckFor >= 0) out.append("Кадр не заканчивается уже ").append(stuckFor).append(" мс (поток ").append(thread.getName()).append(")\n");
        else out.append("Кадры медленные: ").append(-stuckFor).append(" подряд дольше ").append(SLOW_MS).append(" мс (поток ").append(thread.getName()).append(")\n");
        out.append("Память: занято ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append(" МБ из ").append(rt.maxMemory() >> 20)
            .append(" МБ; сборок мусора за 2 с: ").append(gcCount() - gcBefore).append(", их время ").append(gcTime() - gcTimeBefore).append(" мс\n\n");
        out.append("Чаще всего в стеке (из ").append(SAMPLES).append(" снимков):\n");
        hits.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40)
            .forEach(e -> out.append(String.format("%3d  %s%n", e.getValue(), e.getKey())));
        out.append("\nПервый снимок целиком:\n");
        for (StackTraceElement e : samples.get(0)) out.append("    at ").append(e).append('\n');
        out.append("\nПоследний снимок целиком:\n");
        for (StackTraceElement e : samples.get(samples.size() - 1)) out.append("    at ").append(e).append('\n');
        File dir = new File(Minecraft.getInstance().gameDirectory, "logs");
        dir.mkdirs();
        File file = new File(dir, "ryscope-freeze-" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date()) + ".txt");
        Files.writeString(file.toPath(), out.toString(), StandardCharsets.UTF_8);
        RYScope.LOG.warn("Игра {}: стек потока отрисовки записан в {}", stuckFor >= 0 ? "зависла на " + stuckFor + " мс" : "тормозит", file.getName());
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, gc.getCollectionCount());
        return n;
    }

    private static long gcTime() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, gc.getCollectionTime());
        return n;
    }
}
