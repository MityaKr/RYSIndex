package works.repka.rysindex.neoforge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.content.LinkCardView;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.ui.RecipeScreen;
import works.repka.ryscore.links.ItemLinks;
import works.repka.ryscore.links.ItemLinks.Action;
import works.repka.ryscore.links.ItemLinks.Card;
import works.repka.ryscore.links.ItemLinks.Group;
import works.repka.ryscore.links.ItemLinks.Mention;
import works.repka.ryscore.links.ItemLinks.Part;
import works.repka.ryscore.links.ItemLinks.State;

/**
 * Тестовый поставщик сведений для сценария {@code links}: вместо книги RYSQuests. Осколок эха играет «Осколок Врат»
 * из макетов: закрытая поставка, открытая поставка команды, награда квеста, продажа и три упоминания для чипа.
 * Тексты как у книги, чтобы снимки сравнивались с макетами.
 */
final class AutoTestLinks {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "autotest_links");
    static final ResourceLocation BROKEN = ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "autotest_broken");
    static final Group SHOP = new Group(ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "autotest_shop"), Component.literal("Магазин"),
        new ItemStack(Items.EMERALD), 0);
    static final Group REWARDS = new Group(ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "autotest_rewards"), Component.literal("Награды квестов"),
        new ItemStack(Items.WRITABLE_BOOK), 1);

    /** Последнее выполненное действие поставщика: автотест проверяет, что клик дошёл. */
    static volatile String lastAction = "";

    private AutoTestLinks() {}

    static final class Provider implements ItemLinks.Provider {
        int version = 1;
        /** Сколько слитков незерита команда уже доставила: растёт в шаге «версия». */
        long delivered = 1240;

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public int version() {
            return version;
        }

        private static Action action(String label, String tag) {
            return new Action(Component.literal(label), () -> {
                lastAction = tag;
                RYSIndex.LOG.info("[links] действие поставщика: {}", tag);
            });
        }

        @Override
        public List<Card> cards(ItemStack stack, ItemLinks.Role role) {
            if (!stack.is(Items.ECHO_SHARD)) return List.of();
            List<Card> out = new ArrayList<>();
            if (role == ItemLinks.Role.GIVES) {
                out.add(Card.builder(SHOP, id("trophy_locked"), Component.literal("Осколок эха"))
                    .subtitle(Component.literal("закрыто")).state(State.LOCKED)
                    .give(Part.item(new ItemStack(Items.ECHO_SHARD), 1))
                    .takes(Component.literal("Нужно доставить"), List.of(Part.item(new ItemStack(Items.NETHERITE_INGOT), 1984),
                        Part.item(new ItemStack(Items.DIAMOND), 192), Part.item(new ItemStack(Items.EMERALD), 14),
                        Part.coins(Component.literal("1 000 монет"), Component.literal("при получении товара"), 1000)))
                    .lock(Component.literal("Получи первый осколок эха в древнем городе"), action("Квест «Первый осколок»", "lock_link"))
                    .open(action("Открыть в магазине", "open_shop_locked")).build());
                long pct = Math.round(100.0 * (Math.min(delivered, 1984) + 192 + 3) / (1984 + 192 + 14));
                out.add(Card.builder(SHOP, id("trophy_open"), Component.literal("Осколок эха"))
                    .subtitle(Component.literal("поставка команды · " + pct + " %")).state(State.ACTIVE)
                    .give(Part.item(new ItemStack(Items.ECHO_SHARD), 1))
                    .takes(Component.literal("Нужно доставить"), List.of(Part.item(new ItemStack(Items.NETHERITE_INGOT), 1984, delivered),
                        Part.item(new ItemStack(Items.DIAMOND), 192, 192), Part.item(new ItemStack(Items.EMERALD), 14, 3),
                        Part.coins(Component.literal("1 000 монет"), Component.literal("при получении товара"), 1000)))
                    .note(Component.literal("Команда доставила " + (delivered >= 1984 ? 2 : 1) + " из 3 позиций"))
                    .open(action("Открыть в магазине", "open_shop")).build());
                out.add(Card.builder(REWARDS, id("quest_first_shard"), Component.literal("Первый осколок"))
                    .icon(new ItemStack(Items.WRITABLE_BOOK)).subtitle(Component.literal("Творение · эпоха IX · сделан")).state(State.DONE)
                    .gives(Component.literal("Награда"), List.of(Part.item(new ItemStack(Items.ECHO_SHARD), 2),
                        Part.coins(Component.literal("250 монет"), null, 250)))
                    .open(action("Открыть в книге", "open_quest")).build());
            } else {
                out.add(Card.builder(SHOP, id("sell_shard"), Component.literal("250 монет"))
                    .icon(new ItemStack(Items.GOLD_NUGGET)).subtitle(Component.literal("Скупка · осталось 2 из 5 сегодня")).state(State.OPEN)
                    .takes(Component.literal("Цена"), List.of(Part.item(new ItemStack(Items.ECHO_SHARD), 1)))
                    .open(action("Открыть в магазине", "open_sell")).build());
            }
            return out;
        }

        @Override
        public ItemLinks.Mentions mentions(ItemStack stack) {
            if (!stack.is(Items.ECHO_SHARD)) return null;
            Component tasks = Component.literal("Нужен в задачах"), shop = Component.literal("Магазин");
            return new ItemLinks.Mentions(Component.literal("2 квеста"), Component.literal("В книге квестов"), List.of(
                new Mention(tasks, new ItemStack(Items.ECHO_SHARD), Component.literal("Первый осколок"),
                    Component.literal("Творение · эпоха IX · побочный"), State.OPEN, action("", "mention_first")),
                new Mention(tasks, new ItemStack(Items.AMETHYST_SHARD), Component.literal("Осколки вечности"),
                    Component.literal("Творение · эпоха IX"), State.ACTIVE, action("", "mention_eternity")),
                new Mention(shop, ItemStack.EMPTY, Component.literal("Трофей: осколок эха"),
                    Component.literal("Поставка команды · 45 %"), State.LOCKED, action("", "mention_shop"))));
        }

        @Override
        public Component count(Group group, int count) {
            if (!group.equals(SHOP)) return null;
            int a = count % 100, b = count % 10;
            String word = a >= 11 && a <= 14 ? "сделок" : b == 1 ? "сделка" : b >= 2 && b <= 4 ? "сделки" : "сделок";
            return Component.literal(count + " " + word);
        }

        private static ResourceLocation id(String path) {
            return ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "autotest/" + path);
        }
    }

    /** Поставщик, который падает на каждом запросе: RYSIndex пишет ошибку в лог один раз и работает дальше. */
    static final class Broken implements ItemLinks.Provider {
        @Override
        public ResourceLocation id() {
            return BROKEN;
        }

        @Override
        public int version() {
            return 0;
        }

        @Override
        public List<Card> cards(ItemStack stack, ItemLinks.Role role) {
            throw new IllegalStateException("автотест: поставщик сломан");
        }

        @Override
        public ItemLinks.Mentions mentions(ItemStack stack) {
            throw new IllegalStateException("автотест: поставщик сломан");
        }
    }

    static final Provider PROVIDER = new Provider();

    static void install() {
        lastAction = "";
        ItemLinks.register(PROVIDER);
    }

    static void uninstall() {
        ItemLinks.unregister(ID);
        ItemLinks.unregister(BROKEN);
    }

    static RecipeScreen screen() {
        return Minecraft.getInstance().screen instanceof RecipeScreen rs ? rs : null;
    }

    static boolean isShop(Category c) {
        return c.id.equals(SHOP.id());
    }

    static boolean isRewards(Category c) {
        return c.id.equals(REWARDS.id());
    }

    /** Карточки поставщика на экране. */
    static List<LinkCardView> cards() {
        List<LinkCardView> out = new ArrayList<>();
        RecipeScreen rs = screen();
        if (rs == null) return out;
        for (RecipeScreen.Placed p : rs.placed()) if (p.recipe() instanceof LinkCardView v) out.add(v);
        return out;
    }

    /** Разделы, карточки и чип одной строкой в лог. */
    static void log(String tag) {
        RecipeScreen rs = screen();
        if (rs == null) {
            RYSIndex.LOG.info("[links] FAIL {}: карточка не открыта ({})", tag, Minecraft.getInstance().screen);
            return;
        }
        RecipeScreen.SectionsForTest s = rs.sectionsForTest();
        RecipeScreen.StripForTest strip = rs.stripForTest();
        RecipeScreen.LinksForTest l = rs.linksForTest();
        StringBuilder cards = new StringBuilder();
        for (LinkCardView v : cards()) {
            cards.append(" [").append(v.card().id().getPath()).append(' ').append(v.card().state()).append(' ').append(v.width()).append('×').append(v.height());
            if (!v.card().takes().isEmpty()) cards.append(" есть ").append(v.card().takes().get(0).have());
            cards.append(']');
        }
        RYSIndex.LOG.info("[links] {}: раздел {}, счётчики {}, категория «{}» счётчик «{}», карточки{}, чипы {}, список {} строк {}", tag, s.current(), s.counts(),
            strip.title(), strip.pager(), cards, l.chips(), l.open(), l.rows());
    }

    static void check(String tag, boolean ok, String what) {
        RYSIndex.LOG.info("[links] {} {}: {}", ok ? "PASS" : "FAIL", tag, what);
    }
}
