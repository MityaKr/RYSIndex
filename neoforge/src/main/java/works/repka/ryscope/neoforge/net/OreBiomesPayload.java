package works.repka.ryscope.neoforge.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.content.OreBiomes;

/** Биомы руд для «где искать» на карте (0.2.15). Отдельный канал: пакет жил {@link OrePayload} не меняется. */
public record OreBiomesPayload(Map<String, OreBiomes.Where> biomes) implements CustomPacketPayload {
    public static final Type<OreBiomesPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "ore_biomes"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OreBiomesPayload> CODEC = StreamCodec.of(OreBiomesPayload::write, OreBiomesPayload::read);

    @Override
    public Type<OreBiomesPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, OreBiomesPayload payload) {
        buf.writeVarInt(payload.biomes.size());
        for (Map.Entry<String, OreBiomes.Where> e : payload.biomes.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue().dim());
            buf.writeVarInt(e.getValue().biomes().size());
            for (String b : e.getValue().biomes()) buf.writeUtf(b);
        }
    }

    private static OreBiomesPayload read(FriendlyByteBuf buf) {
        int blocks = buf.readVarInt();
        Map<String, OreBiomes.Where> out = new LinkedHashMap<>();
        for (int i = 0; i < blocks; i++) {
            String id = buf.readUtf();
            String dim = buf.readUtf();
            int count = buf.readVarInt();
            if (count < 0 || count > OreBiomes.MAX_BIOMES) throw new IllegalArgumentException("ore_biomes: " + count + " biomes for " + id);
            List<String> list = new ArrayList<>(count);
            for (int b = 0; b < count; b++) list.add(buf.readUtf());
            out.put(id, new OreBiomes.Where(dim, List.copyOf(list)));
        }
        return new OreBiomesPayload(out);
    }
}
