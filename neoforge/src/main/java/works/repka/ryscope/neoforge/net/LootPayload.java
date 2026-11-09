package works.repka.ryscope.neoforge.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.content.LootData;

/**
 * Источники предметов для клиента в сетевой игре: loot-таблицы есть только на сервере.
 * Идентификаторы предметов передаются словарём, поэтому пакет небольшой даже в крупных сборках.
 */
public record LootPayload(List<LootData.Table> tables) implements CustomPacketPayload {
    public static final Type<LootPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "loot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LootPayload> CODEC = StreamCodec.of(LootPayload::write, LootPayload::read);

    private static final String[] NOTES = {
        null, LootData.NOTE_PLAYER, LootData.NOTE_SILK, LootData.NOTE_SHEARS, LootData.NOTE_FORTUNE,
        LootData.NOTE_LOOTING, LootData.NOTE_ENCHANTED, LootData.NOTE_CONDITION
    };

    @Override
    public Type<LootPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, LootPayload payload) {
        Map<String, Integer> dict = new HashMap<>();
        List<String> names = new ArrayList<>();
        for (LootData.Table t : payload.tables) {
            for (LootData.Drop d : t.drops()) {
                dict.computeIfAbsent(d.item(), k -> {
                    names.add(k);
                    return names.size() - 1;
                });
            }
        }
        buf.writeVarInt(names.size());
        for (String n : names) buf.writeUtf(n);
        buf.writeVarInt(payload.tables.size());
        for (LootData.Table t : payload.tables) {
            buf.writeUtf(t.id());
            buf.writeVarInt(t.drops().size());
            for (LootData.Drop d : t.drops()) {
                buf.writeVarInt(dict.get(d.item()));
                buf.writeBoolean(d.tag());
                buf.writeVarInt(d.min());
                buf.writeVarInt(d.max());
                buf.writeFloat(d.chance());
                int note = 0;
                for (int i = 1; i < NOTES.length; i++) if (NOTES[i].equals(d.note())) note = i;
                buf.writeByte(note);
            }
        }
    }

    private static LootPayload read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) names[i] = buf.readUtf();
        int tables = buf.readVarInt();
        List<LootData.Table> out = new ArrayList<>(tables);
        for (int t = 0; t < tables; t++) {
            String id = buf.readUtf();
            int count = buf.readVarInt();
            List<LootData.Drop> drops = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String item = names[buf.readVarInt()];
                boolean tag = buf.readBoolean();
                int min = buf.readVarInt();
                int max = buf.readVarInt();
                float chance = buf.readFloat();
                int note = buf.readByte();
                drops.add(new LootData.Drop(item, tag, min, max, chance, note > 0 && note < NOTES.length ? NOTES[note] : null));
            }
            out.add(new LootData.Table(id, drops));
        }
        return new LootPayload(out);
    }
}
