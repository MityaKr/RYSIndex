package works.repka.rysindex.neoforge.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import works.repka.rysindex.RYSIndex;

/** Клиент просит источники добычи с этим хешем: на диске их нет. Сервер отвечает {@link LootPackedPayload}. */
public record LootNeedPayload(String hash) implements CustomPacketPayload {
    public static final Type<LootNeedPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "loot_need"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LootNeedPayload> CODEC = StreamCodec.of(
        (RegistryFriendlyByteBuf buf, LootNeedPayload p) -> buf.writeUtf(p.hash, 64), buf -> new LootNeedPayload(buf.readUtf(64)));

    @Override
    public Type<LootNeedPayload> type() {
        return TYPE;
    }
}
