package works.repka.ryscope.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Snapshot;

/**
 * Источники предметов: дроп мобов, добыча с блоков, содержимое сундуков и прочий лут.
 * Встроенная замена отдельным модам вроде Just Enough Resources.
 */
public final class LootContent {
    public static final ResourceLocation MOBS = ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "mob_drops");
    public static final ResourceLocation BLOCKS = ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "block_drops");
    public static final ResourceLocation CHESTS = ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "chest_loot");
    public static final ResourceLocation OTHER = ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "other_loot");

    private static volatile Map<EntityType<?>, SourceView> byEntity = Map.of();
    private static volatile Map<Block, SourceView> byBlock = Map.of();

    private LootContent() {}

    @Nullable
    public static SourceView forEntity(EntityType<?> type) {
        return byEntity.get(type);
    }

    @Nullable
    public static SourceView forBlock(Block block) {
        return byBlock.get(block);
    }

    public static void provide(Snapshot.Builder b, List<LootData.Table> tables) {
        if (tables.isEmpty()) {
            byEntity = Map.of();
            byBlock = Map.of();
            return;
        }
        Map<String, LootData.Table> byId = new HashMap<>();
        for (LootData.Table t : tables) byId.put(t.id(), t);
        Set<String> used = new HashSet<>();

        Category mobs = b.category(new Category(MOBS, Component.translatable("ryscope.category.mob_drops"), ItemRes.of(new ItemStack(Items.IRON_SWORD)), 20));
        Category blocks = b.category(new Category(BLOCKS, Component.translatable("ryscope.category.block_drops"), ItemRes.of(new ItemStack(Items.IRON_PICKAXE)), 21));
        Category chests = b.category(new Category(CHESTS, Component.translatable("ryscope.category.chest_loot"), ItemRes.of(new ItemStack(Items.CHEST)), 22));
        Category other = b.category(new Category(OTHER, Component.translatable("ryscope.category.other_loot"), ItemRes.of(new ItemStack(Items.FISHING_ROD)), 23));

        Map<EntityType<?>, SourceView> entityMap = new HashMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            String key = type.getDefaultLootTable().location().toString();
            LootData.Table table = byId.get(key);
            if (table == null) continue;
            used.add(key);
            List<SourceView.Drop> drops = drops(table, null);
            if (drops.isEmpty()) continue;
            SpawnEggItem egg = SpawnEggItem.byId(type);
            Stack icon = egg != null ? ItemRes.of(new ItemStack(egg)) : ItemRes.of(new ItemStack(Items.SKELETON_SKULL));
            SourceView view = new SourceView(table(key), icon, egg != null, type.getDescription(), drops);
            entityMap.put(type, view);
            b.recipe(mobs, view);
        }

        Map<Block, SourceView> blockMap = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            String key = block.getLootTable().location().toString();
            LootData.Table table = byId.get(key);
            if (table == null) continue;
            used.add(key);
            Item self = block.asItem();
            // «блок выпадает сам» никому не интересно: показываем только то, что отличается
            List<SourceView.Drop> drops = drops(table, self == Items.AIR ? null : self);
            if (drops.isEmpty()) continue;
            Stack icon = self == Items.AIR ? null : ItemRes.of(new ItemStack(self));
            SourceView view = new SourceView(table(key), icon, icon != null, block.getName(), drops);
            blockMap.put(block, view);
            b.recipe(blocks, view);
        }

        for (LootData.Table table : tables) {
            if (used.contains(table.id())) continue;
            ResourceLocation id = table(table.id());
            String path = id.getPath();
            if (path.startsWith("entities/") || path.startsWith("blocks/")) continue;
            List<SourceView.Drop> drops = drops(table, null);
            if (drops.isEmpty()) continue;
            boolean chest = path.startsWith("chests/") || path.startsWith("pots/") || path.startsWith("dispensers/") || path.startsWith("spawners/");
            Stack icon = ItemRes.of(new ItemStack(chest ? Items.CHEST : path.contains("fishing") ? Items.FISHING_ROD
                : path.startsWith("archaeology/") ? Items.BRUSH : Items.BUNDLE));
            b.recipe(chest ? chests : other, new SourceView(id, icon, false, Component.literal(pretty(path)), drops));
        }

        byEntity = entityMap;
        byBlock = blockMap;
    }

    private static ResourceLocation table(String id) {
        return ResourceLocation.parse(id);
    }

    private static List<SourceView.Drop> drops(LootData.Table table, @Nullable Item skip) {
        List<SourceView.Drop> out = new ArrayList<>();
        for (LootData.Drop d : table.drops()) {
            if (d.tag()) {
                TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(d.item()));
                // выпадает один предмет из тега: шанс делится между всеми его предметами
                int size = 0;
                for (Holder<Item> ignored : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) size++;
                int n = 0;
                for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                    if (n++ >= 8) break;
                    if (h.value() != skip) {
                        out.add(new SourceView.Drop(ItemRes.of(new ItemStack(h.value())), d.min(), d.max(), d.chance() / Math.max(1, size), d.note()));
                    }
                }
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(d.item()));
            if (item == Items.AIR || item == skip) continue;
            out.add(new SourceView.Drop(ItemRes.of(new ItemStack(item)), d.min(), d.max(), d.chance(), d.note()));
        }
        out.sort((a, c) -> Float.compare(c.chance(), a.chance()));
        return out;
    }

    /** «chests/village/village_weaponsmith» → «Village Weaponsmith». */
    private static String pretty(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char c : last.toCharArray()) {
            sb.append(up ? Character.toUpperCase(c) : c);
            up = c == ' ';
        }
        return sb.toString();
    }
}
