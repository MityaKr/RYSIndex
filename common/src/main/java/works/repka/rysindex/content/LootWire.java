package works.repka.rysindex.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Источники добычи одним сжатым блоком с хешем (0.2.16). Сервер готовит его один раз на набор loot-таблиц, клиент
 * хранит последний на диске и при том же хеше ничего не просит. Без классов игры: проверяется юнит-тестом.
 * <p>
 * Формат до сжатия: версия, словарь предметов, таблицы (id, выпадения: номер предмета, тег, min, max, шанс, пометка).
 */
public final class LootWire {
    private LootWire() {}

    public static final int VERSION = 1;
    /** Защита от испорченных данных: столько не бывает даже в крупных сборках (в RepYS 15 112 таблиц). */
    private static final int MAX_TABLES = 200_000, MAX_DROPS = 4096, MAX_NAMES = 500_000;
    /** Распакованный блок больше этого не читается. */
    private static final int MAX_RAW = 64 << 20;

    private static final String[] NOTES = {
        null, LootData.NOTE_PLAYER, LootData.NOTE_SILK, LootData.NOTE_SHEARS, LootData.NOTE_FORTUNE,
        LootData.NOTE_LOOTING, LootData.NOTE_ENCHANTED, LootData.NOTE_CONDITION
    };

    /** Сжатый блок и хеш его содержимого. */
    public record Packed(String hash, byte[] data) {}

    public static Packed pack(List<LootData.Table> tables) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 20);
            DataOutputStream out = new DataOutputStream(raw);
            Map<String, Integer> dict = new HashMap<>();
            List<String> names = new ArrayList<>();
            for (LootData.Table t : tables) {
                for (LootData.Drop d : t.drops()) {
                    dict.computeIfAbsent(d.item(), k -> {
                        names.add(k);
                        return names.size() - 1;
                    });
                }
            }
            out.writeInt(VERSION);
            out.writeInt(names.size());
            for (String n : names) out.writeUTF(n);
            out.writeInt(tables.size());
            for (LootData.Table t : tables) {
                out.writeUTF(t.id());
                out.writeInt(t.drops().size());
                for (LootData.Drop d : t.drops()) {
                    out.writeInt(dict.get(d.item()));
                    out.writeBoolean(d.tag());
                    out.writeInt(d.min());
                    out.writeInt(d.max());
                    out.writeFloat(d.chance());
                    out.writeByte(noteIndex(d.note()));
                }
            }
            out.flush();
            byte[] bytes = raw.toByteArray();
            ByteArrayOutputStream zipped = new ByteArrayOutputStream(bytes.length / 4 + 64);
            try (DeflaterOutputStream z = new DeflaterOutputStream(zipped)) {
                z.write(bytes);
            }
            return new Packed(hash(bytes), zipped.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Обратное к {@link #pack}. Бросает исключение на испорченных данных или чужой версии. */
    public static List<LootData.Table> unpack(byte[] data) throws IOException {
        byte[] bytes;
        try (InflaterInputStream z = new InflaterInputStream(new ByteArrayInputStream(data))) {
            bytes = z.readNBytes(MAX_RAW + 1);
        }
        if (bytes.length > MAX_RAW) throw new IOException("loot: block too large");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int version = in.readInt();
        if (version != VERSION) throw new IOException("loot: version " + version);
        int n = in.readInt();
        if (n < 0 || n > MAX_NAMES) throw new IOException("loot: items " + n);
        String[] names = new String[n];
        for (int i = 0; i < n; i++) names[i] = in.readUTF();
        int count = in.readInt();
        if (count < 0 || count > MAX_TABLES) throw new IOException("loot: tables " + count);
        List<LootData.Table> out = new ArrayList<>(count);
        for (int t = 0; t < count; t++) {
            String id = in.readUTF();
            int drops = in.readInt();
            if (drops < 0 || drops > MAX_DROPS) throw new IOException("loot: drops " + drops + " in " + id);
            List<LootData.Drop> list = new ArrayList<>(drops);
            for (int i = 0; i < drops; i++) {
                int item = in.readInt();
                if (item < 0 || item >= n) throw new IOException("loot: item " + item);
                boolean tag = in.readBoolean();
                int min = in.readInt();
                int max = in.readInt();
                float chance = in.readFloat();
                int note = in.readByte();
                list.add(new LootData.Drop(names[item], tag, min, max, chance, note > 0 && note < NOTES.length ? NOTES[note] : null));
            }
            out.add(new LootData.Table(id, List.copyOf(list)));
        }
        return out;
    }

    /** Хеш содержимого: первые 16 байт SHA-256 в шестнадцатеричном виде. */
    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int noteIndex(String note) {
        if (note == null) return 0;
        for (int i = 1; i < NOTES.length; i++) if (NOTES[i].equals(note)) return i;
        return 0;
    }
}
