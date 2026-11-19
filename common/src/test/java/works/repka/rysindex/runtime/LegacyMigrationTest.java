package works.repka.rysindex.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Перенос данных игрока из RYScope в RYSIndex: папка настроек, клавиши, файлы мира. */
class LegacyMigrationTest {
    @TempDir
    Path tmp;

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void copiesConfigOnceAndKeepsOld() throws Exception {
        Path config = tmp.resolve("config");
        Path old = config.resolve("ryscope");
        write(old.resolve("bookmarks.json"), "{\"folders\":[{\"items\":[\"minecraft:stone\",{\"category\":\"ryscope:mob_drops\",\"recipe\":\"ryscope:help/3\"}]}]}");
        write(old.resolve("config.json"), "{\"theme\":\"ryscope:glass\",\"maxColumns\":9}");
        write(old.resolve("stations-server-127.0.0.1.json"), "{}");
        Files.write(old.resolve("loot-cache.bin"), new byte[] {1, 2, 3, 0, (byte) 0xFF});
        write(old.resolve("backup/old.txt"), "x");

        assertEquals(5, LegacyMigration.migrateConfig(config));
        Path dir = config.resolve("rysindex");
        String bookmarks = Files.readString(dir.resolve("bookmarks.json"));
        assertTrue(bookmarks.contains("\"rysindex:mob_drops\""));
        assertTrue(bookmarks.contains("\"rysindex:help/3\""));
        assertTrue(bookmarks.contains("\"minecraft:stone\""));
        assertFalse(bookmarks.contains("ryscope"));
        assertArrayEquals(new byte[] {1, 2, 3, 0, (byte) 0xFF}, Files.readAllBytes(dir.resolve("loot-cache.bin")));
        assertTrue(Files.exists(dir.resolve("backup/old.txt")));
        assertTrue(Files.exists(dir.resolve("stations-server-127.0.0.1.json")));
        // старая папка цела
        assertTrue(Files.readString(old.resolve("bookmarks.json")).contains("ryscope:mob_drops"));

        // второй запуск ничего не делает, даже если в старой папке что-то появилось
        write(old.resolve("board.json"), "{}");
        assertEquals(0, LegacyMigration.migrateConfig(config));
        assertFalse(Files.exists(dir.resolve("board.json")));
    }

    @Test
    void doesNotOverwriteNewFiles() throws Exception {
        Path config = tmp.resolve("config");
        write(config.resolve("ryscope/config.json"), "{\"maxColumns\":9}");
        write(config.resolve("ryscope/board.json"), "{\"goals\":[]}");
        write(config.resolve("rysindex/config.json"), "{\"maxColumns\":12}");
        assertEquals(1, LegacyMigration.migrateConfig(config));
        assertEquals("{\"maxColumns\":12}", Files.readString(config.resolve("rysindex/config.json")));
        assertTrue(Files.exists(config.resolve("rysindex/board.json")));
    }

    @Test
    void nothingToMoveStillMarks() throws Exception {
        Path config = tmp.resolve("config");
        assertEquals(0, LegacyMigration.migrateConfig(config));
        assertTrue(Files.exists(config.resolve("rysindex").resolve(LegacyMigration.MARK_FILE)));
        // папка RYScope появилась позже: переноса уже не будет
        write(config.resolve("ryscope/config.json"), "{}");
        assertEquals(0, LegacyMigration.migrateConfig(config));
    }

    @Test
    void movesKeyBindingsOnce() throws Exception {
        Path config = tmp.resolve("config");
        Path options = write(tmp.resolve("options.txt"), String.join("\n",
            "version:3955",
            "key_key.ryscope.lens:key.keyboard.l",
            "key_key.ryscope.recipes:key.keyboard.r:SHIFT",
            "key_key.ryscope.bookmark:key.keyboard.unknown",
            "key_key.rysindex.bookmark:key.keyboard.b",
            "key_key.rysmap.open:key.keyboard.m",
            "soundCategory_master:0.0") + "\n");
        assertEquals(2, LegacyMigration.migrateKeys(options, config));
        List<String> lines = Files.readAllLines(options);
        assertTrue(lines.contains("key_key.rysindex.lens:key.keyboard.l"));
        assertTrue(lines.contains("key_key.rysindex.recipes:key.keyboard.r:SHIFT"));
        // уже назначенную новую клавишу не трогаем
        assertEquals(List.of("key_key.rysindex.bookmark:key.keyboard.b"),
            lines.stream().filter(l -> l.startsWith("key_key.rysindex.bookmark:")).toList());
        assertTrue(lines.contains("key_key.ryscope.lens:key.keyboard.l"));
        assertTrue(lines.contains("soundCategory_master:0.0"));
        Path backup = config.resolve("rysindex").resolve(LegacyMigration.OPTIONS_BACKUP);
        assertTrue(Files.exists(backup));
        assertFalse(Files.readString(backup).contains("rysindex.lens"));

        // второй раз не дописывает, даже если игрок снял новую клавишу
        Files.write(options, lines.stream().filter(l -> !l.startsWith("key_key.rysindex.lens")).toList());
        assertEquals(0, LegacyMigration.migrateKeys(options, config));
        assertFalse(Files.readString(options).contains("key_key.rysindex.lens"));
    }

    @Test
    void keysWithoutOptionsFile() throws Exception {
        assertEquals(0, LegacyMigration.migrateKeys(tmp.resolve("missing.txt"), tmp.resolve("config")));
    }

    @Test
    void configAndKeysShareOneMark() throws Exception {
        Path config = tmp.resolve("config");
        write(config.resolve("ryscope/config.json"), "{}");
        Path options = write(tmp.resolve("options.txt"), "key_key.ryscope.lens:key.keyboard.l\n");
        assertEquals(1, LegacyMigration.migrateKeys(options, config));
        assertEquals(1, LegacyMigration.migrateConfig(config));
        String mark = Files.readString(config.resolve("rysindex").resolve(LegacyMigration.MARK_FILE));
        assertTrue(mark.contains("keys="));
        assertTrue(mark.contains("config="));
    }

    @Test
    void worldFileCopiedIfAbsent() throws Exception {
        Path data = tmp.resolve("world/data");
        Path old = write(data.resolve("ryscope_stages.dat"), "old");
        Path now = data.resolve("rysindex_stages.dat");
        assertTrue(LegacyMigration.copyIfAbsent(old, now));
        assertEquals("old", Files.readString(now));
        Files.writeString(now, "new");
        assertFalse(LegacyMigration.copyIfAbsent(old, now));
        assertEquals("new", Files.readString(now));
        assertFalse(LegacyMigration.copyIfAbsent(data.resolve("missing.dat"), data.resolve("other.dat")));
        assertTrue(Files.exists(old));
    }
}
