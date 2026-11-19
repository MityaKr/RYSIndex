package works.repka.rysindex.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.RecipeView;
import works.repka.rysindex.model.Stack;

/**
 * Отчёты для автора сборки: {@code config/rysindex/reports}.
 * Конфликты (одинаковые входы в одной категории дают разное), дубли рецептов,
 * предметы, которые нельзя ни скрафтить, ни добыть известным моду способом.
 * Учитываются свои категории; рецепты, которые рисуют плагины JEI, в отчёты не попадают.
 */
public final class DevReports {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public record Result(Path dir, int conflicts, int duplicates, int unobtainable) {}

    private DevReports() {}

    public static Result run() {
        Snapshot snap = Indexer.snapshot();
        try {
            Path dir = Config.dir().resolve("reports");
            Files.createDirectories(dir);

            // подпись входов: для каждого слота отсортированный список допустимых id
            Map<String, List<RecipeView>> bySignature = new HashMap<>();
            for (Category c : snap.categories) {
                for (RecipeView r : snap.recipes(c)) {
                    if (!r.isCrafting() || r.id() == null) continue;
                    boolean shaped = false;
                    if (r instanceof works.repka.rysindex.content.NativeRecipe nr) {
                        // камнерез и подобные: один вход и выбор результата игроком, это не конфликт
                        if (nr.kind == works.repka.rysindex.content.NativeRecipe.Kind.SINGLE) continue;
                        shaped = nr.kind == works.repka.rysindex.content.NativeRecipe.Kind.CRAFTING && !nr.shapeless;
                    }
                    List<String> slots = new ArrayList<>();
                    for (Ing ing : r.inputs()) {
                        if (ing.isEmpty()) {
                            // в форменном рецепте пустая ячейка часть рисунка
                            if (shaped) slots.add("_");
                            continue;
                        }
                        TreeSet<String> ids = new TreeSet<>();
                        for (Stack s : ing.options) ids.add(s.id().toString());
                        slots.add(String.join("|", ids) + "x" + ing.options.get(0).amount);
                    }
                    if (slots.isEmpty()) continue;
                    if (shaped) slots.add(0, "w" + ((works.repka.rysindex.content.NativeRecipe) r).gridW);
                    else slots.sort(String::compareTo);
                    bySignature.computeIfAbsent(c.id + "#" + String.join(",", slots), k -> new ArrayList<>()).add(r);
                }
            }
            JsonArray conflicts = new JsonArray(), duplicates = new JsonArray();
            for (Map.Entry<String, List<RecipeView>> e : bySignature.entrySet()) {
                List<RecipeView> list = e.getValue();
                if (list.size() < 2) continue;
                TreeSet<String> results = new TreeSet<>();
                for (RecipeView r : list) results.add(outputs(r));
                JsonObject o = new JsonObject();
                o.addProperty("category", e.getKey().substring(0, e.getKey().indexOf('#')));
                JsonArray ids = new JsonArray();
                for (RecipeView r : list) ids.add(r.id().toString());
                o.add("recipes", ids);
                JsonArray outs = new JsonArray();
                for (String s : results) outs.add(s);
                o.add("outputs", outs);
                if (results.size() > 1) conflicts.add(o);
                else duplicates.add(o);
            }

            JsonArray unobtainable = new JsonArray();
            for (Stack s : snap.stacks) {
                if (s.item().isEmpty()) continue;
                if (snap.producing(s).isEmpty()) unobtainable.add(s.id().toString());
            }

            write(dir.resolve("conflicts.json"), conflicts);
            write(dir.resolve("duplicates.json"), duplicates);
            write(dir.resolve("no_known_source.json"), unobtainable);
            RYSIndex.LOG.info("Отчёты: конфликтов {}, дублей {}, без известного источника {}, папка {}",
                conflicts.size(), duplicates.size(), unobtainable.size(), dir);
            return new Result(dir, conflicts.size(), duplicates.size(), unobtainable.size());
        } catch (Exception e) {
            RYSIndex.LOG.warn("Отчёты не построены", e);
            return null;
        }
    }

    private static String outputs(RecipeView r) {
        TreeSet<String> ids = new TreeSet<>();
        for (Stack s : r.outputs()) ids.add(s.id() + "x" + s.amount);
        return String.join("+", ids);
    }

    private static void write(Path file, JsonArray array) throws java.io.IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(array, w);
        }
    }
}
