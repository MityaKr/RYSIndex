package works.repka.rysindex.content;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.ItemRes;
import works.repka.rysindex.runtime.Snapshot;

/**
 * Торговля жителей: что и за что продаёт каждая профессия на каждом уровне.
 * Берётся из таблиц игры на клиенте. В одиночной игре видны и сделки, добавленные модами;
 * на выделенном сервере клиент знает только ванильные таблицы.
 * Случайные сделки (зачарованные книги, крашеная броня) показаны одним примером.
 */
public final class TradeContent {
    public static final ResourceLocation CATEGORY = ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "trades");

    private TradeContent() {}

    public static void provide(Snapshot.Builder b) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        Category category = b.category(new Category(CATEGORY, Component.translatable("rysindex.category.trades"),
            ItemRes.of(new ItemStack(Items.EMERALD)), 24));
        RandomSource random = RandomSource.create(42L);
        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> e : VillagerTrades.TRADES.entrySet()) {
            ResourceLocation key = BuiltInRegistries.VILLAGER_PROFESSION.getKey(e.getKey());
            String prof = Component.translatable("entity." + key.getNamespace() + ".villager." + key.getPath()).getString();
            add(b, category, e.getValue(), prof, player, random, true);
        }
        add(b, category, VillagerTrades.WANDERING_TRADER_TRADES, Component.translatable("entity.minecraft.wandering_trader").getString(), player, random, false);
    }

    private static void add(Snapshot.Builder b, Category category, Int2ObjectMap<VillagerTrades.ItemListing[]> levels, String who,
                            LocalPlayer player, RandomSource random, boolean showLevel) {
        for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> level : levels.int2ObjectEntrySet()) {
            String label = who;
            if (showLevel) label += " · " + Component.translatable("merchant.level." + level.getIntKey()).getString();
            for (VillagerTrades.ItemListing listing : level.getValue()) {
                try {
                    MerchantOffer offer = listing.getOffer(player, random);
                    if (offer == null || offer.getResult().isEmpty() || offer.getBaseCostA().isEmpty()) continue;
                    NativeRecipe r = new NativeRecipe(NativeRecipe.Kind.OTHER, null, 150, 40);
                    r.crafting = false;
                    r.input(Ing.of(ItemRes.of(offer.getBaseCostA())), 0, 2);
                    if (!offer.getCostB().isEmpty()) r.input(Ing.of(ItemRes.of(offer.getCostB())), 20, 2);
                    r.arrow(46, 6, 22);
                    r.output(ItemRes.of(offer.getResult()), 76, 0, true);
                    r.text(label, 0, 31);
                    b.recipe(category, r);
                } catch (Throwable ignored) {
                    // сделке нужен настоящий житель или серверный мир: пропускаем
                }
            }
        }
    }
}
