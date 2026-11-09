package works.repka.ryscope.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;
import works.repka.ryscope.RYScope;

/**
 * Loot-таблицы в компактном виде «предмет, количество, шанс».
 * Читается на стороне сервера (таблицы есть только там) из их же JSON-представления,
 * поэтому не зависит от внутренних полей игры. Результат можно передать клиенту как есть.
 */
public final class LootData {
    /** Пометки условий: показываются игроку рядом с шансом. */
    public static final String NOTE_PLAYER = "player";
    public static final String NOTE_SILK = "silk";
    public static final String NOTE_SHEARS = "shears";
    public static final String NOTE_FORTUNE = "fortune";
    public static final String NOTE_LOOTING = "looting";
    public static final String NOTE_ENCHANTED = "enchanted";
    public static final String NOTE_CONDITION = "condition";

    public record Drop(String item, boolean tag, int min, int max, float chance, String note) {}

    public record Table(String id, List<Drop> drops) {}

    private LootData() {}

    public static List<Table> extract(MinecraftServer server) {
        List<Table> out = new ArrayList<>();
        Map<String, JsonObject> raw = new LinkedHashMap<>();
        try {
            DynamicOps<JsonElement> ops = server.reloadableRegistries().get().createSerializationContext(JsonOps.INSTANCE);
            for (ResourceLocation id : server.reloadableRegistries().getKeys(Registries.LOOT_TABLE)) {
                try {
                    LootTable table = server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
                    LootTable.DIRECT_CODEC.encodeStart(ops, table).result().ifPresent(json -> {
                        if (json.isJsonObject()) raw.put(id.toString(), json.getAsJsonObject());
                    });
                } catch (Throwable t) {
                    RYScope.LOG.debug("Loot-таблица {} пропущена: {}", id, t.toString());
                }
            }
        } catch (Throwable t) {
            RYScope.LOG.warn("Не удалось прочитать loot-таблицы", t);
            return out;
        }
        for (Map.Entry<String, JsonObject> e : raw.entrySet()) {
            try {
                List<Drop> drops = merge(table(e.getValue(), raw, 0));
                if (!drops.isEmpty()) out.add(new Table(e.getKey(), drops));
            } catch (Throwable t) {
                RYScope.LOG.debug("Loot-таблица {} не разобрана: {}", e.getKey(), t.toString());
            }
        }
        return out;
    }

    private static List<Drop> table(JsonObject table, Map<String, JsonObject> all, int depth) {
        List<Drop> out = new ArrayList<>();
        if (!table.has("pools") || depth > 3) return out;
        for (JsonElement pe : table.getAsJsonArray("pools")) {
            JsonObject pool = pe.getAsJsonObject();
            float[] rolls = number(pool.get("rolls"), 1, 1);
            Cond poolCond = conditions(pool.get("conditions"));
            JsonArray entries = pool.has("entries") ? pool.getAsJsonArray("entries") : new JsonArray();
            float total = 0;
            for (JsonElement ee : entries) total += weight(ee.getAsJsonObject());
            if (total <= 0) continue;
            for (JsonElement ee : entries) {
                JsonObject entry = ee.getAsJsonObject();
                float p = weight(entry) / total * poolCond.chance;
                entry(entry, p, rolls, poolCond.note, functions(pool.get("functions")), all, depth, out);
            }
        }
        return out;
    }

    private static float weight(JsonObject entry) {
        return entry.has("weight") ? Math.max(0, entry.get("weight").getAsFloat()) : 1f;
    }

    private static void entry(JsonObject entry, float p, float[] rolls, String note, Fn poolFn,
                              Map<String, JsonObject> all, int depth, List<Drop> out) {
        String type = entry.has("type") ? entry.get("type").getAsString() : "";
        Cond cond = conditions(entry.get("conditions"));
        p *= cond.chance;
        String n = cond.note != null ? cond.note : note;
        if (p <= 0) return;
        switch (type) {
            case "minecraft:item", "minecraft:tag" -> {
                if (!entry.has("name")) return;
                Fn fn = functions(entry.get("functions")).or(poolFn);
                int rollsAvg = Math.max(1, Math.round((rolls[0] + rolls[1]) / 2f));
                float chance = (float) (1.0 - Math.pow(1.0 - Math.min(1f, p), rollsAvg));
                int min = fn.min, max = fn.max;
                if (p >= 0.999f) {
                    min *= Math.max(1, Math.round(rolls[0]));
                    max *= Math.max(1, Math.round(rolls[1]));
                }
                out.add(new Drop(entry.get("name").getAsString(), type.equals("minecraft:tag"), min, max, chance, fn.note != null ? fn.note : n));
            }
            case "minecraft:alternatives", "minecraft:group", "minecraft:sequence" -> {
                if (!entry.has("children")) return;
                for (JsonElement c : entry.getAsJsonArray("children")) {
                    entry(c.getAsJsonObject(), p, rolls, n, poolFn, all, depth, out);
                }
            }
            case "minecraft:loot_table" -> {
                JsonElement value = entry.get("value");
                JsonObject nested = null;
                if (value != null && value.isJsonObject()) nested = value.getAsJsonObject();
                else if (value != null && value.isJsonPrimitive()) nested = all.get(value.getAsString());
                if (nested == null) return;
                for (Drop d : table(nested, all, depth + 1)) {
                    out.add(new Drop(d.item, d.tag, d.min, d.max, d.chance * Math.min(1f, p), d.note != null ? d.note : n));
                }
            }
            default -> {
                // empty, dynamic и типы модов: вклада в список не дают
            }
        }
    }

    private record Cond(float chance, String note) {}

    private static Cond conditions(JsonElement element) {
        float chance = 1f;
        String note = null;
        if (element == null || !element.isJsonArray()) return new Cond(1f, null);
        for (JsonElement ce : element.getAsJsonArray()) {
            JsonObject c = ce.getAsJsonObject();
            String type = c.has("condition") ? c.get("condition").getAsString() : "";
            switch (type) {
                case "minecraft:random_chance" -> chance *= scalar(c.get("chance"), 1f);
                case "minecraft:random_chance_with_enchanted_bonus" -> {
                    chance *= scalar(c.get("unenchanted_chance"), 1f);
                    note = NOTE_LOOTING;
                }
                case "minecraft:table_bonus" -> {
                    if (c.has("chances") && c.get("chances").isJsonArray() && !c.getAsJsonArray("chances").isEmpty()) {
                        chance *= c.getAsJsonArray("chances").get(0).getAsFloat();
                    }
                    note = NOTE_FORTUNE;
                }
                case "minecraft:killed_by_player" -> note = NOTE_PLAYER;
                case "minecraft:match_tool" -> {
                    String text = c.toString();
                    if (text.contains("silk_touch")) note = NOTE_SILK;
                    else if (text.contains("shears")) note = NOTE_SHEARS;
                    else note = NOTE_CONDITION;
                }
                case "minecraft:any_of" -> {
                    String text = c.toString();
                    if (text.contains("silk_touch")) note = NOTE_SILK;
                    else if (text.contains("shears")) note = NOTE_SHEARS;
                }
                case "minecraft:entity_properties", "minecraft:damage_source_properties", "minecraft:location_check",
                     "minecraft:weather_check", "minecraft:time_check", "minecraft:entity_scores", "minecraft:value_check",
                     "minecraft:reference" -> {
                    // шанс зависит от обстоятельств (кто убил, где, когда): честно помечаем, а не выдаём за 100 %
                    if (note == null) note = NOTE_CONDITION;
                }
                default -> {
                    // survives_explosion, inverted, свойства блока на шанс в списке не влияют
                }
            }
        }
        return new Cond(Math.max(0f, Math.min(1f, chance)), note);
    }

    private record Fn(int min, int max, String note, boolean set) {
        Fn or(Fn other) {
            if (set) return note != null || other.note == null ? this : new Fn(min, max, other.note, true);
            return other.set ? other : new Fn(min, max, note != null ? note : other.note, false);
        }
    }

    private static Fn functions(JsonElement element) {
        int min = 1, max = 1;
        String note = null;
        boolean set = false;
        if (element != null && element.isJsonArray()) {
            for (JsonElement fe : element.getAsJsonArray()) {
                JsonObject f = fe.getAsJsonObject();
                String type = f.has("function") ? f.get("function").getAsString() : "";
                switch (type) {
                    case "minecraft:set_count" -> {
                        float[] range = number(f.get("count"), 1, 1);
                        boolean add = f.has("add") && f.get("add").getAsBoolean();
                        if (add) {
                            min += Math.round(range[0]);
                            max += Math.round(range[1]);
                        } else {
                            min = Math.round(range[0]);
                            max = Math.round(range[1]);
                        }
                        set = true;
                    }
                    case "minecraft:apply_bonus" -> note = NOTE_FORTUNE;
                    case "minecraft:enchanted_count_increase", "minecraft:looting_enchant" -> note = NOTE_LOOTING;
                    case "minecraft:enchant_randomly", "minecraft:enchant_with_levels", "minecraft:set_enchantments" -> note = NOTE_ENCHANTED;
                    default -> {
                        // остальные функции меняют свойства предмета, а не количество
                    }
                }
            }
        }
        return new Fn(Math.max(0, min), Math.max(Math.max(0, min), max), note, set);
    }

    private static float scalar(JsonElement e, float def) {
        if (e == null) return def;
        if (e.isJsonPrimitive()) return e.getAsFloat();
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("value")) return scalar(o.get("value"), def);
            if (o.has("base")) return scalar(o.get("base"), def);
        }
        return def;
    }

    /** Диапазон числового поставщика: константа, uniform или binomial. */
    private static float[] number(JsonElement e, float defMin, float defMax) {
        if (e == null) return new float[] {defMin, defMax};
        if (e.isJsonPrimitive()) {
            float v = e.getAsFloat();
            return new float[] {v, v};
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("min") || o.has("max")) {
                float min = number(o.get("min"), 0, 0)[0];
                float max = number(o.get("max"), min, min)[1];
                return new float[] {min, Math.max(min, max)};
            }
            if (o.has("value")) return number(o.get("value"), defMin, defMax);
            if (o.has("n")) return new float[] {0, number(o.get("n"), 1, 1)[1]};
        }
        return new float[] {defMin, defMax};
    }

    /** Один и тот же предмет из нескольких пулов показывается одной строкой. */
    private static List<Drop> merge(List<Drop> drops) {
        Map<String, Drop> map = new LinkedHashMap<>();
        for (Drop d : drops) {
            String key = d.item + "|" + d.tag + "|" + d.note;
            Drop old = map.get(key);
            if (old == null) {
                map.put(key, d);
            } else {
                float chance = 1f - (1f - old.chance) * (1f - d.chance);
                map.put(key, new Drop(d.item, d.tag, Math.min(old.min, d.min), old.max + d.max, chance, d.note));
            }
        }
        return new ArrayList<>(map.values());
    }
}
