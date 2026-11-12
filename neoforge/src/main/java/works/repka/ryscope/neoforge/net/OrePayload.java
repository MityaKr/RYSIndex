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
import works.repka.ryscope.content.OreData;

/**
 * Генерация руд для клиента в сетевой игре: placed_feature и configured_feature клиенту не синхронизируются,
 * а биомы приходят без настроек генерации. Сервер с RYScope присылает уже разобранные жилы.
 */
public record OrePayload(Map<String, List<OreData.Range>> ores) implements CustomPacketPayload {
    public static final Type<OrePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "ores"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OrePayload> CODEC = StreamCodec.of(OrePayload::write, OrePayload::read);
    /** Защита от испорченного пакета: столько жил у одного блока не бывает (при разборе их не больше четырёх). */
    private static final int MAX_RANGES = 16;

    @Override
    public Type<OrePayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, OrePayload payload) {
        buf.writeVarInt(payload.ores.size());
        for (Map.Entry<String, List<OreData.Range>> e : payload.ores.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue().size());
            for (OreData.Range r : e.getValue()) {
                buf.writeInt(r.min());
                buf.writeInt(r.max());
                buf.writeFloat(r.perChunk());
                buf.writeVarInt(r.size());
                buf.writeBoolean(r.triangle());
                writeAnchor(buf, r.lo());
                writeAnchor(buf, r.hi());
            }
        }
    }

    private static OrePayload read(FriendlyByteBuf buf) {
        int blocks = buf.readVarInt();
        Map<String, List<OreData.Range>> out = new LinkedHashMap<>();
        for (int b = 0; b < blocks; b++) {
            String id = buf.readUtf();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_RANGES) throw new IllegalArgumentException("ores: " + count + " ranges for " + id);
            List<OreData.Range> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int min = buf.readInt();
                int max = buf.readInt();
                float perChunk = buf.readFloat();
                int size = buf.readVarInt();
                boolean triangle = buf.readBoolean();
                OreData.Anchor lo = readAnchor(buf);
                OreData.Anchor hi = readAnchor(buf);
                list.add(new OreData.Range(min, max, perChunk, size, triangle, lo, hi));
            }
            out.put(id, list);
        }
        return new OrePayload(out);
    }

    private static void writeAnchor(FriendlyByteBuf buf, OreData.Anchor a) {
        buf.writeByte(a.kind());
        buf.writeInt(a.value());
    }

    private static OreData.Anchor readAnchor(FriendlyByteBuf buf) {
        return new OreData.Anchor(buf.readByte(), buf.readInt());
    }
}
