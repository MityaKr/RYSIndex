package works.repka.rysindex.neoforge.net;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.neoforge.ae2.ProcessingSubstitution;

/** С клиента на сервер: группы взаимозаменяемых предметов рецепта, только что разложенного в терминал шаблонов AE2. */
public record ProcessingAlternativesPayload(List<List<Item>> groups) implements CustomPacketPayload {
    public static final Type<ProcessingAlternativesPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "processing_alternatives"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ProcessingAlternativesPayload> CODEC =
        ProcessingSubstitution.STREAM_CODEC.map(ProcessingAlternativesPayload::new, ProcessingAlternativesPayload::groups);

    @Override
    public Type<ProcessingAlternativesPayload> type() {
        return TYPE;
    }
}
