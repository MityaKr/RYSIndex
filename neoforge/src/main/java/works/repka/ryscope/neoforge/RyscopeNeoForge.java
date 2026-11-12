package works.repka.ryscope.neoforge;

import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.content.LootData;
import works.repka.ryscope.content.OreData;
import works.repka.ryscope.neoforge.net.LootPayload;
import works.repka.ryscope.neoforge.net.OrePayload;

/**
 * Общая точка входа. Серверная часть необязательна: без неё мод работает как чисто клиентский,
 * с ней клиент на выделенном сервере получает источники предметов (дроп, лут) и генерацию руд.
 */
@Mod(RYScope.MOD_ID)
public final class RyscopeNeoForge {
    private static Object cachedFor;
    private static LootPayload cached;
    /** Генерация руд: реестры генерации загружаются при запуске сервера и после /reload не меняются. */
    private static Object oresFor;
    private static OrePayload ores;

    public RyscopeNeoForge(IEventBus modBus) {
        RYScope.LOG.info("{} loading", RYScope.NAME);
        modBus.addListener(this::registerPayloads);
        works.repka.ryscope.neoforge.ae2.ProcessingSubstitution.COMPONENTS.register(modBus);
        // событие приходит и при входе игрока, и после /reload
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        ServerStages.register(NeoForge.EVENT_BUS);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        // optional: клиент без мода и сервер без мода спокойно соединяются
        var registrar = event.registrar("1").optional();
        registrar.playToClient(LootPayload.TYPE, LootPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onLoot(payload)));
        registrar.playToClient(OrePayload.TYPE, OrePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onOres(payload)));
        registrar.playToClient(works.repka.ryscope.neoforge.net.StageNoticePayload.TYPE, works.repka.ryscope.neoforge.net.StageNoticePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onStageNotice(payload)));
        registrar.playToClient(works.repka.ryscope.neoforge.net.StagePayload.TYPE, works.repka.ryscope.neoforge.net.StagePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onStages(payload)));
        // выдача предметов из режима читов: творческий режим или оператор, проверяет сервер
        registrar.playToServer(works.repka.ryscope.neoforge.net.GivePayload.TYPE, works.repka.ryscope.neoforge.net.GivePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) ServerGive.handle(player, payload);
            }));
        // варианты рецепта для замещения в шаблонах обработки AE2
        registrar.playToServer(works.repka.ryscope.neoforge.net.ProcessingAlternativesPayload.TYPE, works.repka.ryscope.neoforge.net.ProcessingAlternativesPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) works.repka.ryscope.neoforge.ae2.ProcessingSubstitution.remember(player, payload.groups());
            }));
    }

    private void onDatapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        List<ServerPlayer> players = event.getPlayer() != null ? List.of(event.getPlayer()) : event.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            // хозяину одиночной игры пакет не нужен: его клиент читает таблицы напрямую
            if (server.isSingleplayerOwner(player.getGameProfile())) continue;
            if (player.connection.hasChannel(LootPayload.TYPE)) PacketDistributor.sendToPlayer(player, payload(server));
            // клиент старой версии этого канала не знает и остаётся на кеше одиночной игры
            if (player.connection.hasChannel(OrePayload.TYPE)) PacketDistributor.sendToPlayer(player, ores(server));
        }
    }

    private static synchronized OrePayload ores(MinecraftServer server) {
        Object key = server.registryAccess();
        if (ores == null || oresFor != key) {
            long start = System.nanoTime();
            ores = new OrePayload(OreData.extract(server));
            oresFor = key;
            RYScope.LOG.info("Генерация руд подготовлена: {} блоков за {} мс", ores.ores().size(), (System.nanoTime() - start) / 1_000_000);
        }
        return ores;
    }

    private static synchronized LootPayload payload(MinecraftServer server) {
        Object key = server.reloadableRegistries();
        if (cached == null || cachedFor != key) {
            long start = System.nanoTime();
            cached = new LootPayload(LootData.extract(server));
            cachedFor = key;
            RYScope.LOG.info("Источники предметов подготовлены: {} таблиц за {} мс", cached.tables().size(), (System.nanoTime() - start) / 1_000_000);
        }
        return cached;
    }
}
