package works.repka.ryscope.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.model.FluidRes;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.Stack;

/**
 * Читает ингредиенты и результаты из JSON рецепта в том виде, в каком их пишут моды в датапаках:
 * предмет, тег, список вариантов, стек с количеством и шансом, жидкость с объёмом.
 */
public final class JsonStacks {
    private static final int TAG_LIMIT = 64;

    private JsonStacks() {}

    /** Значение по пути вида {@code ingredients.top} или {@code results[0]}. */
    @Nullable
    public static JsonElement path(JsonElement root, String path) {
        JsonElement cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null || part.isEmpty()) return cur;
            String name = part;
            int index = -1;
            int bracket = part.indexOf('[');
            if (bracket >= 0 && part.endsWith("]")) {
                name = part.substring(0, bracket);
                try {
                    index = Integer.parseInt(part.substring(bracket + 1, part.length() - 1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (!name.isEmpty()) {
                if (!cur.isJsonObject()) return null;
                cur = cur.getAsJsonObject().get(name);
            }
            if (index >= 0) {
                if (cur == null || !cur.isJsonArray() || index >= cur.getAsJsonArray().size()) return null;
                cur = cur.getAsJsonArray().get(index);
            }
        }
        return cur;
    }

    /** Список ингредиентов: массив превращается в несколько, одиночное значение в один. */
    public static List<Ing> list(@Nullable JsonElement e) {
        List<Ing> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            for (JsonElement el : e.getAsJsonArray()) {
                Ing ing = ing(el);
                if (!ing.isEmpty()) out.add(ing);
            }
        } else {
            Ing ing = ing(e);
            if (!ing.isEmpty()) out.add(ing);
        }
        return out;
    }

    /** Один ингредиент; массив здесь означает взаимозаменяемые варианты. */
    public static Ing ing(@Nullable JsonElement e) {
        List<Stack> options = new ArrayList<>();
        String[] tag = new String[1];
        collect(e, options, 1, 1f, tag, 0);
        return options.isEmpty() ? Ing.EMPTY : new Ing(options, tag[0]);
    }

    private static void collect(@Nullable JsonElement e, List<Stack> out, long count, float chance, String[] tag, int depth) {
        if (e == null || e.isJsonNull() || depth > 5) return;
        if (e.isJsonArray()) {
            for (JsonElement el : e.getAsJsonArray()) collect(el, out, count, chance, tag, depth + 1);
            return;
        }
        if (e.isJsonPrimitive()) {
            String s = e.getAsString();
            if (s.startsWith("#")) itemTag(s.substring(1), out, count, chance, tag);
            else item(s, out, count, chance);
            return;
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("count") && o.get("count").isJsonPrimitive()) count = Math.max(1, o.get("count").getAsLong());
        if (o.has("chance") && o.get("chance").isJsonPrimitive()) chance = o.get("chance").getAsFloat();
        long amount = o.has("amount") && o.get("amount").isJsonPrimitive() ? Math.max(1, o.get("amount").getAsLong()) : -1;

        // жидкости
        if (o.has("fluid") && o.get("fluid").isJsonPrimitive()) {
            fluid(o.get("fluid").getAsString(), out, amount < 0 ? 1000 : amount, chance);
            return;
        }
        if (o.has("fluid_tag") && o.get("fluid_tag").isJsonPrimitive()) {
            fluidTag(o.get("fluid_tag").getAsString(), out, amount < 0 ? 1000 : amount, chance, tag);
            return;
        }
        String type = o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : "";
        boolean fluidType = type.contains("fluid");
        if (fluidType && o.has("tag") && o.get("tag").isJsonPrimitive()) {
            fluidTag(o.get("tag").getAsString(), out, amount < 0 ? 1000 : amount, chance, tag);
            return;
        }
        if (amount >= 0 && o.has("id") && o.get("id").isJsonPrimitive()) {
            // стек жидкости в записи {"id": ..., "amount": ...}; предметы пишут count
            String id = o.get("id").getAsString();
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && BuiltInRegistries.FLUID.containsKey(rl) && (fluidType || !BuiltInRegistries.ITEM.containsKey(rl))) {
                fluid(id, out, amount, chance);
                return;
            }
        }
        if (amount >= 0 && !o.has("count") && o.has("tag") && o.get("tag").isJsonPrimitive()) {
            // {"amount": 250, "tag": "c:honey"}: так моды записывают жидкость по тегу
            int before = out.size();
            fluidTag(o.get("tag").getAsString(), out, amount, chance, tag);
            if (out.size() > before) return;
            tag[0] = null;
        }
        if (amount >= 0 && !o.has("count")) count = amount;

        // предметы
        for (String key : new String[] {"item", "id", "name"}) {
            if (!o.has(key)) continue;
            JsonElement v = o.get(key);
            if (v.isJsonPrimitive()) item(v.getAsString(), out, count, chance);
            else collect(v, out, count, chance, tag, depth + 1);
            return;
        }
        if (o.has("tag") && o.get("tag").isJsonPrimitive()) {
            itemTag(o.get("tag").getAsString(), out, count, chance, tag);
            return;
        }
        // обёртки: {"ingredient": {...}, "count": 2}, составные ингредиенты NeoForge
        for (String key : new String[] {"ingredient", "ingredients", "items", "children", "values", "stack", "base", "input"}) {
            if (o.has(key)) {
                collect(o.get(key), out, count, chance, tag, depth + 1);
                return;
            }
        }
    }

    private static void item(String id, List<Stack> out, long count, float chance) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return;
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) return;
        ItemRes res = new ItemRes(new ItemStack(item));
        res.amount = count;
        res.chance = chance;
        out.add(res);
    }

    private static void itemTag(String id, List<Stack> out, long count, float chance, String[] tag) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return;
        if (tag[0] == null) tag[0] = id;
        int n = 0;
        for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, rl))) {
            if (n++ >= TAG_LIMIT) break;
            ItemRes res = new ItemRes(new ItemStack(h.value()));
            res.amount = count;
            res.chance = chance;
            out.add(res);
        }
    }

    private static void fluid(String id, List<Stack> out, long amount, float chance) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return;
        Fluid fluid = BuiltInRegistries.FLUID.get(rl);
        if (fluid == Fluids.EMPTY) return;
        FluidRes res = new FluidRes(fluid, amount);
        res.chance = chance;
        out.add(res);
    }

    private static void fluidTag(String id, List<Stack> out, long amount, float chance, String[] tag) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return;
        if (tag[0] == null) tag[0] = id;
        int n = 0;
        for (Holder<Fluid> h : BuiltInRegistries.FLUID.getTagOrEmpty(TagKey.create(Registries.FLUID, rl))) {
            if (n++ >= TAG_LIMIT) break;
            // у жидкости два состояния (источник и течение): показываем только источник
            if (!h.value().isSource(h.value().defaultFluidState())) continue;
            FluidRes res = new FluidRes(h.value(), amount);
            res.chance = chance;
            out.add(res);
        }
    }

    public static List<JsonElement> elements(@Nullable JsonElement e) {
        List<JsonElement> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (JsonElement el : a) out.add(el);
        } else {
            out.add(e);
        }
        return out;
    }
}
