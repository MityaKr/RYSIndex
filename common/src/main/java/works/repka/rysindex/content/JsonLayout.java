package works.repka.rysindex.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.ItemRes;
import works.repka.rysindex.model.Stack;
import works.repka.rysindex.runtime.Snapshot;

/**
 * Категория рецептов, описанная данными: {@code assets/<namespace>/rysindex_layouts/<имя>.json}.
 * Раскладка читает поля рецепта из его же JSON (как в датапаке), поэтому Java-кода и зависимости от мода не нужно.
 * Раскладка из чужого пространства имён (мода или сборки) важнее встроенной в RYSIndex.
 */
public final class JsonLayout {
    public static final String FOLDER = "rysindex_layouts";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}|]+)(?:\\|([a-z_]+))?}");

    private record Widget(String type, String source, int index, int from, int max, int x, int y, int w, int columns,
                          int pitchX, int pitchY, boolean large, boolean output, boolean keepEmpty,
                          String text, List<String> args, String ifPath, String unlessPath, String equals) {}

    private static volatile List<JsonLayout> all = List.of();
    private static volatile Set<ResourceLocation> replaced = Set.of();

    private final String namespace;
    private final String file;
    private final ResourceLocation category;
    private final List<ResourceLocation> types = new ArrayList<>();
    private final List<ResourceLocation> replaces = new ArrayList<>();
    private final String title;
    private final String icon;
    private final List<String> stations = new ArrayList<>();
    private final int width, height, order;
    /** Откуда брать время рецепта (путь в JSON), время по умолчанию в тиках и расход энергии в FE за тик. */
    private final String timePath;
    private final int timeDefault;
    private final int energyPerTick;
    private final List<Widget> widgets = new ArrayList<>();

    private JsonLayout(String namespace, String file, JsonObject json) {
        this.namespace = namespace;
        this.file = file;
        for (JsonElement e : JsonStacks.elements(json.get("for"))) types.add(ResourceLocation.parse(e.getAsString()));
        if (types.isEmpty()) throw new IllegalArgumentException("не указано поле \"for\"");
        this.category = json.has("category") ? ResourceLocation.parse(json.get("category").getAsString()) : types.get(0);
        for (JsonElement e : JsonStacks.elements(json.get("replaces"))) replaces.add(ResourceLocation.parse(e.getAsString()));
        this.title = json.has("title") ? json.get("title").getAsString() : category.toString();
        this.icon = json.has("icon") ? json.get("icon").getAsString() : null;
        for (JsonElement e : JsonStacks.elements(json.get("stations"))) stations.add(e.getAsString());
        JsonArray size = json.has("size") ? json.getAsJsonArray("size") : null;
        this.width = size == null ? 124 : size.get(0).getAsInt();
        this.height = size == null ? 44 : size.get(1).getAsInt();
        this.order = json.has("order") ? json.get("order").getAsInt() : 60;
        this.timePath = json.has("time") ? json.get("time").getAsString() : null;
        this.timeDefault = json.has("time_default") ? json.get("time_default").getAsInt() : 0;
        this.energyPerTick = json.has("energy_per_tick") ? json.get("energy_per_tick").getAsInt() : 0;
        for (JsonElement e : JsonStacks.elements(json.get("widgets"))) widgets.add(widget(e.getAsJsonObject()));
    }

    private static Widget widget(JsonObject o) {
        JsonArray at = o.has("at") ? o.getAsJsonArray("at") : null;
        List<String> args = new ArrayList<>();
        for (JsonElement e : JsonStacks.elements(o.get("args"))) args.add(e.getAsString());
        return new Widget(
            str(o, "type", "slot"), str(o, "source", ""), num(o, "index", -1), num(o, "from", 0), num(o, "max", 99),
            at == null ? 0 : at.get(0).getAsInt(), at == null ? 0 : at.get(1).getAsInt(), num(o, "width", 22),
            Math.max(1, num(o, "columns", 3)), num(o, "pitch_x", 20), num(o, "pitch_y", 20),
            bool(o, "large"), bool(o, "output"), bool(o, "keep_empty"),
            str(o, "text", ""), args, str(o, "if", null), str(o, "unless", null), str(o, "equals", null));
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static int num(JsonObject o, String key, int def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def;
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    /** Категории JEI, которые полностью заменены раскладками: мост их не показывает. */
    public static Set<ResourceLocation> replacedCategories() {
        return replaced;
    }

    public static void reload(ResourceManager manager) {
        // по одному описанию на тип рецепта: чужое пространство имён важнее встроенного
        Map<ResourceLocation, JsonLayout> byType = new HashMap<>();
        Map<ResourceLocation, Resource> found = manager.listResources(FOLDER, p -> p.getPath().endsWith(".json"));
        List<ResourceLocation> files = new ArrayList<>(found.keySet());
        files.sort((a, b) -> {
            boolean ao = a.getNamespace().equals(RYSIndex.MOD_ID), bo = b.getNamespace().equals(RYSIndex.MOD_ID);
            return ao == bo ? a.compareTo(b) : ao ? -1 : 1;
        });
        int count = 0;
        for (ResourceLocation file : files) {
            try (Reader reader = found.get(file).openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                JsonLayout layout = new JsonLayout(file.getNamespace(), file.toString(), json);
                for (ResourceLocation type : layout.types) byType.put(type, layout);
                count++;
            } catch (Exception e) {
                RYSIndex.LOG.warn("Раскладка {} не прочитана: {}", file, e.toString());
            }
        }
        all = new ArrayList<>(new HashSet<>(byType.values()));
        RYSIndex.LOG.info("Раскладок категорий из ресурспаков: {} (файлов {})", all.size(), count);
    }

    public static void provide(Snapshot.Builder b, RecipeManager manager, RegistryAccess registries) {
        List<JsonLayout> layouts = all;
        Set<ResourceLocation> replacedNow = new HashSet<>();
        if (layouts.isEmpty()) {
            replaced = replacedNow;
            return;
        }
        Map<RecipeType<?>, JsonLayout> byType = new HashMap<>();
        for (JsonLayout layout : layouts) {
            for (ResourceLocation id : layout.types) {
                RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.getOptional(id).orElse(null);
                // мода нет в сборке: раскладка просто не используется
                if (type != null) byType.put(type, layout);
            }
        }
        if (byType.isEmpty()) {
            replaced = replacedNow;
            return;
        }
        DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        Map<JsonLayout, Category> categories = new HashMap<>();
        Map<JsonLayout, Integer> counts = new HashMap<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            JsonLayout layout = byType.get(holder.value().getType());
            if (layout == null) continue;
            try {
                JsonElement json = Recipe.CODEC.encodeStart(ops, holder.value()).result().orElse(null);
                if (json == null) continue;
                NativeRecipe recipe = layout.build(holder, json);
                if (recipe == null) continue;
                Category category = categories.computeIfAbsent(layout, l -> b.category(l.category()));
                b.recipe(category, recipe);
                counts.merge(layout, 1, Integer::sum);
            } catch (Throwable t) {
                RYSIndex.LOG.debug("Рецепт {} не разложен по {}: {}", holder.id(), layout.file, t.toString());
            }
        }
        for (Map.Entry<JsonLayout, Integer> e : counts.entrySet()) {
            replacedNow.add(e.getKey().category);
            replacedNow.addAll(e.getKey().replaces);
            RYSIndex.LOG.debug("Раскладка {}: рецептов {}", e.getKey().file, e.getValue());
        }
        replaced = replacedNow;
    }

    private Category category() {
        Component name = Language.getInstance().has(title) ? Component.translatable(title) : Component.literal(title);
        Stack iconStack = icon == null ? null : item(icon);
        Category c = new Category(category, name, iconStack, order);
        for (String s : stations) {
            Stack st = item(s);
            if (st != null) c.stations.add(st);
        }
        return c;
    }

    private static Stack item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item == Items.AIR ? null : ItemRes.of(new ItemStack(item));
    }

    private static boolean truthy(JsonElement e) {
        if (e == null || e.isJsonNull()) return false;
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble() != 0;
            return !p.getAsString().isEmpty() && !p.getAsString().equals("none");
        }
        if (e.isJsonArray()) return !e.getAsJsonArray().isEmpty();
        return true;
    }

    private NativeRecipe build(RecipeHolder<?> holder, JsonElement json) {
        NativeRecipe r = new NativeRecipe(NativeRecipe.Kind.OTHER, holder, width, height);
        r.fromLayout = true;
        JsonElement time = timePath == null ? null : JsonStacks.path(json, timePath);
        r.ticks = time != null && time.isJsonPrimitive() && time.getAsJsonPrimitive().isNumber() ? Math.max(0, time.getAsInt()) : timeDefault;
        r.energy = (long) energyPerTick * r.ticks;
        boolean any = false;
        for (Widget w : widgets) {
            if (w.ifPath != null) {
                JsonElement v = JsonStacks.path(json, w.ifPath);
                if (w.equals != null ? v == null || !v.isJsonPrimitive() || !v.getAsString().equals(w.equals) : !truthy(v)) continue;
            }
            if (w.unlessPath != null && truthy(JsonStacks.path(json, w.unlessPath))) continue;
            switch (w.type) {
                case "slot" -> {
                    JsonElement el = JsonStacks.path(json, w.source);
                    if (w.index >= 0) {
                        List<JsonElement> els = JsonStacks.elements(el);
                        el = w.index < els.size() ? els.get(w.index) : null;
                    }
                    Ing ing = JsonStacks.ing(el);
                    if (ing.isEmpty()) {
                        if (w.keepEmpty) r.slots.add(new NativeRecipe.SlotW(Ing.EMPTY, w.x, w.y, w.output, w.large));
                        continue;
                    }
                    place(r, ing, w.x, w.y, w);
                    any = true;
                }
                case "slots" -> {
                    List<JsonElement> els = JsonStacks.elements(JsonStacks.path(json, w.source));
                    int n = 0;
                    for (int i = w.from; i < els.size() && n < w.max; i++) {
                        Ing ing = JsonStacks.ing(els.get(i));
                        if (ing.isEmpty()) continue;
                        place(r, ing, w.x + (n % w.columns) * w.pitchX, w.y + (n / w.columns) * w.pitchY, w);
                        n++;
                        any = true;
                    }
                }
                case "arrow" -> r.arrow(w.x, w.y, w.w);
                case "flame" -> r.flame(w.x, w.y);
                case "plus" -> r.plus(w.x, w.y);
                case "text" -> {
                    String text = text(w, json);
                    if (!text.isBlank()) r.text(text, w.x, w.y);
                }
                default -> {
                    // неизвестный виджет из более новой версии формата: пропускаем
                }
            }
        }
        return any ? r : null;
    }

    private static void place(NativeRecipe r, Ing ing, int x, int y, Widget w) {
        if (w.output) r.output(ing.options.get(0), x, y, w.large);
        else if (w.large) r.inputLarge(ing, x, y);
        else r.input(ing, x, y);
    }

    private static String text(Widget w, JsonElement json) {
        if (!w.args.isEmpty()) {
            Object[] values = new Object[w.args.size()];
            for (int i = 0; i < values.length; i++) {
                String[] parts = w.args.get(i).split("\\|", 2);
                values[i] = value(JsonStacks.path(json, parts[0]), parts.length > 1 ? parts[1] : null);
                if (values[i].toString().isEmpty()) return "";
            }
            return Component.translatable(w.text, values).getString();
        }
        Matcher m = PLACEHOLDER.matcher(w.text);
        StringBuilder sb = new StringBuilder();
        boolean missing = false;
        while (m.find()) {
            String v = value(JsonStacks.path(json, m.group(1)), m.group(2));
            if (v.isEmpty()) missing = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        if (missing) return "";
        String out = sb.toString();
        return Language.getInstance().has(out) ? Component.translatable(out).getString() : out;
    }

    private static String value(JsonElement e, String filter) {
        if (e == null || !e.isJsonPrimitive()) return "";
        var p = e.getAsJsonPrimitive();
        if (p.isNumber()) {
            double v = p.getAsDouble();
            if ("ticks".equals(filter)) return Component.translatable("rysindex.recipe.seconds", trim(v / 20.0)).getString();
            if ("percent".equals(filter)) return trim(v * 100.0) + "%";
            if ("fe".equals(filter)) return works.repka.rysindex.runtime.Units.energy(v);
            if ("mb".equals(filter)) return trim(v) + " mB";
            return trim(v);
        }
        String s = p.getAsString();
        if ("name".equals(filter)) {
            // значение перечисления вроде «heated»: показываем перевод, если мод его дал
            String key = "rysindex.value." + s;
            return Language.getInstance().has(key) ? Component.translatable(key).getString() : s.replace('_', ' ');
        }
        return s;
    }

    private static String trim(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
