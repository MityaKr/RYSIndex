package works.repka.rysindex.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.RecipeView;
import works.repka.rysindex.model.Stack;

/**
 * Выгрузка того, что знает мод, в JSON: для вики, таблиц и проверки сборки.
 * Пишет {@code config/rysindex/export/items.json} и {@code recipes.json}.
 * В рецепты попадают свои категории (ваниль, раскладки из JSON, источники); рецепты, которые рисует JEI, не выгружаются.
 */
public final class Export {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Export() {}

    /** @return папка с файлами или null при ошибке. */
    public static Path run() {
        Snapshot snap = Indexer.snapshot();
        try {
            Path dir = Config.dir().resolve("export");
            Files.createDirectories(dir);

            JsonArray items = new JsonArray();
            for (Stack s : snap.stacks) {
                JsonObject o = new JsonObject();
                o.addProperty("id", s.id().toString());
                o.addProperty("type", s.type());
                o.addProperty("name", s.name().getString());
                JsonArray tags = new JsonArray();
                for (String t : s.tags()) tags.add(t);
                o.add("tags", tags);
                items.add(o);
            }
            write(dir.resolve("items.json"), items);

            JsonArray recipes = new JsonArray();
            for (Category c : snap.categories) {
                for (RecipeView r : snap.recipes(c)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("category", c.id.toString());
                    if (r.id() != null) o.addProperty("id", r.id().toString());
                    JsonArray in = new JsonArray();
                    for (Ing ing : r.inputs()) {
                        if (ing.isEmpty()) continue;
                        JsonObject io = new JsonObject();
                        if (ing.tag != null) io.addProperty("tag", ing.tag);
                        io.addProperty("amount", ing.options.get(0).amount);
                        JsonArray options = new JsonArray();
                        for (Stack s : ing.options) options.add(s.id().toString());
                        io.add("options", options);
                        in.add(io);
                    }
                    o.add("inputs", in);
                    JsonArray out = new JsonArray();
                    for (Stack s : r.outputs()) {
                        JsonObject so = new JsonObject();
                        so.addProperty("id", s.id().toString());
                        so.addProperty("amount", s.amount);
                        if (s.chance < 0.999f) so.addProperty("chance", s.chance);
                        out.add(so);
                    }
                    o.add("outputs", out);
                    recipes.add(o);
                }
            }
            write(dir.resolve("recipes.json"), recipes);
            RYSIndex.LOG.info("Export: items {}, recipes {}, folder {}", items.size(), recipes.size(), dir);
            return dir;
        } catch (Exception e) {
            RYSIndex.LOG.warn("Export failed", e);
            return null;
        }
    }

    private static void write(Path file, JsonArray array) throws java.io.IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(array, w);
        }
    }
}
