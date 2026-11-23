package works.repka.rysindex.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.ItemRes;
import works.repka.rysindex.model.Stack;
import works.repka.rysindex.runtime.Config;
import works.repka.rysindex.runtime.Snapshot;

/**
 * Страницы справки: короткий текст о предмете с иконками, ссылками на другие предметы и предупреждениями.
 * Источники: {@code assets/<namespace>/rysindex_help/*.json} в ресурспаке или jar мода
 * и {@code config/rysindex/help/*.json} в сборке. Формат описан в docs/09-data-formats.md.
 * «Сведения» JEI ({@code addInformation}) попадают в ту же категорию следом за нашими страницами (см. JeiBridge).
 */
public final class HelpContent {
    public static final String FOLDER = "rysindex_help";
    public static final ResourceLocation CATEGORY = ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "help");

    private record Page(String source, JsonObject json) {}

    private static volatile List<Page> pages = List.of();
    private static Category category;
    /** Предмет карточки, которая сейчас рисует справку: его и показывает слот страницы, если он среди её предметов. */
    private static Stack focus;

    private HelpContent() {}

    /** Категория «Справка»: одна на наши страницы и сведения JEI, поэтому они сливаются в одну вкладку. */
    public static synchronized Category category() {
        if (category == null) {
            category = new Category(CATEGORY, Component.translatable("rysindex.category.help"), ItemRes.of(new ItemStack(Items.WRITABLE_BOOK)), -10);
        }
        return category;
    }

    public static boolean isHelp(Category c) {
        return c.id.equals(CATEGORY);
    }

    public static void setFocus(Stack stack) {
        focus = stack;
    }

    public static Stack focus() {
        return focus;
    }

    public static void reload(ResourceManager manager) {
        List<Page> loaded = new ArrayList<>();
        for (var e : manager.listResources(FOLDER, p -> p.getPath().endsWith(".json")).entrySet()) {
            try (Reader reader = e.getValue().openAsReader()) {
                loaded.add(new Page(e.getKey().toString(), JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception ex) {
                RYSIndex.LOG.warn("Help page {} could not be read: {}", e.getKey(), ex.toString());
            }
        }
        Path dir = Config.dir().resolve("help");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".json")).sorted().forEach(f -> {
                    try (Reader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                        loaded.add(new Page(f.getFileName().toString(), JsonParser.parseReader(reader).getAsJsonObject()));
                    } catch (Exception ex) {
                        RYSIndex.LOG.warn("Help page {} could not be read: {}", f, ex.toString());
                    }
                });
            } catch (Exception ex) {
                RYSIndex.LOG.warn("Help folder could not be read: {}", ex.toString());
            }
        }
        pages = loaded;
    }

    public static void provide(Snapshot.Builder b) {
        List<Page> list = pages;
        if (list.isEmpty()) return;
        Category category = category();
        int n = 0;
        for (Page page : list) {
            try {
                List<Stack> stacks = targets(page.json.get("target"));
                if (stacks.isEmpty()) continue;
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "help/" + (n++));
                b.recipe(b.category(category), new HelpView(id, stacks, page.json.has("body") ? page.json.getAsJsonArray("body") : null));
            } catch (Throwable t) {
                RYSIndex.LOG.warn("Help page {} skipped: {}", page.source, t.toString());
            }
        }
    }

    /**
     * Цели страницы: строка-id, «#тег», массив или объект {@code {"id": ..., "components": {...}}}. С компонентами страница
     * относится только к этому подтипу предмета: контроллер одной машины Modular Machinery, а не все контроллеры.
     */
    static List<Stack> targets(JsonElement e) {
        List<Stack> out = new ArrayList<>();
        for (JsonElement part : HelpTargets.parts(e)) {
            if (HelpTargets.hasComponents(part)) {
                ItemStack stack = withComponents(part.getAsJsonObject());
                if (!stack.isEmpty()) out.add(new ItemRes(stack));
            } else {
                Ing ing = JsonStacks.ing(part);
                if (!ing.isEmpty()) out.addAll(ing.options);
            }
        }
        return out;
    }

    private static ItemStack withComponents(JsonObject o) {
        String key = o.has("id") ? "id" : o.has("item") ? "item" : null;
        if (key == null || !o.get(key).isJsonPrimitive()) return ItemStack.EMPTY;
        ResourceLocation rl = ResourceLocation.tryParse(o.get(key).getAsString());
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(rl));
        DataComponentPatch.CODEC.parse(ops(), o.get("components"))
            .resultOrPartial(err -> RYSIndex.LOG.warn("Help target {} components: {}", rl, err))
            .ifPresent(stack::applyComponents);
        return stack;
    }

    /** Компоненты с реестрами мира, если он есть (зачарования и прочее по ссылке на реестр), иначе простой JSON. */
    private static DynamicOps<JsonElement> ops() {
        try {
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level != null) return level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        } catch (Throwable ignored) {
            // на сервере клиента нет: хватает простого JSON
        }
        return JsonOps.INSTANCE;
    }
}
