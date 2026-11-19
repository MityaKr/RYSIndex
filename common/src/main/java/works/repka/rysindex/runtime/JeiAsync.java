package works.repka.rysindex.runtime;

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
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.platform.Platform;
import works.repka.rysindex.ui.Overlay;

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
    private static final String WATCHER = "rysindex-jei-watch";
    private static final Pattern VERIFY_LOCATION = Pattern.compile("Location:\\s+([\\w/$]+)\\.");

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RYSIndex-JEI");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean loading;
    private static volatile boolean queued;
    private static volatile Throwable failure;
    /** Поток, в котором прямо сейчас загружается JEI, или null. */
    private static volatile Thread loader;
    /** Подключение нашего моста, отложенное до конца фоновой загрузки. */
    private static volatile Runnable publish;
    /** Запуск JEI в этом мире закончился (удачно или нет). До этого список ресурсов скоро даст сам JEI. */
    private static volatile boolean settled;
    private static Runnable rawStart;
    private static Runnable rawStop;

    private JeiAsync() {}

    /** Идёт ли фоновая загрузка JEI прямо сейчас. */
    public static boolean loading() {
        return loading;
    }

    /**
     * JEI установлен и вот-вот отдаст свой список ресурсов: запуск в этом мире ещё не закончился и не упал.
     * Индексу тогда незачем собирать творческие вкладки в главном потоке: хватит списка по реестру, а полный
     * список придёт с JEI.
     */
    public static boolean expected() {
        return !settled && failure == null && Platform.get().isModLoaded("jei");
    }

    /** Запуск JEI закончился. Если наш мост так и не подключился, индекс строится заново без расчёта на JEI. */
    private static void settle() {
        settled = true;
        if (Foreign.get() == null) Indexer.request("JEI запущен без моста");
    }

    /** Ошибка, из-за которой JEI не запустился в этом мире, или null. */
    public static Throwable failure() {
        return failure;
    }

    /** Вызов идёт из потока фоновой загрузки JEI: в это время с JEI работает только он. */
    public static boolean onLoaderThread() {
        return Thread.currentThread() == loader;
    }

    /**
     * Мост к JEI подключается только после конца фоновой загрузки: пока она идёт, плагины ещё меняют
     * данные JEI (скрывают предметы, добавляют рецепты), и читать их из других потоков нельзя.
     */
    public static void publishWhenLoaded(Runnable action) {
        publish = action;
    }

    public static Runnable wrapStart(Runnable start) {
        rawStart = start;
        return () -> {
            Config cfg = Config.get();
            if (!cfg.asyncJei || cfg.asyncJeiUnsafe || !cfg.replaceJeiInterface) {
                queued = false;
                try {
                    start.run();
                    failure = null;
                } catch (RuntimeException | Error e) {
                    // поведение JEI не меняем: ошибка уходит дальше, но игрок узнаёт о ней от нас
                    failed(e);
                    throw e;
                }
                settle();
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
            settled = false;
            // остановка должна идти строго после запуска: если запуск ушёл в фон, туда же уходит и она
            if (queued) EXEC.execute(() -> safe(stop, "остановка JEI"));
            else stop.run();
        };
    }

    /** Итог одного запуска JEI: сколько ошибок плагинов попало в журнал и чем запуск закончился. */
    private record Outcome(int errors, Throwable thrown) {}

    private static Outcome watched(Runnable start) {
        AtomicInteger errors = new AtomicInteger();
        org.apache.logging.log4j.core.config.LoggerConfig root = null;
        AbstractAppender watcher = null;
        try {
            // JEI ловит исключения плагинов сам и только пишет их в журнал: считаем такие записи.
            // Наблюдатель вешается на корневой журнал и сам отбирает записи JEI. Вешать его на журнал JEI нельзя:
            // log4j заводит для него отдельную настройку, и записи плагинов пропадают из журнала игры
            // (так было до исправления: при проверке на сборке RepYS ошибки плагинов исчезли из latest.log).
            if (LogManager.getContext(false) instanceof org.apache.logging.log4j.core.LoggerContext context) {
                root = context.getConfiguration().getRootLogger();
                watcher = new AbstractAppender(WATCHER, null, null, true, Property.EMPTY_ARRAY) {
                    @Override
                    public void append(LogEvent event) {
                        if (PLUGIN_LOGGER.equals(event.getLoggerName()) && event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                            errors.incrementAndGet();
                        }
                    }
                };
                watcher.start();
                root.addAppender(watcher, null, null);
            }
        } catch (Throwable t) {
            RYSIndex.LOG.debug("Наблюдение за журналом JEI недоступно: {}", t.toString());
        }
        Throwable thrown = null;
        try {
            if (start != null) start.run();
        } catch (Throwable t) {
            thrown = t;
        } finally {
            if (root != null && watcher != null) {
                try {
                    root.removeAppender(WATCHER);
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
        publish = null;
        loader = Thread.currentThread();
        try {
            async = watched(start);
        } finally {
            loader = null;
            loading = false;
        }
        // для проверки страховки: считать, что плагин упал
        if (Boolean.getBoolean("rysindex.test.asyncFail")) async = new Outcome(async.errors() + 1, async.thrown());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Config cfg = Config.get();
        if (async.thrown() == null && async.errors() <= cfg.asyncJeiTolerated) {
            failure = null;
            Runnable connect = publish;
            publish = null;
            if (connect != null) connect.run();
            settle();
            if (cfg.asyncJeiStrikes != 0) {
                cfg.asyncJeiStrikes = AsyncPolicy.success(cfg.asyncJeiTolerated).strikes();
                Config.save();
            }
            if (async.errors() == 0) {
                RYSIndex.LOG.info("JEI загружен в фоне за {} мс, главный поток не останавливался", ms);
            } else {
                RYSIndex.LOG.info("JEI загружен в фоне за {} мс. Ошибок плагинов: {}, столько же при обычной загрузке",
                    ms, async.errors());
            }
            return;
        }
        if (async.thrown() != null) {
            RYSIndex.LOG.error("JEI не запустился в фоновом потоке, пробую обычным способом", async.thrown());
        } else {
            RYSIndex.LOG.warn("При фоновой загрузке JEI ошибок плагинов: {}. Сверяю с обычной загрузкой.", async.errors());
        }
        queued = false;
        publish = null;
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
            settle();
            AsyncPolicy.Verdict v = AsyncPolicy.failure(first.thrown() != null, first.errors(), sync.errors(), cfg.asyncJeiStrikes, cfg.asyncJeiTolerated);
            cfg.asyncJeiTolerated = v.tolerated();
            cfg.asyncJeiStrikes = v.strikes();
            if (v.strikes() == 0) {
                RYSIndex.LOG.info("Те же ошибки плагинов ({}) есть и при обычной загрузке JEI: фоновый режим остаётся включён.",
                    sync.errors());
            } else if (!v.unsafe()) {
                RYSIndex.LOG.warn("Обычная загрузка JEI прошла чище (ошибок {} против {}). Похоже на разовую гонку потоков: при следующем входе снова фон"
                    + " (отключится после {} неудач подряд).", sync.errors(), first.thrown() != null ? "сбоя запуска" : String.valueOf(first.errors()),
                    AsyncPolicy.STRIKES);
            } else {
                cfg.asyncJeiUnsafe = true;
                RYSIndex.LOG.warn("Обычная загрузка JEI снова прошла чище (ошибок {} против {}), {} раза подряд: фоновый режим для этой установки"
                    + " отключён, включить снова можно в настройках RYSIndex.", sync.errors(),
                    first.thrown() != null ? "сбоя запуска" : String.valueOf(first.errors()), v.strikes());
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
        RYSIndex.LOG.error("JEI не запустился: рецепты из плагинов модов недоступны. Причина: {}. Класс: {}. Миксины на этом классе: {}",
            root.getClass().getSimpleName(), brokenClass, mixins, error);
        String reason = root.getClass().getSimpleName() + (brokenClass == null ? "" : " (" + simpleName(brokenClass) + ")");
        Minecraft mc = Minecraft.getInstance();
        // список ресурсов от JEI не придёт: индекс собирает его сам
        Indexer.request("JEI не запустился");
        mc.execute(() -> {
            Component head = Component.translatable("rysindex.jei.failed", reason).withStyle(ChatFormatting.RED);
            Component hint = mixins.isEmpty()
                ? Component.translatable("rysindex.jei.failed.log")
                : Component.translatable("rysindex.jei.failed.mixin", simpleName(brokenClass), String.join(", ", mixins));
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
            RYSIndex.LOG.error("Не удалось: {}", what, t);
        }
    }
}
