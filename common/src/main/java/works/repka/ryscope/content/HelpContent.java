package works.repka.ryscope.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Config;
import works.repka.ryscope.runtime.Snapshot;

/**
 * Страницы справки: короткий текст о предмете с иконками, ссылками на другие предметы и предупреждениями.
 * Источники: {@code assets/<namespace>/ryscope_help/*.json} в ресурспаке или jar мода
 * и {@code config/ryscope/help/*.json} в сборке. Формат описан в docs/09-data-formats.md.
 */
public final class HelpContent {
    public static final String FOLDER = "ryscope_help";
    public static final ResourceLocation CATEGORY = ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "help");

    private record Page(String source, JsonObject json) {}

    private static volatile List<Page> pages = List.of();

    private HelpContent() {}

    public static void reload(ResourceManager manager) {
        List<Page> loaded = new ArrayList<>();
        for (var e : manager.listResources(FOLDER, p -> p.getPath().endsWith(".json")).entrySet()) {
            try (Reader reader = e.getValue().openAsReader()) {
                loaded.add(new Page(e.getKey().toString(), JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception ex) {
                RYScope.LOG.warn("Страница справки {} не прочитана: {}", e.getKey(), ex.toString());
            }
        }
        Path dir = Config.dir().resolve("help");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".json")).sorted().forEach(f -> {
                    try (Reader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                        loaded.add(new Page(f.getFileName().toString(), JsonParser.parseReader(reader).getAsJsonObject()));
                    } catch (Exception ex) {
                        RYScope.LOG.warn("Страница справки {} не прочитана: {}", f, ex.toString());
                    }
                });
            } catch (Exception ex) {
                RYScope.LOG.warn("Папка справки не прочитана: {}", ex.toString());
            }
        }
        pages = loaded;
    }

    public static void provide(Snapshot.Builder b) {
        List<Page> list = pages;
        if (list.isEmpty()) return;
        Category category = new Category(CATEGORY, Component.translatable("ryscope.category.help"), ItemRes.of(new ItemStack(Items.WRITABLE_BOOK)), -10);
        int n = 0;
        for (Page page : list) {
            try {
                Ing targets = JsonStacks.ing(target(page.json.get("target")));
                if (targets.isEmpty()) continue;
                List<Stack> stacks = new ArrayList<>(targets.options);
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "help/" + (n++));
                b.recipe(b.category(category), new HelpView(id, stacks, page.json.has("body") ? page.json.getAsJsonArray("body") : null));
            } catch (Throwable t) {
                RYScope.LOG.warn("Страница справки {} пропущена: {}", page.source, t.toString());
            }
        }
    }

    /** Цель в записи концепта: строка-id, «#тег» или массив. */
    private static JsonElement target(JsonElement e) {
        return e;
    }
}
