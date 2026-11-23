package works.repka.rysindex.neoforge;

import works.repka.rysindex.neoforge.net.LootPayload;
import works.repka.rysindex.runtime.LootSource;

/** Обработчики пакетов на клиенте. Вынесены отдельно, чтобы выделенный сервер не загружал клиентские классы. */
final class ClientPayloads {
    private ClientPayloads() {}

    static void onStages(works.repka.rysindex.neoforge.net.StagePayload payload) {
        works.repka.rysindex.runtime.Stages.applyServer(payload.ids(), payload.jsons(), payload.unlocked());
    }

    static void onStageNotice(works.repka.rysindex.neoforge.net.StageNoticePayload payload) {
        works.repka.rysindex.runtime.Stages.showBlocked(payload.stageId());
    }

    static void onLoot(LootPayload payload) {
        LootSource.setSynced(payload.tables());
    }

    /** Хеш источников от сервера: тот же блок на диске берётся оттуда, иначе просим его. Диск и разбор в фоне. */
    static void onLootHash(works.repka.rysindex.neoforge.net.LootHashPayload payload) {
        String hash = payload.hash();
        net.minecraft.Util.backgroundExecutor().execute(() -> {
            byte[] data = works.repka.rysindex.runtime.LootCache.load(hash);
            java.util.List<works.repka.rysindex.content.LootData.Table> tables = data == null ? null : unpack(data);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.getConnection() == null) return;
                if (tables != null) {
                    LootSource.setSynced(tables);
                    works.repka.rysindex.RYSIndex.LOG.info("Server sources taken from the cache: {} tables", tables.size());
                } else {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(new works.repka.rysindex.neoforge.net.LootNeedPayload(hash));
                }
            });
        });
    }

    static void onLootPacked(works.repka.rysindex.neoforge.net.LootPackedPayload payload) {
        net.minecraft.Util.backgroundExecutor().execute(() -> {
            java.util.List<works.repka.rysindex.content.LootData.Table> tables = unpack(payload.data());
            if (tables == null) return;
            works.repka.rysindex.runtime.LootCache.save(payload.hash(), payload.data());
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.getConnection() == null) return;
                LootSource.setSynced(tables);
                works.repka.rysindex.RYSIndex.LOG.info("Server sources received: {} tables, {} KB", tables.size(), payload.data().length / 1024);
            });
        });
    }

    @org.jetbrains.annotations.Nullable
    private static java.util.List<works.repka.rysindex.content.LootData.Table> unpack(byte[] data) {
        try {
            return works.repka.rysindex.content.LootWire.unpack(data);
        } catch (Throwable t) {
            works.repka.rysindex.RYSIndex.LOG.warn("Server sources could not be parsed: {}", t.toString());
            return null;
        }
    }

    static void onOres(works.repka.rysindex.neoforge.net.OrePayload payload) {
        works.repka.rysindex.runtime.OreSource.setSynced(payload.ores());
    }

    static void onOreBiomes(works.repka.rysindex.neoforge.net.OreBiomesPayload payload) {
        works.repka.rysindex.runtime.OreSource.setSyncedBiomes(payload.biomes());
    }
}
