package works.repka.rysindex.neoforge;

import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.content.LootData;
import works.repka.rysindex.content.OreData;
import works.repka.rysindex.neoforge.net.LootPayload;
import works.repka.rysindex.neoforge.net.OrePayload;

/**
 * Общая точка входа. Серверная часть необязательна: без неё мод работает как чисто клиентский,
 * с ней клиент на выделенном сервере получает источники предметов (дроп, лут) и генерацию руд.
 */
@Mod(RYSIndex.MOD_ID)
public final class RysIndexNeoForge {
    /** Источники добычи: считаются в фоне на запуске сервера и после /reload (0.2.16), здесь готовые или в работе. */
    private static Object lootFor;
    private static java.util.concurrent.CompletableFuture<LootReady> loot;

    /** Готовые источники: таблицы для старого канала и сжатый блок с хешем для нового. */
    private record LootReady(List<LootData.Table> tables, works.repka.rysindex.content.LootWire.Packed packed) {}
    /** Генерация руд: реестры генерации загружаются при запуске сервера и после /reload не меняются. */
    private static Object oresFor;
    private static OrePayload ores;
    private static works.repka.rysindex.neoforge.net.OreBiomesPayload oreBiomes;

    public RysIndexNeoForge(IEventBus modBus) {
        RYSIndex.LOG.info("{} loading", RYSIndex.NAME);
        migrateLegacy(false);
        modBus.addListener(this::registerPayloads);
        works.repka.rysindex.neoforge.ae2.ProcessingSubstitution.COMPONENTS.register(modBus);
        // событие приходит и при входе игрока, и после /reload
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent e) -> prepareLoot(e.getServer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> {
            synchronized (RysIndexNeoForge.class) {
                loot = null;
                lootFor = null;
            }
        });
        ServerStages.register(NeoForge.EVENT_BUS);
    }

    /**
     * Данные игрока из RYScope (мод до 0.3.0 назывался так): папка config/ryscope и, на клиенте, клавиши в options.txt.
     * Вызывается из обеих точек входа до первого чтения настроек; сам перенос делается один раз.
     */
    static void migrateLegacy(boolean client) {
        java.nio.file.Path config = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        try {
            int files = works.repka.rysindex.runtime.LegacyMigration.migrateConfig(config);
            if (files > 0) RYSIndex.LOG.info("Перенесено из config/ryscope в config/rysindex: {} файлов", files);
        } catch (Exception e) {
            RYSIndex.LOG.warn("Перенос config/ryscope не удался: {}", e.toString());
        }
        if (!client) return;
        try {
            java.nio.file.Path options = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("options.txt");
            int keys = works.repka.rysindex.runtime.LegacyMigration.migrateKeys(options, config);
            if (keys > 0) RYSIndex.LOG.info("Перенесено клавиш key.ryscope.* в key.rysindex.*: {}", keys);
        } catch (Exception e) {
            RYSIndex.LOG.warn("Перенос клавиш из options.txt не удался: {}", e.toString());
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        // optional: клиент без мода и сервер без мода спокойно соединяются
        var registrar = event.registrar("1").optional();
        registrar.playToClient(LootPayload.TYPE, LootPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onLoot(payload)));
        // с 0.2.16: хеш, просьба и сжатый блок; клиент старой версии этих каналов не знает и получает старый пакет
        registrar.playToClient(works.repka.rysindex.neoforge.net.LootHashPayload.TYPE, works.repka.rysindex.neoforge.net.LootHashPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onLootHash(payload)));
        registrar.playToClient(works.repka.rysindex.neoforge.net.LootPackedPayload.TYPE, works.repka.rysindex.neoforge.net.LootPackedPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onLootPacked(payload)));
        registrar.playToServer(works.repka.rysindex.neoforge.net.LootNeedPayload.TYPE, works.repka.rysindex.neoforge.net.LootNeedPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) sendPacked(player, payload.hash());
            }));
        registrar.playToClient(OrePayload.TYPE, OrePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onOres(payload)));
        registrar.playToClient(works.repka.rysindex.neoforge.net.OreBiomesPayload.TYPE, works.repka.rysindex.neoforge.net.OreBiomesPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onOreBiomes(payload)));
        registrar.playToClient(works.repka.rysindex.neoforge.net.StageNoticePayload.TYPE, works.repka.rysindex.neoforge.net.StageNoticePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onStageNotice(payload)));
        registrar.playToClient(works.repka.rysindex.neoforge.net.StagePayload.TYPE, works.repka.rysindex.neoforge.net.StagePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientPayloads.onStages(payload)));
        // выдача предметов из режима читов: творческий режим или оператор, проверяет сервер
        registrar.playToServer(works.repka.rysindex.neoforge.net.GivePayload.TYPE, works.repka.rysindex.neoforge.net.GivePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) ServerGive.handle(player, payload);
            }));
        // варианты рецепта для замещения в шаблонах обработки AE2
        registrar.playToServer(works.repka.rysindex.neoforge.net.ProcessingAlternativesPayload.TYPE, works.repka.rysindex.neoforge.net.ProcessingAlternativesPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) works.repka.rysindex.neoforge.ae2.ProcessingSubstitution.remember(player, payload.groups());
            }));
    }

    private void onDatapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        List<ServerPlayer> players = event.getPlayer() != null ? List.of(event.getPlayer()) : event.getPlayerList().getPlayers();
        // после /reload снимок реестров новый: считаем заново в фоне; при входе берём готовое
        java.util.concurrent.CompletableFuture<LootReady> ready = prepareLoot(server);
        for (ServerPlayer player : players) {
            // хозяину одиночной игры пакет не нужен: его клиент читает таблицы напрямую
            if (server.isSingleplayerOwner(player.getGameProfile())) continue;
            boolean packed = player.connection.hasChannel(works.repka.rysindex.neoforge.net.LootHashPayload.TYPE);
            if (packed || player.connection.hasChannel(LootPayload.TYPE)) {
                // отправка в главном потоке, как только таблицы готовы; игрок мог уже выйти
                ready.thenAcceptAsync(r -> {
                    if (r == null || player.hasDisconnected()) return;
                    if (packed) PacketDistributor.sendToPlayer(player, new works.repka.rysindex.neoforge.net.LootHashPayload(r.packed().hash()));
                    else PacketDistributor.sendToPlayer(player, new LootPayload(r.tables()));
                }, server);
            }
            // клиент старой версии этого канала не знает и остаётся на кеше одиночной игры
            if (player.connection.hasChannel(OrePayload.TYPE)) PacketDistributor.sendToPlayer(player, ores(server));
            // биомы руд отдельным каналом с 0.2.15: клиент 0.2.14 и старше его не знает, и формат жил не меняется
            if (player.connection.hasChannel(works.repka.rysindex.neoforge.net.OreBiomesPayload.TYPE)) {
                ores(server);
                PacketDistributor.sendToPlayer(player, oreBiomes);
            }
        }
    }

    private static synchronized OrePayload ores(MinecraftServer server) {
        Object key = server.registryAccess();
        if (ores == null || oresFor != key) {
            long start = System.nanoTime();
            java.util.Map<String, List<net.minecraft.world.level.levelgen.placement.PlacedFeature>> features = new java.util.HashMap<>();
            ores = new OrePayload(OreData.extract(server, features));
            oreBiomes = new works.repka.rysindex.neoforge.net.OreBiomesPayload(works.repka.rysindex.content.OreBiomeData.extract(server, features));
            oresFor = key;
            RYSIndex.LOG.info("Генерация руд подготовлена: {} блоков, биомы у {}, за {} мс", ores.ores().size(), oreBiomes.biomes().size(),
                (System.nanoTime() - start) / 1_000_000);
        }
        return ores;
    }

    /**
     * Источники добычи для этого снимка реестров: готовые или в работе. Разбор 15 тысяч таблиц шёл в главном потоке
     * при первом входе и после /reload (380–392 мс по журналу 18.11), теперь в фоновом потоке игры.
     */
    private static synchronized java.util.concurrent.CompletableFuture<LootReady> prepareLoot(MinecraftServer server) {
        var registries = server.reloadableRegistries();
        if (loot != null && lootFor == registries) return loot;
        lootFor = registries;
        loot = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            List<LootData.Table> tables = LootData.extract(registries);
            works.repka.rysindex.content.LootWire.Packed packed = works.repka.rysindex.content.LootWire.pack(tables);
            RYSIndex.LOG.info("Источники предметов подготовлены в фоне: {} таблиц, {} КБ сжатыми, за {} мс", tables.size(),
                packed.data().length / 1024, (System.nanoTime() - start) / 1_000_000);
            return new LootReady(List.copyOf(tables), packed);
        }, net.minecraft.Util.backgroundExecutor()).exceptionally(t -> {
            RYSIndex.LOG.warn("Источники предметов не подготовлены", t);
            return null;
        });
        return loot;
    }

    /** Клиент просит блок с этим хешем: отдаём, если он совпадает с готовым (после /reload придёт новый хеш). */
    private static void sendPacked(ServerPlayer player, String hash) {
        java.util.concurrent.CompletableFuture<LootReady> current;
        synchronized (RysIndexNeoForge.class) {
            current = loot;
        }
        if (current == null) return;
        current.thenAcceptAsync(r -> {
            if (r == null || player.hasDisconnected() || !r.packed().hash().equals(hash)) return;
            PacketDistributor.sendToPlayer(player, new works.repka.rysindex.neoforge.net.LootPackedPayload(r.packed().hash(), r.packed().data()));
        }, player.server);
    }
}
