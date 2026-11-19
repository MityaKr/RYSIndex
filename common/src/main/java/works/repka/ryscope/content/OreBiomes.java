package works.repka.ryscope.content;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * В каких биомах встречается руда: для «где искать» на карте RYSMap (Т4, {@code MapApi.highlight}).
 * <p>
 * Читается на сервере, как {@link OreData}: список жил биома лежит в его настройках генерации
 * ({@code Biome.getGenerationSettings().features()}, у NeoForge уже с изменениями модов), а клиенту биомы приходят без них.
 * Измерение берётся по источнику биомов ({@code LevelStem.generator().getBiomeSource().possibleBiomes()}).
 * <p>
 * Хранятся только руды, которые есть не во всех биомах своего измерения: подсветка всей карты ничего не говорит.
 * Высоты жил с поверхностью не сравниваются (жилы под землёй), поэтому подсветка только по биомам.
 * Чтение с сервера и файл: {@link OreBiomeData}. Здесь без классов игры: правило проверяется юнит-тестом.
 */
public final class OreBiomes {
    private OreBiomes() {}

    /** Измерение и биомы руды, отсортированы. */
    public record Where(String dim, List<String> biomes) {}

    /** Больше биомов в подсветке не держим: такой список почти «везде», а пакет и файл растут. */
    public static final int MAX_BIOMES = 64;

    /**
     * Руда из жил {@code features} в биомах {@code featureBiomes}; {@code dimBiomes} это биомы каждого измерения.
     * Измерение то, где у руды больше всего биомов. null: руда во всех биомах измерения, биомов нет или слишком много.
     */
    public static Where restrict(Collection<String> featureBiomes, Map<String, Set<String>> dimBiomes) {
        String bestDim = null;
        List<String> best = List.of();
        for (Map.Entry<String, Set<String>> d : dimBiomes.entrySet()) {
            List<String> in = new ArrayList<>();
            for (String b : featureBiomes) if (d.getValue().contains(b) && !in.contains(b)) in.add(b);
            if (in.size() > best.size()) {
                best = in;
                bestDim = d.getKey();
            }
        }
        if (bestDim == null || best.isEmpty() || best.size() >= dimBiomes.get(bestDim).size() || best.size() > MAX_BIOMES) return null;
        List<String> sorted = new ArrayList<>(best);
        sorted.sort(null);
        return new Where(bestDim, List.copyOf(sorted));
    }
}
