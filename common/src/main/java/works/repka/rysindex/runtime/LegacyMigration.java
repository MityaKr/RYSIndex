package works.repka.rysindex.runtime;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Перенос данных игрока после переименования мода RYScope в RYSIndex (0.3.0).
 * Один раз, с отметкой в {@code config/rysindex/migration.properties}; старые файлы не трогает.
 * <ul>
 *   <li>папка {@code config/ryscope} копируется в {@code config/rysindex}: закладки, доска, настройки, кэши, станции;
 *       в JSON ссылки {@code "ryscope:...} (категории, рецепты в закладках) становятся {@code "rysindex:...};</li>
 *   <li>клавиши {@code key.ryscope.*} из {@code options.txt} дописываются как {@code key.rysindex.*};</li>
 *   <li>файлы мира копируются через {@link #copyIfAbsent}.</li>
 * </ul>
 * Без классов игры: проверяется юнит-тестами.
 */
public final class LegacyMigration {
    private LegacyMigration() {}

    public static final String OLD_ID = "ryscope";
    public static final String NEW_ID = "rysindex";
    public static final String MARK_FILE = "migration.properties";
    static final String OLD_KEY = "key_key." + OLD_ID + ".";
    static final String NEW_KEY = "key_key." + NEW_ID + ".";
    /** Копия options.txt до дописывания клавиш, на случай отката. */
    public static final String OPTIONS_BACKUP = "options-before-rysindex.txt";

    /**
     * Копирует {@code configDir/ryscope} в {@code configDir/rysindex}, если это ещё не сделано.
     * Файлы, которые уже есть в новой папке, не перезаписываются.
     * @return сколько файлов скопировано; 0, если копировать нечего или перенос уже был
     */
    public static synchronized int migrateConfig(Path configDir) throws IOException {
        Path dir = configDir.resolve(NEW_ID);
        Properties mark = readMark(dir);
        if (mark.containsKey("config")) return 0;
        Path old = configDir.resolve(OLD_ID);
        int copied = 0;
        if (Files.isDirectory(old)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(old)) {
                files = walk.filter(Files::isRegularFile).toList();
            }
            for (Path file : files) {
                Path target = dir.resolve(old.relativize(file).toString());
                if (Files.exists(target)) continue;
                Files.createDirectories(target.getParent());
                if (file.getFileName().toString().endsWith(".json")) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    Files.writeString(target, rewriteJson(text), StandardCharsets.UTF_8);
                } else {
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
                copied++;
            }
        }
        mark.setProperty("config", copied + " files from config/" + OLD_ID);
        writeMark(dir, mark);
        return copied;
    }

    /**
     * Дописывает в options.txt привязки {@code key.rysindex.*} по старым {@code key.ryscope.*}, если новых ещё нет.
     * Перед правкой кладёт копию файла в {@code configDir/rysindex/options-before-rysindex.txt}.
     * @return сколько клавиш перенесено; 0, если нечего или перенос уже был
     */
    public static synchronized int migrateKeys(Path optionsFile, Path configDir) throws IOException {
        Path dir = configDir.resolve(NEW_ID);
        Properties mark = readMark(dir);
        if (mark.containsKey("keys")) return 0;
        int moved = 0;
        if (Files.isRegularFile(optionsFile)) {
            List<String> lines = Files.readAllLines(optionsFile, StandardCharsets.UTF_8);
            List<String> out = rewriteOptions(lines);
            moved = out.size() - lines.size();
            if (moved > 0) {
                Files.createDirectories(dir);
                Path backup = dir.resolve(OPTIONS_BACKUP);
                if (!Files.exists(backup)) Files.copy(optionsFile, backup);
                Files.write(optionsFile, out, StandardCharsets.UTF_8);
            }
        }
        mark.setProperty("keys", moved + " key bindings from key." + OLD_ID);
        writeMark(dir, mark);
        return moved;
    }

    /**
     * Копирует файл, если старый есть, а нового нет.
     * @return true, если скопирован
     */
    public static boolean copyIfAbsent(Path from, Path to) {
        try {
            if (!Files.isRegularFile(from) || Files.exists(to)) return false;
            if (to.getParent() != null) Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Ссылки на категории и рецепты мода в JSON: {@code "ryscope:x"} становится {@code "rysindex:x"}. */
    static String rewriteJson(String text) {
        return text.replace("\"" + OLD_ID + ":", "\"" + NEW_ID + ":");
    }

    /** Строки options.txt плюс новые привязки в конце: значение каждой старой клавиши, для которой новой ещё нет. */
    static List<String> rewriteOptions(List<String> lines) {
        Set<String> present = new HashSet<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (line.startsWith(NEW_KEY) && colon > 0) present.add(line.substring(NEW_KEY.length(), colon));
        }
        List<String> out = new ArrayList<>(lines);
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (!line.startsWith(OLD_KEY) || colon < 0) continue;
            String name = line.substring(OLD_KEY.length(), colon);
            if (name.isEmpty() || !present.add(name)) continue;
            out.add(NEW_KEY + name + line.substring(colon));
        }
        return out;
    }

    private static Properties readMark(Path dir) {
        Properties p = new Properties();
        Path file = dir.resolve(MARK_FILE);
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) {
                // испорченная отметка: перенос повторится, но новые файлы он не перезаписывает
            }
        }
        return p;
    }

    private static void writeMark(Path dir, Properties mark) throws IOException {
        Files.createDirectories(dir);
        try (Writer w = Files.newBufferedWriter(dir.resolve(MARK_FILE), StandardCharsets.UTF_8)) {
            mark.store(w, "RYSIndex: data moved from RYScope once; delete a line to repeat that step");
        }
    }
}
