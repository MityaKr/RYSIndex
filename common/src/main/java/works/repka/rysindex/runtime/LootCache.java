package works.repka.rysindex.runtime;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.jetbrains.annotations.Nullable;
import works.repka.rysindex.RYSIndex;

/**
 * Последние источники добычи от сервера на диске ({@code config/rysindex/loot-cache.bin}): хеш и сжатый блок
 * {@link works.repka.rysindex.content.LootWire}. При том же хеше клиент берёт их отсюда и сервер не шлёт их заново.
 */
public final class LootCache {
    private static final String FILE = "loot-cache.bin";

    private LootCache() {}

    private static Path path() {
        return Config.dir().resolve(FILE);
    }

    /** Блок с этим хешем или null. */
    @Nullable
    public static synchronized byte[] load(String hash) {
        Path p = path();
        if (!Files.exists(p)) return null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(p))) {
            String stored = in.readUTF();
            if (!stored.equals(hash)) return null;
            int len = in.readInt();
            if (len < 0 || len > (64 << 20)) return null;
            return in.readNBytes(len);
        } catch (IOException e) {
            RYSIndex.LOG.debug("Кеш источников не прочитан: {}", e.toString());
            return null;
        }
    }

    public static synchronized void save(String hash, byte[] data) {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(FILE + ".tmp");
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeUTF(hash);
                out.writeInt(data.length);
                out.write(data);
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            RYSIndex.LOG.warn("Кеш источников не записан: {}", e.toString());
        }
    }
}
