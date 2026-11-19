package works.repka.ryscope.neoforge;

import works.repka.ryscope.neoforge.net.LootPayload;
import works.repka.ryscope.runtime.LootSource;

/** Обработчики пакетов на клиенте. Вынесены отдельно, чтобы выделенный сервер не загружал клиентские классы. */
final class ClientPayloads {
    private ClientPayloads() {}

    static void onStages(works.repka.ryscope.neoforge.net.StagePayload payload) {
        works.repka.ryscope.runtime.Stages.applyServer(payload.ids(), payload.jsons(), payload.unlocked());
    }

    static void onStageNotice(works.repka.ryscope.neoforge.net.StageNoticePayload payload) {
        works.repka.ryscope.runtime.Stages.showBlocked(payload.stageId());
    }

    static void onLoot(LootPayload payload) {
        LootSource.setSynced(payload.tables());
    }

    /** Хеш источников от сервера: тот же блок на диске берётся оттуда, иначе просим его. Диск и разбор в фоне. */
    static void onLootHash(works.repka.ryscope.neoforge.net.LootHashPayload payload) {
        String hash = payload.hash();
        net.minecraft.Util.backgroundExecutor().execute(() -> {
            byte[] data = works.repka.ryscope.runtime.LootCache.load(hash);
            java.util.List<works.repka.ryscope.content.LootData.Table> tables = data == null ? null : unpack(data);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.getConnection() == null) return;
                if (tables != null) {
                    LootSource.setSynced(tables);
                    works.repka.ryscope.RYScope.LOG.info("Источники от сервера взяты из кеша: {} таблиц", tables.size());
                } else {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(new works.repka.ryscope.neoforge.net.LootNeedPayload(hash));
                }
            });
        });
    }

    static void onLootPacked(works.repka.ryscope.neoforge.net.LootPackedPayload payload) {
        net.minecraft.Util.backgroundExecutor().execute(() -> {
            java.util.List<works.repka.ryscope.content.LootData.Table> tables = unpack(payload.data());
            if (tables == null) return;
            works.repka.ryscope.runtime.LootCache.save(payload.hash(), payload.data());
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.getConnection() == null) return;
                LootSource.setSynced(tables);
                works.repka.ryscope.RYScope.LOG.info("Источники от сервера получены: {} таблиц, {} КБ", tables.size(), payload.data().length / 1024);
            });
        });
    }

    @org.jetbrains.annotations.Nullable
    private static java.util.List<works.repka.ryscope.content.LootData.Table> unpack(byte[] data) {
        try {
            return works.repka.ryscope.content.LootWire.unpack(data);
        } catch (Throwable t) {
            works.repka.ryscope.RYScope.LOG.warn("Источники от сервера не разобраны: {}", t.toString());
            return null;
        }
    }

    static void onOres(works.repka.ryscope.neoforge.net.OrePayload payload) {
        works.repka.ryscope.runtime.OreSource.setSynced(payload.ores());
    }

    static void onOreBiomes(works.repka.ryscope.neoforge.net.OreBiomesPayload payload) {
        works.repka.ryscope.runtime.OreSource.setSyncedBiomes(payload.biomes());
    }
}
