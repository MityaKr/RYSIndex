package works.repka.rysindex.neoforge.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import works.repka.rysindex.RYSIndex;

/** С клиента на сервер: выдать предмет в режиме читов (в инвентарь или на курсор) или убрать предмет с курсора. */
public record GivePayload(ItemStack stack, int mode) implements CustomPacketPayload {
    /** Режимы по порядку {@code Give.Mode} на клиенте. */
    public static final int INVENTORY = 0, CURSOR = 1, DELETE = 2;
    public static final Type<GivePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "give"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GivePayload> CODEC = StreamCodec.composite(
        ItemStack.OPTIONAL_STREAM_CODEC, GivePayload::stack,
        ByteBufCodecs.VAR_INT, GivePayload::mode,
        GivePayload::new);

    @Override
    public Type<GivePayload> type() {
        return TYPE;
    }
}
