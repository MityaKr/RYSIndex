package works.repka.rysindex.client;

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
import works.repka.rysindex.RYSIndex;

/**
 * Сторож зависаний: если кадр не заканчивается дольше {@link #FREEZE_MS}, несколько раз снимает стек потока
 * отрисовки и пишет в logs/rysindex-freeze-*.txt, где стоит игра, сколько занято памяти и сколько было сборок мусора.
 * Работает на любой мод, не только на RYSIndex: ищет причину падения FPS, которую по обычному журналу не видно.
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
        Thread t = new Thread(FreezeWatch::watch, "RYSIndex-FreezeWatch");
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
                RYSIndex.LOG.warn("Freeze watchdog: {}", t.toString());
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
        if (stuckFor >= 0) out.append("The frame has not finished for ").append(stuckFor).append(" ms (thread ").append(thread.getName()).append(")\n");
        else out.append("Frames are slow: ").append(-stuckFor).append(" in a row longer than ").append(SLOW_MS).append(" ms (thread ").append(thread.getName()).append(")\n");
        out.append("Memory: used ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append(" MB of ").append(rt.maxMemory() >> 20)
            .append(" MB; garbage collections in 2 s: ").append(gcCount() - gcBefore).append(", their time ").append(gcTime() - gcTimeBefore).append(" ms\n\n");
        out.append("Most frequent in the stack (of ").append(SAMPLES).append(" samples):\n");
        hits.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40)
            .forEach(e -> out.append(String.format("%3d  %s%n", e.getValue(), e.getKey())));
        out.append("\nFirst sample in full:\n");
        for (StackTraceElement e : samples.get(0)) out.append("    at ").append(e).append('\n');
        out.append("\nLast sample in full:\n");
        for (StackTraceElement e : samples.get(samples.size() - 1)) out.append("    at ").append(e).append('\n');
        File dir = new File(Minecraft.getInstance().gameDirectory, "logs");
        dir.mkdirs();
        File file = new File(dir, "rysindex-freeze-" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date()) + ".txt");
        Files.writeString(file.toPath(), out.toString(), StandardCharsets.UTF_8);
        RYSIndex.LOG.warn("Game {}: render thread stack written to {}", stuckFor >= 0 ? "froze for " + stuckFor + " ms" : "is lagging", file.getName());
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
