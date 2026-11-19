package works.repka.rysindex.neoforge.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import works.repka.rysindex.RYSIndex;

/**
 * Сервер сообщает хеш готовых источников добычи (0.2.16). Клиент с тем же блоком на диске больше ничего не просит,
 * иначе отвечает {@link LootNeedPayload}.
 */
public record LootHashPayload(String hash) implements CustomPacketPayload {
    public static final Type<LootHashPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "loot_hash"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LootHashPayload> CODEC = StreamCodec.of(
        (RegistryFriendlyByteBuf buf, LootHashPayload p) -> buf.writeUtf(p.hash, 64), buf -> new LootHashPayload(buf.readUtf(64)));

    @Override
    public Type<LootHashPayload> type() {
        return TYPE;
    }
}
