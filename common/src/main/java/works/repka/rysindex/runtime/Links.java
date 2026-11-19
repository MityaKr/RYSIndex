package works.repka.rysindex.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.content.LinkCardView;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.ItemRes;
import works.repka.rysindex.model.RecipeView;
import works.repka.rysindex.model.Stack;
import works.repka.ryscore.links.ItemLinks;

/**
 * Сведения о предмете от модов семьи ({@link ItemLinks} ядра): карточки поставщиков подмешиваются в ответы
 * {@link Lookup#find} на лету, как источник JEI, а упоминания идут в чип шапки карточки предмета. В снимок
 * ничего не кладётся: книга и прогресс меняются, снимок неизменяем.
 */
public final class Links {
    /** Поставщики, ошибка которых уже записана в лог: пишется один раз на поставщика и вид запроса. */
    private static final Set<String> REPORTED = Collections.synchronizedSet(new HashSet<>());
    /** Категория на каждую группу поставщика: одна и та же между запросами, равенство по id. */
    private static final Map<ResourceLocation, LinkCategory> CATEGORIES = new HashMap<>();

    private Links() {}

    /** Категория из группы поставщика. Ранг в {@link Lookup}: после машин, перед торговлей жителей и лутом. */
    public static final class LinkCategory extends Category {
        public final ItemLinks.Group group;
        public final ResourceLocation provider;

        LinkCategory(ItemLinks.Group group, ResourceLocation provider) {
            super(group.id(), group.title(), group.icon().isEmpty() ? null : ItemRes.of(group.icon()), group.order());
            this.group = group;
            this.provider = provider;
        }
    }

    /** Поставщик и его упоминания для чипа. */
    public record Shown(ResourceLocation provider, ItemLinks.Mentions mentions) {}

    public static int version() {
        try {
            return ItemLinks.version();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean any() {
        return !ItemLinks.providers().isEmpty();
    }

    /** Карточки поставщиков отвечают только в главном потоке клиента (правило контракта). */
    private static boolean onMainThread() {
        try {
            return Minecraft.getInstance().isSameThread();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ответ поставщика или запасное значение; ошибка пишется в лог один раз. */
    static <T> T safely(ResourceLocation provider, String what, Supplier<T> call, T fallback) {
        try {
            T out = call.get();
            return out == null ? fallback : out;
        } catch (Throwable t) {
            if (REPORTED.add(provider + "/" + what)) RYSIndex.LOG.warn("Поставщик сведений {} не ответил ({})", provider, what, t);
            return fallback;
        }
    }

    /**
     * Группы карточек для ресурса: {@code RECIPES} спрашивает «как получить» (карточки уходят в «Источники»),
     * {@code USES} «где тратится». Пусто для не-предметов, без поставщиков и вне главного потока.
     */
    public static List<Lookup.Group> groups(Stack stack, Lookup.Mode mode) {
        ItemStack item = stack.item();
        if (item.isEmpty()) return List.of();
        List<ItemLinks.Provider> providers = ItemLinks.providers();
        if (providers.isEmpty() || !onMainThread()) return List.of();
        ItemLinks.Role role = mode == Lookup.Mode.RECIPES ? ItemLinks.Role.GIVES : ItemLinks.Role.TAKES;
        ItemStack query = item.copyWithCount(1);
        List<Lookup.Group> out = new ArrayList<>();
        for (ItemLinks.Provider p : providers) {
            ResourceLocation id = safely(null, "id", p::id, null);
            if (id == null) continue;
            List<ItemLinks.Card> cards = safely(id, "cards", () -> p.cards(query, role), List.<ItemLinks.Card>of());
            for (Map.Entry<ItemLinks.Group, List<ItemLinks.Card>> e : LinkMath.byGroup(cards, ItemLinks.Card::group).entrySet()) {
                LinkCategory category = category(e.getKey(), id);
                List<RecipeView> views = new ArrayList<>(e.getValue().size());
                for (ItemLinks.Card card : e.getValue()) views.add(new LinkCardView(card, p));
                out.add(new Lookup.Group(category, views));
            }
        }
        out.sort(java.util.Comparator.comparingInt(g -> g.category().order));
        return out;
    }

    private static synchronized LinkCategory category(ItemLinks.Group group, ResourceLocation provider) {
        LinkCategory known = CATEGORIES.get(group.id());
        // название и значок могли смениться вместе с языком: категория пересоздаётся, равенство по id остаётся
        if (known != null && known.group.equals(group)) return known;
        LinkCategory made = new LinkCategory(group, provider);
        CATEGORIES.put(group.id(), made);
        return made;
    }

    /** Упоминания предмета от всех поставщиков, у кого они есть. */
    public static List<Shown> mentions(Stack stack) {
        ItemStack item = stack.item();
        if (item.isEmpty() || !onMainThread()) return List.of();
        List<ItemLinks.Provider> providers = ItemLinks.providers();
        if (providers.isEmpty()) return List.of();
        ItemStack query = item.copyWithCount(1);
        List<Shown> out = new ArrayList<>();
        for (ItemLinks.Provider p : providers) {
            ResourceLocation id = safely(null, "id", p::id, null);
            if (id == null) continue;
            ItemLinks.Mentions m = safely(id, "mentions", () -> p.mentions(query), null);
            if (m != null && !m.isEmpty()) out.add(new Shown(id, m));
        }
        return out;
    }

    /** Счётчик категории словами поставщика («3 сделки») или null. */
    @Nullable
    public static String count(Category category, int count) {
        if (!(category instanceof LinkCategory lc)) return null;
        for (ItemLinks.Provider p : ItemLinks.providers()) {
            ResourceLocation id = safely(null, "id", p::id, null);
            if (id == null || !id.equals(lc.provider)) continue;
            net.minecraft.network.chat.Component c = safely(id, "count", () -> p.count(lc.group, count), null);
            return c == null ? null : c.getString();
        }
        return null;
    }

    /** Выполнить действие поставщика; ошибка в лог, окно RYSIndex не падает. */
    public static void run(@Nullable ItemLinks.Action action) {
        if (action == null) return;
        safely(null, "action", () -> {
            action.run().run();
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }
}
