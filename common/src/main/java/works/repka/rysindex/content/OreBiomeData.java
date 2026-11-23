package works.repka.rysindex.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import works.repka.rysindex.RYSIndex;

/** Биомы руд ({@link OreBiomes}): чтение на сервере и запись в кеш одиночной игры. */
public final class OreBiomeData {
    private OreBiomeData() {}

    /** По блокам руд и их жилам ({@link OreData#extract(MinecraftServer, Map)}). */
    public static Map<String, OreBiomes.Where> extract(MinecraftServer server, Map<String, List<PlacedFeature>> blockFeatures) {
        Map<String, OreBiomes.Where> out = new LinkedHashMap<>();
        try {
            Map<String, Set<String>> dims = new LinkedHashMap<>();
            Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
            for (Map.Entry<ResourceKey<LevelStem>, LevelStem> e : stems.entrySet()) {
                Set<String> set = new HashSet<>();
                for (Holder<Biome> h : e.getValue().generator().getBiomeSource().possibleBiomes()) {
                    h.unwrapKey().ifPresent(k -> set.add(k.location().toString()));
                }
                dims.put(e.getKey().location().toString(), set);
            }
            Map<PlacedFeature, Set<String>> featureBiomes = new IdentityHashMap<>();
            Registry<Biome> biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
            for (Map.Entry<ResourceKey<Biome>, Biome> e : biomes.entrySet()) {
                String id = e.getKey().location().toString();
                for (HolderSet<PlacedFeature> step : e.getValue().getGenerationSettings().features()) {
                    for (Holder<PlacedFeature> f : step) featureBiomes.computeIfAbsent(f.value(), k -> new HashSet<>()).add(id);
                }
            }
            for (Map.Entry<String, List<PlacedFeature>> e : blockFeatures.entrySet()) {
                Set<String> in = new HashSet<>();
                for (PlacedFeature f : e.getValue()) in.addAll(featureBiomes.getOrDefault(f, Set.of()));
                OreBiomes.Where where = OreBiomes.restrict(in, dims);
                if (where != null) out.put(e.getKey(), where);
            }
        } catch (Throwable t) {
            RYSIndex.LOG.warn("Ore biomes could not be read: {}", t.toString());
        }
        return out;
    }

    public static JsonObject toJson(Map<String, OreBiomes.Where> map) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, OreBiomes.Where> e : map.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("dim", e.getValue().dim());
            JsonArray list = new JsonArray();
            for (String b : e.getValue().biomes()) list.add(b);
            o.add("biomes", list);
            out.add(e.getKey(), o);
        }
        return out;
    }

    /** Обратное к {@link #toJson}; битые записи пропускаются. */
    public static Map<String, OreBiomes.Where> fromJson(JsonObject json) {
        Map<String, OreBiomes.Where> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            try {
                JsonObject o = e.getValue().getAsJsonObject();
                List<String> list = new ArrayList<>();
                for (JsonElement b : o.getAsJsonArray("biomes")) list.add(b.getAsString());
                if (!list.isEmpty() && list.size() <= OreBiomes.MAX_BIOMES) out.put(e.getKey(), new OreBiomes.Where(o.get("dim").getAsString(), List.copyOf(list)));
            } catch (Throwable ignored) {
                // запись другого формата
            }
        }
        return out;
    }
}
