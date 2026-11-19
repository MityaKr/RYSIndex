package works.repka.rysindex.runtime;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import works.repka.rysindex.content.LootData;

/**
 * Откуда клиент берёт loot-таблицы: в одиночной игре напрямую у встроенного сервера,
 * в сетевой игре из данных, присланных сервером (если мод стоит и там).
 */
public final class LootSource {
    private static volatile List<LootData.Table> synced = List.of();
    private static Object cachedFor;
    private static List<LootData.Table> cached = List.of();

    private LootSource() {}

    /** Данные от сервера в сетевой игре. */
    public static void setSynced(List<LootData.Table> tables) {
        synced = tables;
        Indexer.request("получены источники от сервера");
    }

    public static void clear() {
        synced = List.of();
        cachedFor = null;
        cached = List.of();
    }

    /** Вызывается из потока индексации. */
    static synchronized List<LootData.Table> tables() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return synced;
        // объект реестров меняется после /reload: по нему и понимаем, что пора перечитать
        Object key = server.reloadableRegistries();
        if (key != cachedFor) {
            cached = LootData.extract(server);
            cachedFor = key;
        }
        return cached;
    }
}
