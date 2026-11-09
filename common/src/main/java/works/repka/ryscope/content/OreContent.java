package works.repka.ryscope.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Snapshot;
import works.repka.ryscope.ui.Draw;
import works.repka.ryscope.ui.theme.Theme;

/**
 * Генерация руд: на каких высотах и как часто встречается блок.
 * Читается из данных генерации мира (они есть только на сервере) через их JSON-представление.
 * Пока работает в одиночной игре; высоты «от дна мира» пересчитаны для обычного мира (дно на -64).
 */
public final class OreContent {
    public static final ResourceLocation CATEGORY = ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "ore_gen");
    private static final int WORLD_MIN = -64, WORLD_MAX = 320;

    /** minLabel и maxLabel: как показать границу игроку (число или «дно+8», если высота задана от края мира). */
    public record Range(int min, int max, float perChunk, int size, boolean triangle, String minLabel, String maxLabel) {}

    private static Object cachedFor;
    private static Map<Block, List<Range>> cached = Map.of();

    private OreContent() {}

    public static synchronized void provide(Snapshot.Builder b) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        Object key = server.reloadableRegistries();
        if (key != cachedFor) {
            cached = extract(server);
            cachedFor = key;
        }
        if (cached.isEmpty()) return;
        Category category = b.category(new Category(CATEGORY, Component.translatable("ryscope.category.ore_gen"),
            ItemRes.of(new ItemStack(Items.DIAMOND_ORE)), 25));
        for (Map.Entry<Block, List<Range>> e : cached.entrySet()) {
            Item item = e.getKey().asItem();
            if (item == Items.AIR) continue;
            List<Stack> outputs = new ArrayList<>();
            outputs.add(ItemRes.of(new ItemStack(item)));
            SourceView drops = LootContent.forBlock(e.getKey());
            if (drops != null) for (SourceView.Drop d : drops.drops()) outputs.add(d.stack());
            b.recipe(category, new OreView(BuiltInRegistries.BLOCK.getKey(e.getKey()), outputs, e.getValue()));
        }
    }

    private static Map<Block, List<Range>> extract(MinecraftServer server) {
        Map<Block, List<Range>> out = new LinkedHashMap<>();
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
                    String minLabel = null, maxLabel = null;
                    for (JsonElement me : placedJson.getAsJsonObject().getAsJsonArray("placement")) {
                        JsonObject m = me.getAsJsonObject();
                        String mt = m.has("type") ? m.get("type").getAsString() : "";
                        if (mt.equals("minecraft:count") && m.has("count")) perChunk *= average(m.get("count"));
                        else if (mt.equals("minecraft:rarity_filter") && m.has("chance")) perChunk /= Math.max(1, m.get("chance").getAsInt());
                        else if (mt.equals("minecraft:height_range") && m.has("height") && m.get("height").isJsonObject()) {
                            JsonObject h = m.getAsJsonObject("height");
                            triangle = h.has("type") && h.get("type").getAsString().contains("trapezoid");
                            if (h.has("min_inclusive")) {
                                min = anchor(h.get("min_inclusive"), min);
                                minLabel = label(h.get("min_inclusive"));
                            }
                            if (h.has("max_inclusive")) {
                                max = anchor(h.get("max_inclusive"), max);
                                maxLabel = label(h.get("max_inclusive"));
                            }
                            hasHeight = true;
                        }
                    }
                    if (!hasHeight || !config.has("targets")) continue;
                    int lo = Math.max(WORLD_MIN, min), hi = Math.min(WORLD_MAX, max);
                    Range range = new Range(lo, hi, perChunk, size, triangle,
                        minLabel != null ? minLabel : Integer.toString(lo), maxLabel != null ? maxLabel : Integer.toString(hi));
                    for (JsonElement te : config.getAsJsonArray("targets")) {
                        JsonObject state = te.getAsJsonObject().getAsJsonObject("state");
                        if (state == null || !state.has("Name")) continue;
                        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(state.get("Name").getAsString()));
                        if (block == Blocks.AIR) continue;
                        List<Range> list = out.computeIfAbsent(block, k -> new ArrayList<>());
                        if (!list.contains(range) && list.size() < 4) list.add(range);
                    }
                } catch (Throwable ignored) {
                    // особая конфигурация мода: пропускаем эту жилу
                }
            }
        } catch (Throwable t) {
            RYScope.LOG.warn("Данные генерации руд не прочитаны: {}", t.toString());
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

    private static String label(JsonElement e) {
        if (!e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        if (o.has("absolute")) return Integer.toString(o.get("absolute").getAsInt());
        if (o.has("above_bottom")) return Component.translatable("ryscope.ore.above_bottom", o.get("above_bottom").getAsInt()).getString();
        if (o.has("below_top")) return Component.translatable("ryscope.ore.below_top", o.get("below_top").getAsInt()).getString();
        return null;
    }

    private static int anchor(JsonElement e, int def) {
        if (!e.isJsonObject()) return def;
        JsonObject o = e.getAsJsonObject();
        if (o.has("absolute")) return o.get("absolute").getAsInt();
        if (o.has("above_bottom")) return WORLD_MIN + o.get("above_bottom").getAsInt();
        if (o.has("below_top")) return WORLD_MAX - o.get("below_top").getAsInt();
        return def;
    }

    /** Карточка руды: шкала высот мира с отмеченными диапазонами и частотой. */
    public static final class OreView implements RecipeView {
        private final ResourceLocation id;
        private final List<Stack> outputs;
        private final List<Range> ranges;
        private final List<Ing> inputs;

        OreView(ResourceLocation blockId, List<Stack> outputs, List<Range> ranges) {
            this.id = ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "ore/" + blockId.getNamespace() + "/" + blockId.getPath());
            this.outputs = outputs;
            this.ranges = ranges;
            this.inputs = List.of(Ing.of(outputs.get(0)));
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public int width() {
            return 196;
        }

        @Override
        public int height() {
            return 24 + ranges.size() * 13;
        }

        @Override
        public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            Stack block = outputs.get(0);
            boolean hover = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
            theme.slot(g, x, y, 18, hover);
            block.render(g, x + 1, y + 1);
            Draw.text(g, Draw.ellipsize(block.name().getString(), 120), x + 23, y + 5, theme.c("text.body"));
            int by = y + 24;
            int barX = x, barW = 74;
            for (Range r : ranges) {
                // шкала всей высоты мира, на ней отрезок, где встречается руда
                Draw.round(g, barX, by + 3, barW, 4, 2f, theme.c("slot.bg"));
                float a = (r.min - WORLD_MIN) / (float) (WORLD_MAX - WORLD_MIN), c = (r.max - WORLD_MIN) / (float) (WORLD_MAX - WORLD_MIN);
                Draw.round(g, barX + a * barW, by + 3, Math.max(2f, (c - a) * barW), 4, 2f, theme.c("accent"));
                // уровень моря для ориентира
                Draw.rect(g, barX + Math.round((63 - WORLD_MIN) / (float) (WORLD_MAX - WORLD_MIN) * barW), by + 1, 1, 8, theme.c("text.dim"));
                String text = Component.translatable("ryscope.ore.range", r.minLabel, r.maxLabel).getString();
                String freq = r.perChunk >= 1f
                    ? Component.translatable("ryscope.ore.per_chunk", trim(r.perChunk)).getString()
                    : Component.translatable("ryscope.ore.rare", Math.round(1f / Math.max(0.0001f, r.perChunk))).getString();
                Draw.textScaled(g, text + " · " + freq, barX + barW + 5, by + 2, 0.75f, theme.c("text.muted"));
                by += 13;
            }
            for (int i = 1; i < Math.min(outputs.size(), 4); i++) {
                int sx = x + 196 - i * 14, sy = y + 3;
                g.pose().pushPose();
                g.pose().translate(sx, sy, 0);
                g.pose().scale(0.75f, 0.75f, 1f);
                outputs.get(i).render(g, 0, 0);
                g.pose().popPose();
            }
        }

        private static String trim(float v) {
            return v == Math.rint(v) ? Integer.toString((int) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
        }

        @Override
        public Stack stackAt(int x, int y, int mouseX, int mouseY) {
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) return outputs.get(0);
            for (int i = 1; i < Math.min(outputs.size(), 4); i++) {
                int sx = x + 196 - i * 14, sy = y + 3;
                if (mouseX >= sx && mouseX < sx + 12 && mouseY >= sy && mouseY < sy + 12) return outputs.get(i);
            }
            return null;
        }

        @Override
        public List<Ing> inputs() {
            return inputs;
        }

        @Override
        public List<Stack> outputs() {
            return outputs;
        }

        @Override
        public boolean isCrafting() {
            return false;
        }
    }
}
