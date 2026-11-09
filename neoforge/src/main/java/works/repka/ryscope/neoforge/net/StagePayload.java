package works.repka.ryscope.neoforge.net;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import works.repka.ryscope.RYScope;

/**
 * Стадии от сервера: описания (id и JSON) и то, какие из них открыты этому игроку.
 * Получив пакет, клиент перестаёт считать стадии сам и показывает то, что решил сервер.
 */
public record StagePayload(List<String> ids, List<String> jsons, Set<String> unlocked) implements CustomPacketPayload {
    public static final Type<StagePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "stages"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StagePayload> CODEC = StreamCodec.of(StagePayload::write, StagePayload::read);

    @Override
    public Type<StagePayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, StagePayload p) {
        buf.writeVarInt(p.ids.size());
        for (int i = 0; i < p.ids.size(); i++) {
            buf.writeUtf(p.ids.get(i));
            buf.writeUtf(p.jsons.get(i), 262144);
        }
        buf.writeVarInt(p.unlocked.size());
        for (String s : p.unlocked) buf.writeUtf(s);
    }

    private static StagePayload read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> ids = new ArrayList<>(n), jsons = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(buf.readUtf());
            jsons.add(buf.readUtf(262144));
        }
        int m = buf.readVarInt();
        Set<String> unlocked = new HashSet<>();
        for (int i = 0; i < m; i++) unlocked.add(buf.readUtf());
        return new StagePayload(ids, jsons, unlocked);
    }
}
