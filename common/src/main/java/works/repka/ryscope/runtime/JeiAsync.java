package works.repka.ryscope.runtime;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.platform.Platform;
import works.repka.ryscope.ui.Overlay;

/**
 * Запуск JEI в фоновом потоке. Сам JEI загружает плагины в главном потоке, и в больших сборках
 * это секунды замершей игры при входе в мир. Здесь тот же запуск выполняется в отдельном потоке.
 *
 * Страховка: если в фоне ошибок больше, чем бывает при обычной загрузке, JEI перезапускается обычным
 * способом. Дальше два исхода:
 * <ul>
 *   <li>обычная загрузка прошла чище: виноват фоновый режим, для этой установки он отключается;</li>
 *   <li>ошибок столько же: плагины сломаны сами по себе, фоновый режим остаётся, а это число ошибок
 *       запоминается как привычное.</li>
 * </ul>
 * Если JEI не запустился вовсе, игрок получает сообщение с причиной: без JEI нет рецептов из плагинов модов.
 */
public final class JeiAsync {
    private static final String PLUGIN_LOGGER = "mezz.jei.library.load.PluginCaller";
    private static final Pattern VERIFY_LOCATION = Pattern.compile("Location:\\s+([\\w/$]+)\\.");

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RYScope-JEI");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean loading;
    private static volatile boolean queued;
    private static volatile Throwable failure;
    private static Runnable rawStart;
    private static Runnable rawStop;

    private JeiAsync() {}

    /** Идёт ли фоновая загрузка JEI прямо сейчас. */
    public static boolean loading() {
        return loading;
    }

    /** Ошибка, из-за которой JEI не запустился в этом мире, или null. */
    public static Throwable failure() {
        return failure;
    }

    public static Runnable wrapStart(Runnable start) {
        rawStart = start;
        return () -> {
            Config cfg = Config.get();
            if (!cfg.asyncJei || cfg.asyncJeiUnsafe) {
                queued = false;
                try {
                    start.run();
                    failure = null;
                } catch (RuntimeException | Error e) {
                    // поведение JEI не меняем: ошибка уходит дальше, но игрок узнаёт о ней от нас
                    failed(e);
                    throw e;
                }
                return;
            }
            loading = true;
            queued = true;
            EXEC.execute(() -> runStart(start));
        };
    }

    public static Runnable wrapStop(Runnable stop) {
        rawStop = stop;
        return () -> {
            // остановка должна идти строго после запуска: если запуск ушёл в фон, туда же уходит и она
            if (queued) EXEC.execute(() -> safe(stop, "остановка JEI"));
            else stop.run();
        };
    }

    /** Итог одного запуска JEI: сколько ошибок плагинов попало в журнал и чем запуск закончился. */
    private record Outcome(int errors, Throwable thrown) {}

    private static Outcome watched(Runnable start) {
        AtomicInteger errors = new AtomicInteger();
        org.apache.logging.log4j.core.Logger logger = null;
        AbstractAppender watcher = null;
        try {
            // JEI ловит исключения плагинов сам и только пишет их в журнал: считаем такие записи
            if (LogManager.getLogger(PLUGIN_LOGGER) instanceof org.apache.logging.log4j.core.Logger core) {
                logger = core;
                watcher = new AbstractAppender("ryscope-jei-watch", null, null, true, Property.EMPTY_ARRAY) {
                    @Override
                    public void append(LogEvent event) {
                        if (event.getLevel().isMoreSpecificThan(Level.ERROR)) errors.incrementAndGet();
                    }
                };
                watcher.start();
                logger.addAppender(watcher);
            }
        } catch (Throwable t) {
            RYScope.LOG.debug("Наблюдение за журналом JEI недоступно: {}", t.toString());
        }
        Throwable thrown = null;
        try {
            if (start != null) start.run();
        } catch (Throwable t) {
            thrown = t;
        } finally {
            if (logger != null && watcher != null) {
                try {
                    logger.removeAppender(watcher);
                    watcher.stop();
                } catch (Throwable ignored) {
                    // журнал переживёт
                }
            }
        }
        return new Outcome(errors.get(), thrown);
    }

    private static void runStart(Runnable start) {
        long t0 = System.nanoTime();
        Outcome async;
        try {
            async = watched(start);
        } finally {
            loading = false;
        }
        // для проверки страховки: считать, что плагин упал
        if (Boolean.getBoolean("ryscope.test.asyncFail")) async = new Outcome(async.errors() + 1, async.thrown());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Config cfg = Config.get();
        if (async.thrown() == null && async.errors() <= cfg.asyncJeiTolerated) {
            failure = null;
            if (async.errors() == 0) {
                RYScope.LOG.info("JEI загружен в фоне за {} мс, главный поток не останавливался", ms);
            } else {
                RYScope.LOG.info("JEI загружен в фоне за {} мс. Ошибок плагинов: {}, столько же при обычной загрузке",
                    ms, async.errors());
            }
            return;
        }
        if (async.thrown() != null) {
            RYScope.LOG.error("JEI не запустился в фоновом потоке, пробую обычным способом", async.thrown());
        } else {
            RYScope.LOG.warn("При фоновой загрузке JEI ошибок плагинов: {}. Сверяю с обычной загрузкой.", async.errors());
        }
        queued = false;
        Outcome first = async;
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().level == null) return;
            safe(rawStop, "остановка JEI");
            Outcome sync = watched(rawStart);
            if (sync.thrown() != null) {
                // JEI не запускается ни в каком режиме: фоновый поток ни при чём
                failed(sync.thrown());
                return;
            }
            failure = null;
            if (first.thrown() == null && sync.errors() >= first.errors()) {
                cfg.asyncJeiTolerated = sync.errors();
                RYScope.LOG.info("Те же ошибки плагинов ({}) есть и при обычной загрузке JEI: фоновый режим остаётся включён.",
                    sync.errors());
            } else {
                cfg.asyncJeiUnsafe = true;
                RYScope.LOG.warn("Обычная загрузка JEI прошла чище (ошибок {} против {}): фоновый режим для этой установки отключён.",
                    sync.errors(), first.thrown() != null ? "сбоя запуска" : String.valueOf(first.errors()));
            }
            Config.save();
        });
    }

    /** JEI не запустился: запомнить причину и сказать игроку, иначе он увидит просто «нет рецептов». */
    private static void failed(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        failure = root;
        String brokenClass = brokenClass(root);
        List<String> mixins = brokenClass == null ? List.of() : Platform.get().mixinsOn(brokenClass);
        RYScope.LOG.error("JEI не запустился: рецепты из плагинов модов недоступны. Причина: {}. Класс: {}. Миксины на этом классе: {}",
            root.getClass().getSimpleName(), brokenClass, mixins, error);
        String reason = root.getClass().getSimpleName() + (brokenClass == null ? "" : " (" + simpleName(brokenClass) + ")");
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            Component head = Component.translatable("ryscope.jei.failed", reason).withStyle(ChatFormatting.RED);
            Component hint = mixins.isEmpty()
                ? Component.translatable("ryscope.jei.failed.log")
                : Component.translatable("ryscope.jei.failed.mixin", simpleName(brokenClass), String.join(", ", mixins));
            mc.gui.getChat().addMessage(head);
            mc.gui.getChat().addMessage(hint.copy().withStyle(ChatFormatting.GRAY));
            Overlay.toast(head.getString(), 8000);
        });
    }

    /** Класс, на котором сломалась загрузка: у VerifyError он в тексте, у остальных это верх стека. */
    private static String brokenClass(Throwable root) {
        if (root instanceof VerifyError && root.getMessage() != null) {
            Matcher m = VERIFY_LOCATION.matcher(root.getMessage());
            if (m.find()) return m.group(1).replace('/', '.');
        }
        if (root instanceof LinkageError) {
            StackTraceElement[] stack = root.getStackTrace();
            if (stack.length > 0) return stack[0].getClassName();
        }
        return null;
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static void safe(Runnable r, String what) {
        try {
            if (r != null) r.run();
        } catch (Throwable t) {
            RYScope.LOG.error("Не удалось: {}", what, t);
        }
    }
}
