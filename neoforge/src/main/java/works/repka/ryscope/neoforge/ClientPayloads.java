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

    static void onOres(works.repka.ryscope.neoforge.net.OrePayload payload) {
        works.repka.ryscope.runtime.OreSource.setSynced(payload.ores());
    }

    static void onOreBiomes(works.repka.ryscope.neoforge.net.OreBiomesPayload payload) {
        works.repka.ryscope.runtime.OreSource.setSyncedBiomes(payload.biomes());
    }
}
