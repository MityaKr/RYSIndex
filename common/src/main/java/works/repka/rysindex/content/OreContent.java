package works.repka.rysindex.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.content.OreData.Range;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.ItemRes;
import works.repka.rysindex.model.RecipeView;
import works.repka.rysindex.model.Stack;
import works.repka.rysindex.runtime.OreSource;
import works.repka.rysindex.runtime.Snapshot;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Type;

/**
 * Генерация руд: на каких высотах и как часто встречается блок. Откуда берутся данные, решает {@link OreSource}:
 * одиночная игра, сервер с RYSIndex или кеш одиночной игры. Если данных нет совсем, у руд из тега {@code c:ores}
 * в «Источниках» стоит строка о том, как их получить.
 */
public final class OreContent {
    public static final ResourceLocation CATEGORY = ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "ore_gen");
    private static final int WORLD_MIN = OreData.WORLD_MIN, WORLD_MAX = OreData.WORLD_MAX;
    private static final TagKey<Item> ORES = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "ores"));
    /** Кегль подписей карточки (как у строк диапазонов) и шаг строки. */
    private static final float NOTE_PX = 11.5f;
    private static final int NOTE_LINE = 10;

    private OreContent() {}

    public static void provide(Snapshot.Builder b) {
        OreSource.Result source = OreSource.current();
        if (source.origin() == OreSource.Origin.NONE) {
            provideHints(b);
            return;
        }
        if (source.ores().isEmpty()) return;
        boolean cached = source.origin() == OreSource.Origin.CACHE;
        Category category = category(b);
        for (Map.Entry<String, List<Range>> e : source.ores().entrySet()) {
            ResourceLocation blockId = ResourceLocation.tryParse(e.getKey());
            if (blockId == null) continue;
            Block block = BuiltInRegistries.BLOCK.get(blockId);
            if (block == Blocks.AIR) continue;
            Item item = block.asItem();
            if (item == Items.AIR) continue;
            List<Stack> outputs = new ArrayList<>();
            outputs.add(ItemRes.of(new ItemStack(item)));
            SourceView drops = LootContent.forBlock(block);
            if (drops != null) for (SourceView.Drop d : drops.drops()) outputs.add(d.stack());
            b.recipe(category, new OreView(blockId, outputs, e.getValue(), cached, source.biomes().get(e.getKey())));
        }
    }

    private static Category category(Snapshot.Builder b) {
        return b.category(new Category(CATEGORY, Component.translatable("rysindex.category.ore_gen"),
            ItemRes.of(new ItemStack(Items.DIAMOND_ORE)), 25));
    }

    /** Данных генерации нет: у руд из общего тега вместо пустоты строка, как их получить. */
    private static void provideHints(Snapshot.Builder b) {
        Category category = null;
        for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(ORES)) {
            Item item = h.value();
            if (item == Items.AIR) continue;
            if (category == null) category = category(b);
            b.recipe(category, new OreHint(BuiltInRegistries.ITEM.getKey(item), ItemRes.of(new ItemStack(item))));
        }
    }

    private static ResourceLocation viewId(ResourceLocation blockId) {
        return ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "ore/" + blockId.getNamespace() + "/" + blockId.getPath());
    }

    private static boolean overSlot(int x, int y, int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
    }

    /** Шапка карточки: блок на плашке слота и его название. */
    private static void header(GuiGraphics g, Theme theme, Stack block, int x, int y, int mouseX, int mouseY) {
        theme.slot(g, x, y, 18, overSlot(x, y, mouseX, mouseY));
        block.render(g, x + 1, y + 1);
        Draw.text(g, Draw.ellipsize(block.name().getString(), 120), x + 23, y + 5, theme.c("text.body"));
    }

    /** Карточка руды: шкала высот мира с отмеченными диапазонами и частотой. */
    public static final class OreView implements RecipeView {
        private final ResourceLocation id;
        private final List<Stack> outputs;
        private final List<Range> ranges;
        private final List<Ing> inputs;
        /** Данные из кеша одиночной игры: под диапазонами подпись об этом. */
        private final boolean cached;
        /** Биомы руды, если она есть не везде: строка «Биомы» и «На карте» (подсветка RYSMap). */
        @org.jetbrains.annotations.Nullable
        private final OreBiomes.Where where;

        OreView(ResourceLocation blockId, List<Stack> outputs, List<Range> ranges, boolean cached, @org.jetbrains.annotations.Nullable OreBiomes.Where where) {
            this.id = viewId(blockId);
            this.outputs = outputs;
            this.ranges = ranges;
            this.inputs = List.of(Ing.of(outputs.get(0)));
            this.cached = cached;
            this.where = where;
        }

        /** Подпись ссылки на карту и её ширина; ссылки нет без RYSMap. */
        private static String mapLink() {
            return works.repka.ryscore.map.MapApi.get().present() ? Component.translatable("rysindex.ore.on_map").getString() : "";
        }

        /** Строка биомов: «Биомы: Пустоши, Лесистые пустоши» и измерение, если руда не в Верхнем мире. */
        private String biomeLine() {
            List<String> names = new ArrayList<>();
            for (String b : where.biomes()) {
                ResourceLocation rl = ResourceLocation.tryParse(b);
                names.add(rl == null ? b : Component.translatable("biome." + rl.getNamespace() + "." + rl.getPath()).getString());
            }
            return Component.translatable("rysindex.ore.biomes", String.join(", ", names)).getString();
        }

        /** Прямоугольник ссылки «На карте» {x, y, w, h} или null. */
        @org.jetbrains.annotations.Nullable
        private int[] linkRect(int x, int y) {
            String link = where == null ? "" : mapLink();
            if (link.isEmpty()) return null;
            int w = (int) Math.ceil(Draw.labelWidth(link, NOTE_PX, Type.Weight.MEDIUM));
            int ly = y + 24 + ranges.size() * 13;
            return new int[] {x + width() - w, ly, w, NOTE_LINE};
        }

        /** Карта RYSMap с подсветкой биомов руды, открытая на игроке. */
        private void showOnMap() {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (where == null || mc.player == null) return;
            List<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>> keys = new ArrayList<>();
            for (String b : where.biomes()) {
                ResourceLocation rl = ResourceLocation.tryParse(b);
                if (rl != null) keys.add(net.minecraft.resources.ResourceKey.create(Registries.BIOME, rl));
            }
            ResourceLocation dimId = ResourceLocation.tryParse(where.dim());
            var dim = dimId == null ? mc.player.level().dimension() : net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, dimId);
            Component name = outputs.get(0).name();
            var api = works.repka.ryscore.map.MapApi.get();
            api.highlight(works.repka.ryscore.map.MapApi.Highlight.builder(id, Component.translatable("rysindex.ore.map_title", name)).biomes(keys).build());
            api.open(works.repka.ryscore.map.MapApi.Target.builder(dim, mc.player.getX(), mc.player.getY(), mc.player.getZ()).label(name).build());
        }

        @Override
        public boolean mouseClicked(int x, int y, double mouseX, double mouseY, int button) {
            int[] r = linkRect(x, y);
            if (button != 0 || r == null || mouseX < r[0] || mouseX >= r[0] + r[2] || mouseY < r[1] || mouseY >= r[1] + r[3]) return false;
            showOnMap();
            return true;
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
            return 24 + ranges.size() * 13 + (where != null ? NOTE_LINE : 0) + (cached ? NOTE_LINE : 0);
        }

        @Override
        public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick) {
            header(g, theme, outputs.get(0), x, y, mouseX, mouseY);
            int by = y + 24;
            int barX = x, barW = 74;
            for (Range r : ranges) {
                // шкала всей высоты мира, на ней отрезок, где встречается руда
                Draw.round(g, barX, by + 3, barW, 4, 2f, theme.c("slot.bg"));
                float a = (r.min() - WORLD_MIN) / (float) (WORLD_MAX - WORLD_MIN), c = (r.max() - WORLD_MIN) / (float) (WORLD_MAX - WORLD_MIN);
                Draw.round(g, barX + a * barW, by + 3, Math.max(2f, (c - a) * barW), 4, 2f, theme.c("accent"));
                // уровень моря для ориентира
                Draw.rect(g, barX + Math.round((63 - WORLD_MIN) / (float) (WORLD_MAX - WORLD_MIN) * barW), by + 1, 1, 8, theme.c("text.dim"));
                String text = Component.translatable("rysindex.ore.range", r.lo().label(), r.hi().label()).getString();
                String freq = r.perChunk() >= 1f
                    ? Component.translatable("rysindex.ore.per_chunk", trim(r.perChunk())).getString()
                    : Component.translatable("rysindex.ore.rare", Math.round(1f / Math.max(0.0001f, r.perChunk()))).getString();
                Draw.textScaled(g, text + " · " + freq, barX + barW + 5, by + 2, 0.75f, theme.c("text.muted"));
                by += 13;
            }
            if (where != null) {
                int[] link = linkRect(x, y);
                int room = width() - (link != null ? link[2] + 6 : 0);
                String line = Draw.ellipsize(biomeLine(), room, NOTE_PX, Type.Weight.REGULAR);
                Draw.label(g, line, barX, by + NOTE_LINE / 2f, NOTE_PX, Type.Weight.REGULAR, theme.c("text.muted"));
                if (link != null) {
                    boolean hot = mouseX >= link[0] && mouseX < link[0] + link[2] && mouseY >= link[1] && mouseY < link[1] + link[3];
                    Draw.label(g, mapLink(), link[0], by + NOTE_LINE / 2f, NOTE_PX, Type.Weight.MEDIUM, hot ? theme.c("text.primary") : theme.c("accent"));
                }
                by += NOTE_LINE;
            }
            if (cached) {
                String note = Draw.ellipsize(Component.translatable("rysindex.ore.cached").getString(), width(), NOTE_PX, Type.Weight.REGULAR);
                Draw.label(g, note, barX, by + NOTE_LINE / 2f, NOTE_PX, Type.Weight.REGULAR, theme.c("text.dim"));
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
            if (overSlot(x, y, mouseX, mouseY)) return outputs.get(0);
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

    /** Руда без данных генерации: блок и строка о том, где эти данные появятся. */
    public static final class OreHint implements RecipeView {
        private static final int MAX_LINES = 3;
        private final ResourceLocation id;
        private final List<Stack> outputs;
        /** Перенос строки по текущему языку: пересчитывается, если текст сменился. */
        private String wrappedFor;
        private List<String> lines = List.of();

        OreHint(ResourceLocation itemId, Stack block) {
            this.id = viewId(itemId);
            this.outputs = List.of(block);
        }

        private List<String> lines() {
            String text = Component.translatable("rysindex.ore.unknown").getString();
            if (!text.equals(wrappedFor)) {
                lines = Draw.wrapLabel(text, width(), NOTE_PX, Type.Weight.REGULAR, MAX_LINES);
                wrappedFor = text;
            }
            return lines;
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
            return 24 + lines().size() * NOTE_LINE;
        }

        @Override
        public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick) {
            header(g, theme, outputs.get(0), x, y, mouseX, mouseY);
            int by = y + 24;
            for (String line : lines()) {
                Draw.label(g, line, x, by + NOTE_LINE / 2f, NOTE_PX, Type.Weight.REGULAR, theme.c("text.muted"));
                by += NOTE_LINE;
            }
        }

        @Override
        public Stack stackAt(int x, int y, int mouseX, int mouseY) {
            return overSlot(x, y, mouseX, mouseY) ? outputs.get(0) : null;
        }

        @Override
        public List<Ing> inputs() {
            return List.of();
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
