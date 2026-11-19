package works.repka.rysindex.neoforge.ae2;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;
import works.repka.rysindex.RYSIndex;

/**
 * Замещение в шаблонах обработки AE2. У AE2 оно есть только у шаблонов крафта; здесь шаблон обработки
 * запоминает для каждой ячейки входа допустимые варианты из рецепта, и автокрафт берёт любой из них.
 * Варианты приходят с клиента, когда шаблон заполняется через «+» RYSIndex; у входов, положенных руками, их нет.
 * Этот класс не трогает классы AE2: без AE2 он просто не используется.
 */
public final class ProcessingSubstitution {
    private ProcessingSubstitution() {}

    /** Сколько групп и вариантов в группе принимается с клиента: больше в шаблон обработки не помещается. */
    public static final int MAX_GROUPS = 81, MAX_VARIANTS = 512;

    public static final Codec<List<List<Item>>> CODEC = BuiltInRegistries.ITEM.byNameCodec().listOf().listOf();
    public static final StreamCodec<RegistryFriendlyByteBuf, List<List<Item>>> STREAM_CODEC =
        ByteBufCodecs.registry(Registries.ITEM).apply(ByteBufCodecs.list()).apply(ByteBufCodecs.list());

    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, RYSIndex.MOD_ID);
    /** Для каждой ячейки входа шаблона: допустимые предметы, пустой список — замещения нет. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<List<Item>>>> ALTERNATIVES =
        COMPONENTS.registerComponentType("processing_alternatives", b -> b.persistent(CODEC).networkSynchronized(STREAM_CODEC));

    static {
        // Шаблоны, записанные до переименования мода, хранят компонент под старым id: без псевдонима AE2 не прочитает такой предмет.
        COMPONENTS.addAlias(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ryscope", "processing_alternatives"), ALTERNATIVES.getId());
    }

    /** Группы вариантов последнего разложенного рецепта, по открытому меню терминала. */
    private static final Map<AbstractContainerMenu, List<Set<Item>>> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    /** Клиент разложил рецепт в терминал шаблонов: запомнить, какие предметы взаимозаменяемы. */
    public static void remember(ServerPlayer player, List<List<Item>> groups) {
        List<Set<Item>> sets = new ArrayList<>();
        for (int i = 0; i < Math.min(groups.size(), MAX_GROUPS); i++) {
            List<Item> group = groups.get(i);
            if (group.size() < 2) continue;
            sets.add(new LinkedHashSet<>(group.subList(0, Math.min(group.size(), MAX_VARIANTS))));
        }
        PENDING.put(player.containerMenu, sets);
    }

    /**
     * Варианты по ячейкам входа: для ячейки с предметом из группы — вся группа (сначала сам предмет).
     * @return null, если ни одной ячейке замещать нечем
     */
    @Nullable
    public static List<List<Item>> alternativesFor(AbstractContainerMenu menu, List<Item> slotItems) {
        List<Set<Item>> groups = PENDING.get(menu);
        if (groups == null || groups.isEmpty()) return null;
        List<List<Item>> out = new ArrayList<>(slotItems.size());
        boolean any = false;
        for (Item item : slotItems) {
            Set<Item> variants = new LinkedHashSet<>();
            if (item != null) {
                for (Set<Item> group : groups) {
                    if (!group.contains(item)) continue;
                    variants.add(item);
                    variants.addAll(group);
                }
            }
            if (variants.size() > 1) {
                out.add(List.copyOf(variants));
                any = true;
            } else {
                out.add(List.of());
            }
        }
        if (!any) return null;
        // хвост пустых ячеек не хранится: шаблон обработки на 81 вход
        int end = out.size();
        while (end > 0 && out.get(end - 1).isEmpty()) end--;
        return List.copyOf(out.subList(0, end));
    }
}
