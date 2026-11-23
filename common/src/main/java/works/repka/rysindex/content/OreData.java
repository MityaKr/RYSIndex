package works.repka.rysindex.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import works.repka.rysindex.RYSIndex;

/**
 * Генерация руд в компактном виде «блок, высоты, частота».
 * Читается на стороне сервера: placed_feature и configured_feature клиенту не синхронизируются
 * (в {@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES} их нет, а биомы приходят без настроек генерации).
 * Результат не ссылается на клиентские классы, его можно передать клиенту пакетом или сохранить в файл.
 * Высоты «от дна мира» пересчитаны для обычного мира (дно на -64).
 */
public final class OreData {
    public static final int WORLD_MIN = -64, WORLD_MAX = 320;
    /** Версия формата файла кеша: при смене старый файл не читается. */
    public static final int FORMAT = 1;

    /** Граница высоты, как её задал автор: число, «от дна» или «от верха». Подпись строится на клиенте, на его языке. */
    public record Anchor(int kind, int value) {
        public static final int ABSOLUTE = 0, ABOVE_BOTTOM = 1, BELOW_TOP = 2;

        public String label() {
            return switch (kind) {
                case ABOVE_BOTTOM -> Component.translatable("rysindex.ore.above_bottom", value).getString();
                case BELOW_TOP -> Component.translatable("rysindex.ore.below_top", value).getString();
                default -> Integer.toString(value);
            };
        }
    }

    /** min и max уже обрезаны высотой мира, lo и hi это исходные границы для подписи. */
    public record Range(int min, int max, float perChunk, int size, boolean triangle, Anchor lo, Anchor hi) {}

    private OreData() {}

    /** Жилы по блокам; ключ это идентификатор блока. */
    public static Map<String, List<Range>> extract(MinecraftServer server) {
        return extract(server, null);
    }

    /** То же; в {@code features} (если не null) складываются все жилы каждого блока: по ним {@link OreBiomes} ищет биомы. */
    public static Map<String, List<Range>> extract(MinecraftServer server, @org.jetbrains.annotations.Nullable Map<String, List<PlacedFeature>> features) {
        Map<String, List<Range>> out = new LinkedHashMap<>();
        try {
            DynamicOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            Registry<PlacedFeature> placed = server.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
            for (PlacedFeature pf : placed) {
                try {
                    JsonElement cfg = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, pf.feature().value()).result().orElse(null);
                    if (cfg == null || !cfg.isJsonObject()) continue;
                    JsonObject feature = cfg.getAsJsonObject();
                    String type = feature.has("type") ? feature.get("type").getAsString() : "";
                    if (!type.equals("minecraft:ore") && !type.equals("minecraft:scattered_ore")) continue;
                    JsonObject config = feature.getAsJsonObject("config");
                    int size = config.has("size") ? config.get("size").getAsInt() : 0;
                    JsonElement placedJson = PlacedFeature.DIRECT_CODEC.encodeStart(ops, pf).result().orElse(null);
                    if (placedJson == null) continue;
                    float perChunk = 1f;
                    int min = WORLD_MIN, max = WORLD_MAX;
                    boolean triangle = false, hasHeight = false;
                    Anchor lo = null, hi = null;
                    for (JsonElement me : placedJson.getAsJsonObject().getAsJsonArray("placement")) {
                        JsonObject m = me.getAsJsonObject();
                        String mt = m.has("type") ? m.get("type").getAsString() : "";
                        if (mt.equals("minecraft:count") && m.has("count")) perChunk *= average(m.get("count"));
                        else if (mt.equals("minecraft:rarity_filter") && m.has("chance")) perChunk /= Math.max(1, m.get("chance").getAsInt());
                        else if (mt.equals("minecraft:height_range") && m.has("height") && m.get("height").isJsonObject()) {
                            JsonObject h = m.getAsJsonObject("height");
                            triangle = h.has("type") && h.get("type").getAsString().contains("trapezoid");
                            if (h.has("min_inclusive")) {
                                lo = anchor(h.get("min_inclusive"));
                                if (lo != null) min = height(lo);
                            }
                            if (h.has("max_inclusive")) {
                                hi = anchor(h.get("max_inclusive"));
                                if (hi != null) max = height(hi);
                            }
                            hasHeight = true;
                        }
                    }
                    if (!hasHeight || !config.has("targets")) continue;
                    int clampedLo = Math.max(WORLD_MIN, min), clampedHi = Math.min(WORLD_MAX, max);
                    Range range = new Range(clampedLo, clampedHi, perChunk, size, triangle,
                        lo != null ? lo : new Anchor(Anchor.ABSOLUTE, clampedLo), hi != null ? hi : new Anchor(Anchor.ABSOLUTE, clampedHi));
                    for (JsonElement te : config.getAsJsonArray("targets")) {
                        JsonObject state = te.getAsJsonObject().getAsJsonObject("state");
                        if (state == null || !state.has("Name")) continue;
                        ResourceLocation id = ResourceLocation.parse(state.get("Name").getAsString());
                        Block block = BuiltInRegistries.BLOCK.get(id);
                        if (block == Blocks.AIR) continue;
                        String key = BuiltInRegistries.BLOCK.getKey(block).toString();
                        List<Range> list = out.computeIfAbsent(key, k -> new ArrayList<>());
                        if (!list.contains(range) && list.size() < 4) list.add(range);
                        if (features != null) features.computeIfAbsent(key, k -> new ArrayList<>()).add(pf);
                    }
                } catch (Throwable ignored) {
                    // особая конфигурация мода: пропускаем эту жилу
                }
            }
        } catch (Throwable t) {
            RYSIndex.LOG.warn("Ore generation data could not be read: {}", t.toString());
        }
        return out;
    }

    private static float average(JsonElement e) {
        if (e.isJsonPrimitive()) return e.getAsFloat();
        JsonObject o = e.getAsJsonObject();
        if (o.has("min_inclusive") && o.has("max_inclusive")) return (o.get("min_inclusive").getAsFloat() + o.get("max_inclusive").getAsFloat()) / 2f;
        if (o.has("value")) return average(o.get("value"));
        return 1f;
    }

    private static Anchor anchor(JsonElement e) {
        if (!e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        if (o.has("absolute")) return new Anchor(Anchor.ABSOLUTE, o.get("absolute").getAsInt());
        if (o.has("above_bottom")) return new Anchor(Anchor.ABOVE_BOTTOM, o.get("above_bottom").getAsInt());
        if (o.has("below_top")) return new Anchor(Anchor.BELOW_TOP, o.get("below_top").getAsInt());
        return null;
    }

    private static int height(Anchor a) {
        return switch (a.kind) {
            case Anchor.ABOVE_BOTTOM -> WORLD_MIN + a.value;
            case Anchor.BELOW_TOP -> WORLD_MAX - a.value;
            default -> a.value;
        };
    }

    public static JsonObject toJson(Map<String, List<Range>> ores) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, List<Range>> e : ores.entrySet()) {
            JsonArray list = new JsonArray();
            for (Range r : e.getValue()) {
                JsonObject o = new JsonObject();
                o.addProperty("min", r.min);
                o.addProperty("max", r.max);
                o.addProperty("per_chunk", r.perChunk);
                o.addProperty("size", r.size);
                o.addProperty("triangle", r.triangle);
                o.add("lo", anchorJson(r.lo));
                o.add("hi", anchorJson(r.hi));
                list.add(o);
            }
            out.add(e.getKey(), list);
        }
        return out;
    }

    /** Обратное к {@link #toJson}; битые записи пропускаются. */
    public static Map<String, List<Range>> fromJson(JsonObject json) {
        Map<String, List<Range>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            if (!e.getValue().isJsonArray()) continue;
            List<Range> list = new ArrayList<>();
            for (JsonElement re : e.getValue().getAsJsonArray()) {
                try {
                    JsonObject o = re.getAsJsonObject();
                    list.add(new Range(o.get("min").getAsInt(), o.get("max").getAsInt(), o.get("per_chunk").getAsFloat(),
                        o.get("size").getAsInt(), o.get("triangle").getAsBoolean(), anchorOf(o.get("lo")), anchorOf(o.get("hi"))));
                } catch (Throwable ignored) {
                    // запись другого формата
                }
            }
            if (!list.isEmpty()) out.put(e.getKey(), list);
        }
        return out;
    }

    private static JsonArray anchorJson(Anchor a) {
        JsonArray arr = new JsonArray();
        arr.add(a.kind);
        arr.add(a.value);
        return arr;
    }

    private static Anchor anchorOf(JsonElement e) {
        JsonArray arr = e.getAsJsonArray();
        return new Anchor(arr.get(0).getAsInt(), arr.get(1).getAsInt());
    }
}
