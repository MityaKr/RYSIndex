package works.repka.rysindex.neoforge.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import works.repka.rysindex.RYSIndex;

/** Сервер не дал выполнить рецепт: какая стадия его закрывает. Клиент показывает это поверх открытого окна. */
public record StageNoticePayload(String stageId) implements CustomPacketPayload {
    public static final Type<StageNoticePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "stage_notice"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StageNoticePayload> CODEC =
        StreamCodec.of((buf, p) -> buf.writeUtf(p.stageId), buf -> new StageNoticePayload(buf.readUtf()));

    @Override
    public Type<StageNoticePayload> type() {
        return TYPE;
    }
}
