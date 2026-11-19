package works.repka.ryscope.neoforge.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import works.repka.ryscope.RYScope;

/**
 * Источники добычи готовым сжатым блоком ({@link works.repka.ryscope.content.LootWire}): сервер сжимает их один раз
 * на набор loot-таблиц, а не кодирует заново для каждого игрока.
 */
public record LootPackedPayload(String hash, byte[] data) implements CustomPacketPayload {
    /** Больше блок не бывает: сжатые источники RepYS около сотни килобайт. */
    public static final int MAX_BYTES = 8 << 20;
    public static final Type<LootPackedPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "loot_packed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LootPackedPayload> CODEC = StreamCodec.of(
        (RegistryFriendlyByteBuf buf, LootPackedPayload p) -> {
            buf.writeUtf(p.hash, 64);
            buf.writeByteArray(p.data);
        },
        buf -> new LootPackedPayload(buf.readUtf(64), buf.readByteArray(MAX_BYTES)));

    @Override
    public Type<LootPackedPayload> type() {
        return TYPE;
    }
}
