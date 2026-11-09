package works.repka.ryscope.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.client.Keys;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Config;
import works.repka.ryscope.runtime.Foreign;
import works.repka.ryscope.runtime.Indexer;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.SearchService;
import works.repka.ryscope.ui.Lens;
import works.repka.ryscope.ui.Overlay;
import works.repka.ryscope.ui.RecipeScreen;
import works.repka.ryscope.ui.theme.ThemeManager;

/**
 * Автотест для разработки: включается свойством {@code -Dryscope.autotest}.
 * Сам создаёт отдельный мир, проходит по экранам мода, сохраняет скриншоты в {@code run/screenshots} и закрывает игру.
 */
final class AutoTest {
    /** Отдельный мир на каждый набор модов: открытие мира с другим набором останавливается на вопросе игры. */
    private static final String WORLD = "ryscope_autotest_" + Integer.toHexString(
        ModList.get().getMods().stream().map(m -> m.getModId() + m.getVersion()).sorted().toList().hashCode());

    private record Step(String name, BooleanSupplier ready, Runnable action, int waitAfter) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index;
    private static int wait;
    private static int total;
    private static int stuck;
    private static Stack lensCard;

    private AutoTest() {}

    static void install(IEventBus bus) {
        RYScope.LOG.info("[autotest] включён, сценарий: {}", System.getProperty("ryscope.autotest"));
        if (Boolean.getBoolean("ryscope.autotest.asPlayer")) {
            // В dev-запуске NeoForge включает игровые тесты, и часть модов больших сборок от этого падает.
            // У игрока этого режима нет, поэтому в проверке «как у игрока» он выключается.
            net.minecraft.SharedConstants.IS_RUNNING_IN_IDE = false;
            System.setProperty("neoforge.enableGameTest", "false");
        }
        build();
        bus.addListener((ClientTickEvent.Post e) -> tick());
        bus.addListener((ScreenEvent.Render.Post e) -> {
            if (lensCard != null) {
                Lens.renderCard(e.getGuiGraphics(), ThemeManager.get(), lensCard, e.getScreen().width / 2 - 60, 60,
                    e.getScreen().width, e.getScreen().height);
            }
        });
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static void step(String name, BooleanSupplier ready, Runnable action, int waitAfter) {
        STEPS.add(new Step(name, ready, action, waitAfter));
    }

    private static void step(String name, Runnable action, int waitAfter) {
        step(name, () -> true, action, waitAfter);
    }

    private static void shot(String name) {
        step("clean", () -> {
            mc().getToasts().clear();
            mc().gui.getChat().clearMessages(false);
        }, 2);
        step("shot:" + name, () -> {
            String file = String.format("ryscope_%02d_%s.png", shotCounter++, name);
            Screenshot.grab(mc().gameDirectory, file, mc().getMainRenderTarget(), msg -> {});
        }, 3);
    }

    private static int shotCounter = 1;

    private static void command(String cmd) {
        if (mc().player != null) mc().player.connection.sendCommand(cmd);
    }

    private static void build() {
        // на свежей папке вместо главного меню открывается экран специальных возможностей: годится любой экран
        step("title", () -> mc().screen != null && mc().level == null && mc().getOverlay() == null && total > 40, () -> {
            mc().options.pauseOnLostFocus = false;
            mc().options.onboardAccessibility = false;
            mc().options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc().getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            if (!(mc().screen instanceof TitleScreen)) mc().setScreen(new TitleScreen());
            Config.get().searchLanguages = new ArrayList<>(List.of("en_us", "ru_ru"));
            Config.get().lastSearch = "";
            ThemeManager.select("ryscope:slate");
            writeSamples();
            if (mc().getLevelSource().levelExists(WORLD)) {
                mc().createWorldOpenFlows().openWorld(WORLD, () -> RYScope.LOG.error("[autotest] мир не открылся"));
            } else {
                LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
                    new GameRules(), WorldDataConfiguration.DEFAULT);
                mc().createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(42L, false, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    mc().screen);
            }
        }, 20);
        step("world", () -> {
            skipBackupQuestion();
            return mc().player != null && mc().level != null && mc().screen == null;
        }, () -> {}, 40);
        step("jei", () -> !ModList.get().isLoaded("jei") || Foreign.get() != null || stuck > 400, () ->
            RYScope.LOG.info("[autotest] источник JEI: {}", Foreign.get() == null ? "нет" : Foreign.get().name()), 10);
        step("index", () -> Indexer.state() == Indexer.State.READY && !Indexer.snapshot().stacks.isEmpty(), () -> {
            command("gamerule sendCommandFeedback false");
            command("gamemode survival");
            // мир автотеста живёт между запусками: возвращаем стадии в исходное состояние
            command("advancement revoke @s only minecraft:story/enter_the_nether");
            command("ryscope stage revoke @s config:diamond");
            command("ryscope stage revoke @s config:machines");
            command("clear @s");
            command("time set noon");
            command("weather clear");
            command("give @s minecraft:oak_planks 64");
            command("give @s minecraft:cobblestone 64");
            command("give @s minecraft:iron_ingot 16");
            command("give @s minecraft:redstone 8");
            command("give @s minecraft:oak_log 8");
            command("tp @s 0.5 -60 0.5 0 0");
            command("fill -3 -60 -3 3 -56 6 minecraft:air");
        }, 30);

        step("inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 20);
        shot("inventory");
        step("scroll to groups", () -> {
            List<Stack> list = SearchService.results().stacks;
            int groups = 0, first = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof works.repka.ryscope.runtime.Groups.GroupStack) {
                    groups++;
                    if (first < 0) first = i;
                }
            }
            RYScope.LOG.info("[autotest] свёрнутых групп в списке: {}, ячеек всего: {}", groups, list.size());
            if (first >= 0) Overlay.revealInList(Math.max(0, first - 5));
        }, 12);
        shot("groups");
        search("piston");
        shot("search_piston");
        search("pistn");
        shot("search_fuzzy");
        search("gjhitym");
        shot("search_layout");
        search("@minecraft #planks -oak");
        shot("search_syntax");
        search("uses:piston");
        shot("search_uses");
        search("craftable");
        shot("search_craftable");
        search("");

        step("recipe", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES), 10);
        shot("recipe_piston");
        step("uses", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.IRON_INGOT)), Lookup.Mode.USES), 10);
        shot("uses_iron");
        step("smelting", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.IRON_INGOT)), Lookup.Mode.RECIPES), 10);
        shot("recipes_iron");
        step("brewing", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.BLAZE_POWDER)), Lookup.Mode.USES), 10);
        shot("uses_blaze_powder");
        step("next tab", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0), 6);
        shot("uses_blaze_powder_tab2");
        step("log", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.OAK_LOG)), Lookup.Mode.USES), 10);
        shot("uses_oak_log");
        step("next tab", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0), 6);
        shot("uses_oak_log_tab2");
        step("next tab", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0), 6);
        shot("uses_oak_log_tab3");
        step("theme vanilla", () -> ThemeManager.select("ryscope:vanilla"), 6);
        shot("theme_vanilla_recipe");
        step("theme glass", () -> ThemeManager.select("ryscope:glass"), 6);
        shot("theme_glass_recipe");
        step("close", () -> mc().setScreen(null), 5);
        step("inventory glass", () -> mc().setScreen(new InventoryScreen(mc().player)), 15);
        shot("theme_glass_inventory");
        step("theme vanilla", () -> ThemeManager.select("ryscope:vanilla"), 6);
        shot("theme_vanilla_inventory");
        step("theme slate", () -> ThemeManager.select("ryscope:slate"), 6);
        step("lens card", () -> lensCard = new ItemRes(new ItemStack(Items.PISTON)), 6);
        shot("lens_gui");
        step("lens off", () -> {
            lensCard = null;
            mc().setScreen(null);
        }, 5);

        // настоящий путь ввода: курсор над слотом инвентаря и клавиша через обработчики игры
        step("input: inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 12);
        step("input: hover hotbar", () -> {
            AbstractContainerScreen<?> acs = (AbstractContainerScreen<?>) mc().screen;
            moveMouse(acs.getGuiLeft() + 8 + 8, acs.getGuiTop() + 142 + 8);
        }, 4);
        step("input: press recipes key", () -> pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_U), 8);
        step("input: check", () -> RYScope.LOG.info("[autotest] клавиша «использования» над слотом инвентаря: {}",
            mc().screen instanceof RecipeScreen ? "открыла экран рецептов" : "НЕ СРАБОТАЛА, экран " + mc().screen), 2);
        shot("input_key_over_slot");
        step("input: click list", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
        }, 10);
        step("input: focus search", () -> {
            int sw = mc().getWindow().getGuiScaledWidth();
            moveMouse(sw - 40, 4 + 5 + 7);
            click(0);
        }, 8);
        shot("search_help_and_recent");
        step("input: unfocus", () -> pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE), 4);
        step("input: click list item", () -> {
            // первая ячейка списка: панель прижата к правому краю, сетка начинается под полем поиска
            int sw = mc().getWindow().getGuiScaledWidth();
            moveMouse(sw - 4 - 5 - 4 - 9, 4 + 5 + 14 + 4 + 9);
            click(0);
        }, 8);
        step("input: check click", () -> RYScope.LOG.info("[autotest] клик по предмету в списке: {}",
            mc().screen instanceof RecipeScreen ? "открыл экран рецептов" : "НЕ СРАБОТАЛ, экран " + mc().screen), 2);
        shot("input_click_list");
        step("close", () -> mc().setScreen(null), 5);

        // источники: откуда берётся предмет
        step("sources", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.GUNPOWDER)), Lookup.Mode.RECIPES), 10);
        shot("sources_gunpowder");
        step("sources2", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.DIAMOND)), Lookup.Mode.RECIPES), 10);
        shot("sources_diamond");
        step("next tab", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0), 6);
        shot("sources_diamond_tab2");
        step("next tab", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0), 6);
        shot("sources_diamond_tab3");
        step("close", () -> mc().setScreen(null), 5);

        // дерево крафта и настройки
        step("tree", () -> works.repka.ryscope.ui.TreeScreen.open(new ItemRes(new ItemStack(Items.DISPENSER))), 12);
        shot("tree_dispenser");
        step("tree2", () -> works.repka.ryscope.ui.TreeScreen.open(new ItemRes(new ItemStack(Items.COMPARATOR))), 12);
        shot("tree_comparator");
        // стадии, справка, торговля, руды
        step("stage recipe", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.DIAMOND_PICKAXE)), Lookup.Mode.RECIPES), 10);
        shot("stage_locked_recipe");
        step("inventory for locked", () -> mc().setScreen(new InventoryScreen(mc().player)), 10);
        search("locked");
        shot("stage_locked_list");
        search("");
        step("help", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES), 10);
        step("help tab", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_UP, 0, 0), 6);
        shot("help_page");
        step("trades", () -> {
            var c = Indexer.snapshot().category(works.repka.ryscope.content.TradeContent.CATEGORY);
            RYScope.LOG.info("[autotest] сделок жителей: {}", c == null ? 0 : Indexer.snapshot().recipes(c).size());
            if (c != null) RecipeScreen.openCategory(c);
        }, 10);
        shot("trades");
        step("ores", () -> {
            var c = Indexer.snapshot().category(works.repka.ryscope.content.OreContent.CATEGORY);
            RYScope.LOG.info("[autotest] руд с данными генерации: {}", c == null ? 0 : Indexer.snapshot().recipes(c).size());
            if (c != null) RecipeScreen.openCategory(c);
        }, 10);
        shot("ore_gen");
        step("reports", () -> {
            mc().setScreen(null);
            var r = works.repka.ryscope.runtime.DevReports.run();
            RYScope.LOG.info("[autotest] отчёты: {}", r);
        }, 5);
        step("planner", () -> works.repka.ryscope.ui.PlannerScreen.open(itemProp("ryscope.autotest.plan", "minecraft:comparator")), 12);
        shot("planner");
        step("planner fast", () -> mc().screen.mouseClicked(mc().screen.width / 2.0 - 110, 6 + 10 + 22 + 5, 0), 8);
        step("compare", () -> {
            mc().setScreen(null);
            works.repka.ryscope.ui.CompareScreen.open(itemProp("ryscope.autotest.compare", "minecraft:stick"));
        }, 12);
        shot("compare");
        step("pin goal", () -> {
            works.repka.ryscope.runtime.Board.goals().clear();
            works.repka.ryscope.runtime.Board.toggle(new ItemRes(new ItemStack(Items.COMPARATOR)), 2);
            works.repka.ryscope.runtime.Board.toggle(new ItemRes(new ItemStack(Items.PISTON)), 1);
            mc().setScreen(null);
        }, 45);
        shot("board_hud");
        step("settings", () -> mc().setScreen(new works.repka.ryscope.ui.SettingsScreen(null)), 8);
        shot("settings");
        step("close", () -> mc().setScreen(null), 5);

        // --- папки закладок, сохранённые запросы, вкладки, хранилища ---
        step("folders", () -> {
            works.repka.ryscope.runtime.Bookmarks.addFolder("Redstone");
            works.repka.ryscope.runtime.Bookmarks.toggle(new ItemRes(new ItemStack(Items.REPEATER)));
            works.repka.ryscope.runtime.Bookmarks.toggle(new ItemRes(new ItemStack(Items.OBSERVER)));
            Config.get().savedSearches.clear();
            Config.get().savedSearches.add("@minecraft #planks -oak");
            Config.get().savedSearches.add("uses:piston");
            Config.get().savedSearches.add("craftable");
            mc().setScreen(new InventoryScreen(mc().player));
        }, 10);
        step("folders: check", () -> RYScope.LOG.info("[autotest] папок закладок: {}, открыта {}, закладок в ней: {}",
            works.repka.ryscope.runtime.Bookmarks.folders().size(), works.repka.ryscope.runtime.Bookmarks.active() + 1,
            works.repka.ryscope.runtime.Bookmarks.list().size()), 2);
        step("saved: focus search", () -> {
            int sw = mc().getWindow().getGuiScaledWidth();
            moveMouse(sw - 40, 4 + 5 + 7);
            click(0);
        }, 8);
        shot("folders_and_saved_searches");
        step("saved: down", () -> pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN), 2);
        step("saved: down", () -> pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN), 2);
        step("saved: enter", () -> pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER), 8);
        step("saved: check", () -> RYScope.LOG.info("[autotest] стрелками выбран сохранённый запрос (ожидается uses:piston): «{}»", SearchService.text()), 2);
        shot("saved_search_applied");
        step("saved: unfocus", () -> pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE), 4);
        search("");
        step("tabs", () -> {
            RecipeScreen.clearTabs();
            RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES);
            RecipeScreen.open(new ItemRes(new ItemStack(Items.HOPPER)), Lookup.Mode.RECIPES);
            RecipeScreen.open(new ItemRes(new ItemStack(Items.IRON_INGOT)), Lookup.Mode.USES);
        }, 10);
        shot("item_tabs");
        step("tabs: click first", () -> {
            if (mc().screen instanceof RecipeScreen rs) {
                int[] card = rs.cardBounds();
                moveMouse(card[0] + 10, card[1] - 12);
                click(0);
            }
        }, 8);
        step("tabs: check", () -> RYScope.LOG.info("[autotest] вкладок: {}, после клика по первой открыт: {}", RecipeScreen.tabCount(),
            mc().screen instanceof RecipeScreen rs && rs.focus() != null ? rs.focus().id() : "НЕ ЭКРАН РЕЦЕПТОВ"), 2);
        shot("item_tabs_switched");
        step("chest", () -> {
            mc().setScreen(null);
            command("setblock 0 -60 2 minecraft:chest{Items:[{Slot:0b,id:\"minecraft:redstone\",count:40},{Slot:1b,id:\"minecraft:quartz\",count:12}]}");
            // хранилище запоминается по блоку, на который игрок смотрит в момент открытия
            command("tp @s 0.5 -60 0.5 0 30");
        }, 20);
        step("chest: open", AutoTest::openBlock, 25);
        step("chest: close", () -> mc().player.closeContainer(), 25);
        step("chest: check", () -> RYScope.LOG.info("[autotest] хранилищ учтено: {}, редстоуна в них: {} (ожидается 40)",
            works.repka.ryscope.runtime.Storages.inRange(), works.repka.ryscope.runtime.Storages.count(Items.REDSTONE)), 2);
        step("tree with storages", () -> works.repka.ryscope.ui.TreeScreen.open(new ItemRes(new ItemStack(Items.COMPARATOR))), 12);
        shot("tree_with_storages");
        step("close", () -> mc().setScreen(null), 5);

        // верстак и перенос рецепта
        step("table", () -> command("setblock 0 -60 2 minecraft:crafting_table"), 10);
        step("open table", () -> {
            BlockPos pos = new BlockPos(0, -60, 2);
            mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        }, 20);
        shot("crafting_table");
        step("transfer", () -> {
            Screen screen = mc().screen;
            if (!(screen instanceof AbstractContainerScreen<?> acs)) {
                RYScope.LOG.error("[autotest] верстак не открылся: {}", screen);
                return;
            }
            List<Lookup.Group> groups = Lookup.find(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES);
            if (groups.isEmpty()) {
                RYScope.LOG.error("[autotest] рецепт поршня не найден");
                return;
            }
            RecipeView recipe = groups.get(0).recipes().get(0);
            RYScope.LOG.info("[autotest] проверка переноса: {}", recipe.transfer(acs, false, true));
            RYScope.LOG.info("[autotest] перенос: {}", recipe.transfer(acs, false, false));
        }, 15);
        shot("transfer_done");
        step("recipe over table", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.FURNACE)), Lookup.Mode.RECIPES), 15);
        shot("recipe_with_transfer_button");
        step("close", () -> mc().setScreen(null), 10);

        // серверные стадии: скрытый рецепт не приходит клиенту, закрытый нельзя скрафтить
        step("stages: state", () -> {
            var rm = mc().level.getRecipeManager();
            RYScope.LOG.info("[autotest] стадии от сервера: {}, открыто: {}", works.repka.ryscope.runtime.Stages.serverMode(),
                works.repka.ryscope.runtime.Stages.unlockedIds());
            RYScope.LOG.info("[autotest] скрытые рецепты у клиента (должно быть: нет, нет): маяк {}, проигрыватель {}",
                rm.byKey(net.minecraft.resources.ResourceLocation.parse("minecraft:beacon")).isPresent() ? "ЕСТЬ" : "нет",
                rm.byKey(net.minecraft.resources.ResourceLocation.parse("minecraft:jukebox")).isPresent() ? "ЕСТЬ" : "нет");
            RYScope.LOG.info("[autotest] обычный рецепт поршня у клиента: {}",
                rm.byKey(net.minecraft.resources.ResourceLocation.parse("minecraft:piston")).isPresent() ? "есть" : "НЕТ");
            command("give @s minecraft:diamond 3");
            command("give @s minecraft:stick 2");
            command("setblock 0 -60 2 minecraft:crafting_table");
        }, 20);
        step("stages: open table", () -> {
            BlockPos pos = new BlockPos(0, -60, 2);
            mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        }, 20);
        step("stages: try locked craft", () -> RYScope.LOG.info("[autotest] раскладка закрытого рецепта: {}", placePickaxe()), 15);
        step("stages: check blocked", () -> RYScope.LOG.info("[autotest] результат закрытого рецепта в верстаке (должно быть: пусто): {}",
            resultSlotFilled() ? "ВЫДАН" : "пусто"), 2);
        shot("stage_craft_blocked");
        // даже если результат показан (его считает другой мод, например FastWorkbench), забрать его нельзя
        step("stages: try take", () -> mc().gameMode.handleInventoryMouseClick(mc().player.containerMenu.containerId, 0, 0,
            net.minecraft.world.inventory.ClickType.QUICK_MOVE, mc().player), 12);
        step("stages: check take", () -> RYScope.LOG.info("[autotest] забрать результат закрытого рецепта (должно быть: не удалось): {}",
            mc().player.getInventory().countItem(Items.DIAMOND_PICKAXE) > 0 ? "ЗАБРАН" : "не удалось"), 2);
        step("stages: grant", () -> command("ryscope stage grant @s config:diamond"), 25);
        step("stages: retry", () -> {
            RYScope.LOG.info("[autotest] после выдачи командой открыто: {}", works.repka.ryscope.runtime.Stages.unlockedIds());
            RYScope.LOG.info("[autotest] раскладка открытого рецепта: {}", placePickaxe());
        }, 15);
        step("stages: check allowed", () -> RYScope.LOG.info("[autotest] результат открытого рецепта в верстаке (должно быть: есть): {}",
            resultSlotFilled() ? "есть" : "ПУСТО"), 2);
        step("stages: advancement", () -> {
            mc().setScreen(null);
            command("advancement grant @s only minecraft:story/enter_the_nether");
        }, 40);
        step("stages: hidden now visible", () -> {
            var rm = mc().level.getRecipeManager();
            RYScope.LOG.info("[autotest] после достижения рецепты у клиента (должно быть: есть, есть): маяк {}, проигрыватель {}",
                rm.byKey(net.minecraft.resources.ResourceLocation.parse("minecraft:beacon")).isPresent() ? "есть" : "НЕТ",
                rm.byKey(net.minecraft.resources.ResourceLocation.parse("minecraft:jukebox")).isPresent() ? "есть" : "НЕТ");
        }, 5);

        // запрет в камнерезе, кузнечном столе и печи
        step("machines: stonecutter", () -> {
            mc().setScreen(null);
            command("clear @s");
            command("give @s minecraft:stone 4");
            command("setblock 0 -60 2 minecraft:stonecutter");
        }, 15);
        step("machines: open stonecutter", AutoTest::openBlock, 15);
        step("machines: stone in", () -> moveItem(Items.STONE, 0), 10);
        step("machines: pick locked", () -> RYScope.LOG.info("[autotest] камнерез, выбран закрытый рецепт: {}", pickStonecutter(Items.STONE_BRICKS)), 12);
        step("machines: check stonecutter locked", () -> RYScope.LOG.info("[autotest] камнерез, результат закрытого рецепта (должно быть: пусто): {}",
            slotFilled(1) ? "ВЫДАН" : "пусто"), 2);
        step("machines: pick open", () -> RYScope.LOG.info("[autotest] камнерез, выбран открытый рецепт: {}", pickStonecutter(Items.STONE_SLAB)), 12);
        step("machines: check stonecutter open", () -> RYScope.LOG.info("[autotest] камнерез, результат открытого рецепта (должно быть: есть): {}",
            slotFilled(1) ? "есть" : "ПУСТО"), 2);

        step("machines: smithing", () -> {
            mc().setScreen(null);
            command("clear @s");
            command("give @s minecraft:netherite_upgrade_smithing_template");
            command("give @s minecraft:diamond_pickaxe");
            command("give @s minecraft:netherite_ingot");
            command("setblock 0 -60 2 minecraft:smithing_table");
        }, 15);
        step("machines: open smithing", AutoTest::openBlock, 15);
        step("machines: smithing in", () -> {
            moveItem(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 0);
            moveItem(Items.DIAMOND_PICKAXE, 1);
            moveItem(Items.NETHERITE_INGOT, 2);
        }, 15);
        step("machines: check smithing locked", () -> RYScope.LOG.info("[autotest] кузнечный стол, результат закрытого рецепта (должно быть: пусто): {}",
            slotFilled(3) ? "ВЫДАН" : "пусто"), 2);
        shot("stage_smithing_blocked");

        step("machines: campfire", () -> {
            mc().player.closeContainer();
            command("clear @s");
            command("give @s minecraft:beef 2");
            command("setblock 0 -60 2 minecraft:campfire");
        }, 20);
        step("machines: campfire use", AutoTest::openBlock, 20);
        step("machines: check campfire locked", () -> RYScope.LOG.info("[autotest] костёр, закрытый рецепт: в руке {} x{} (должно быть: говядина x2)",
            mc().player.getMainHandItem().getItem(), mc().player.getMainHandItem().getCount()), 2);

        step("machines: furnace", () -> {
            mc().setScreen(null);
            command("clear @s");
            command("give @s minecraft:sand 2");
            command("give @s minecraft:coal 2");
            command("give @s minecraft:cobblestone 2");
            command("setblock 0 -60 2 minecraft:air");
            command("setblock 0 -60 2 minecraft:furnace");
        }, 15);
        step("machines: open furnace", AutoTest::openBlock, 15);
        step("machines: furnace in", () -> {
            moveItem(Items.SAND, 0);
            moveItem(Items.COAL, 1);
        }, 40);
        step("machines: check furnace locked", () -> RYScope.LOG.info("[autotest] печь, закрытый рецепт: горит {} (должно быть: нет), угля в слоте {} (должно быть: 2)",
            furnaceLit() ? "ДА" : "нет", slotCount(1)), 2);
        step("machines: furnace swap", () -> {
            // убираем песок, кладём булыжник: его рецепт открыт
            mc().gameMode.handleInventoryMouseClick(mc().player.containerMenu.containerId, 0, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, mc().player);
            moveItem(Items.COBBLESTONE, 0);
        }, 40);
        step("machines: check furnace open", () -> RYScope.LOG.info("[autotest] печь, открытый рецепт: горит {} (должно быть: да)", furnaceLit() ? "да" : "НЕТ"), 2);

        step("machines: grant", () -> {
            mc().setScreen(null);
            command("clear @s");
            command("give @s minecraft:netherite_upgrade_smithing_template");
            command("give @s minecraft:diamond_pickaxe");
            command("give @s minecraft:netherite_ingot");
            command("setblock 0 -60 2 minecraft:smithing_table");
            command("ryscope stage grant @s config:machines");
        }, 25);
        step("machines: open smithing 2", AutoTest::openBlock, 15);
        step("machines: smithing in 2", () -> {
            moveItem(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 0);
            moveItem(Items.DIAMOND_PICKAXE, 1);
            moveItem(Items.NETHERITE_INGOT, 2);
        }, 15);
        step("machines: check smithing open", () -> RYScope.LOG.info("[autotest] кузнечный стол после выдачи стадии (должно быть: есть): {}",
            slotFilled(3) ? "есть" : "ПУСТО"), 2);
        step("machines: campfire 2", () -> {
            mc().player.closeContainer();
            command("clear @s");
            command("give @s minecraft:beef 2");
            command("setblock 0 -60 2 minecraft:campfire");
        }, 20);
        step("machines: campfire use 2", AutoTest::openBlock, 20);
        step("machines: check campfire open", () -> RYScope.LOG.info("[autotest] костёр после выдачи стадии: в руке {} x{} (должно быть: говядина x1)",
            mc().player.getMainHandItem().getItem(), mc().player.getMainHandItem().getCount()), 2);
        step("machines: cleanup", () -> {
            mc().setScreen(null);
            command("clear @s");
            command("setblock 0 -60 2 minecraft:crafting_table");
        }, 10);

        // линза в мире
        step("furnace", () -> {
            command("setblock 0 -59 2 minecraft:furnace");
            command("tp @s 0.5 -60 0.5 0 -10");
        }, 20);
        step("lens world", () -> Keys.forceLens = true, 15);
        shot("lens_world");
        step("ore", () -> command("setblock 0 -59 2 minecraft:diamond_ore"), 15);
        shot("lens_world_ore");
        step("mob", () -> {
            command("setblock 0 -59 2 minecraft:air");
            command("setblock 0 -60 2 minecraft:air");
            command("summon minecraft:cow 0.5 -60 3.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
            command("tp @s 0.5 -60 0.5 0 12");
        }, 25);
        shot("lens_world_mob");
        step("mob off", () -> command("kill @e[type=minecraft:cow]"), 5);
        step("lens world off", () -> Keys.forceLens = false, 3);

        // обзор категорий из внешних источников: по одному кадру на категорию
        step("tour", () -> {
            int n = 0;
            List<Step> extra = new ArrayList<>();
            for (works.repka.ryscope.model.Category c : Lookup.allCategories()) {
                boolean own = Indexer.snapshot().hasCategory(c.id);
                RYScope.LOG.info("[autotest] категория {} ({}), источник: {}", c.id, c.title.getString(), own ? "RYScope" : "JEI");
                String filter = System.getProperty("ryscope.autotest.cats", "");
                boolean wanted = filter.isEmpty() ? !own : c.id.toString().matches(filter);
                if (!wanted) continue;
                if (n++ >= 30) continue;
                String name = c.id.getPath().replace('/', '_');
                extra.add(new Step("cat:" + c.id, () -> true, () -> {
                    if (!RecipeScreen.openCategory(c)) RYScope.LOG.warn("[autotest] категория {} пуста", c.id);
                }, 8));
                extra.add(new Step("clean", () -> true, () -> mc().getToasts().clear(), 1));
                extra.add(new Step("shot:cat_" + name, () -> true, () -> Screenshot.grab(mc().gameDirectory,
                    String.format("ryscope_%02d_cat_%s.png", shotCounter++, name), mc().getMainRenderTarget(), msg -> {}), 3));
            }
            extra.add(new Step("close", () -> true, () -> mc().setScreen(null), 5));
            STEPS.addAll(index + 1, extra);
        }, 2);
        step("export", () -> RYScope.LOG.info("[autotest] экспорт: {}", works.repka.ryscope.runtime.Export.run()), 5);
        step("summary", () -> {
            var snap = Indexer.snapshot();
            RYScope.LOG.info("[autotest] итог: ресурсов {}, рецептов {}, категорий {}, индекс {} мс, поиск {} мкс",
                snap.stacks.size(), snap.recipeCount, snap.categories.size(), snap.buildMillis, SearchService.results().micros);
            RYScope.LOG.info("[autotest] категорий со всеми источниками: {}", Lookup.allCategories().size());
            RYScope.LOG.info("[autotest] отрисовка панелей: {} мкс на кадр", Overlay.averageFrameMicros());
        }, 20);
        step("quit", () -> mc().stop(), 0);
    }

    /** Сообщает игре о перемещении курсора так же, как это делает оконная система. Координаты в единицах GUI. */
    private static void moveMouse(double guiX, double guiY) {
        try {
            double scale = mc().getWindow().getGuiScale();
            var m = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
            m.setAccessible(true);
            // игра переводит координаты окна в координаты GUI через отношение размеров окна и кадра
            double sx = guiX * scale * mc().getWindow().getScreenWidth() / mc().getWindow().getWidth();
            double sy = guiY * scale * mc().getWindow().getScreenHeight() / mc().getWindow().getHeight();
            m.invoke(mc().mouseHandler, mc().getWindow().getWindow(), sx, sy);
        } catch (ReflectiveOperationException e) {
            RYScope.LOG.error("[autotest] не удалось переместить курсор", e);
        }
    }

    private static void click(int button) {
        try {
            var m = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onPress", long.class, int.class, int.class, int.class);
            m.setAccessible(true);
            long w = mc().getWindow().getWindow();
            m.invoke(mc().mouseHandler, w, button, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0);
            m.invoke(mc().mouseHandler, w, button, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0);
        } catch (ReflectiveOperationException e) {
            RYScope.LOG.error("[autotest] не удалось нажать кнопку мыши", e);
        }
    }

    private static void pressKey(int key) {
        long w = mc().getWindow().getWindow();
        int scan = org.lwjgl.glfw.GLFW.glfwGetKeyScancode(key);
        mc().keyboardHandler.keyPress(w, key, scan, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0);
        mc().keyboardHandler.keyPress(w, key, scan, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0);
    }

    /** Предмет по id из системного свойства: чтобы один сценарий годился для разных наборов модов. */
    private static ItemRes itemProp(String property, String fallback) {
        var id = net.minecraft.resources.ResourceLocation.parse(System.getProperty(property, fallback));
        return new ItemRes(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id)));
    }

    private static void openBlock() {
        BlockPos pos = new BlockPos(0, -60, 2);
        mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    /** Перекладывает стопку предмета из инвентаря в слот открытого меню обычными кликами. */
    private static void moveItem(net.minecraft.world.item.Item item, int target) {
        var menu = mc().player.containerMenu;
        for (var slot : menu.slots) {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.getItem().is(item)) {
                mc().gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, mc().player);
                mc().gameMode.handleInventoryMouseClick(menu.containerId, target, 0, net.minecraft.world.inventory.ClickType.PICKUP, mc().player);
                return;
            }
        }
        RYScope.LOG.error("[autotest] в инвентаре нет {}", item);
    }

    private static boolean slotFilled(int index) {
        var menu = mc().player.containerMenu;
        return index < menu.slots.size() && menu.getSlot(index).hasItem();
    }

    private static int slotCount(int index) {
        var menu = mc().player.containerMenu;
        return index < menu.slots.size() ? menu.getSlot(index).getItem().getCount() : -1;
    }

    private static boolean furnaceLit() {
        return mc().player.containerMenu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu furnace && furnace.isLit();
    }

    /** Выбирает в камнерезе рецепт с этим результатом, как клик по кнопке рецепта. */
    private static String pickStonecutter(net.minecraft.world.item.Item result) {
        if (!(mc().player.containerMenu instanceof net.minecraft.world.inventory.StonecutterMenu menu)) return "камнерез не открыт";
        var recipes = menu.getRecipes();
        for (int i = 0; i < recipes.size(); i++) {
            if (recipes.get(i).value().getResultItem(mc().level.registryAccess()).is(result)) {
                menu.clickMenuButton(mc().player, i);
                mc().gameMode.handleInventoryButtonClick(menu.containerId, i);
                return "номер " + i + " из " + recipes.size();
            }
        }
        return "рецепт не найден среди " + recipes.size();
    }

    private static Object placePickaxe() {
        if (!(mc().screen instanceof AbstractContainerScreen<?> acs)) return "верстак не открыт: " + mc().screen;
        List<Lookup.Group> groups = Lookup.find(new ItemRes(new ItemStack(Items.DIAMOND_PICKAXE)), Lookup.Mode.RECIPES);
        if (groups.isEmpty()) return "рецепт кирки не найден";
        return groups.get(0).recipes().get(0).transfer(acs, false, false);
    }

    private static boolean resultSlotFilled() {
        return mc().screen instanceof AbstractContainerScreen<?> acs && acs.getMenu().getSlot(0).hasItem();
    }

    /** Примеры данных сборки: страница справки и стадия в config/ryscope. */
    private static void writeSamples() {
        try {
            java.nio.file.Path dir = Config.dir();
            java.nio.file.Files.createDirectories(dir.resolve("help"));
            java.nio.file.Files.createDirectories(dir.resolve("stages"));
            java.nio.file.Files.writeString(dir.resolve("help/piston.json"), """
                { "target": "minecraft:piston",
                  "body": [
                    { "p": "A piston pushes up to 12 blocks when it gets a redstone signal. Put [[item:minecraft:slime_ball]] on it to make a [[item:minecraft:sticky_piston]] that also pulls." },
                    { "warn": "Obsidian, bedrock and containers with items inside cannot be pushed." },
                    { "p": "Usually powered by:" },
                    { "items": ["minecraft:lever", "minecraft:redstone_torch", "minecraft:observer"] }
                  ] }
                """);
            java.nio.file.Files.writeString(dir.resolve("stages/diamond.json"), """
                { "name": "Diamond age", "order": 2, "advancement": "minecraft:end/kill_dragon", "hint": "Defeat the Ender Dragon to unlock",
                  "items": ["minecraft:diamond_pickaxe", "minecraft:enchanting_table", "#minecraft:beds"],
                  "recipes": ["minecraft:jukebox"] }
                """);
            java.nio.file.Files.writeString(dir.resolve("stages/nether.json"), """
                { "name": "Nether age", "order": 3, "advancement": "minecraft:story/enter_the_nether", "hidden": true,
                  "items": ["minecraft:beacon"], "recipes": ["minecraft:jukebox"] }
                """);
            java.nio.file.Files.writeString(dir.resolve("stages/machines.json"), """
                { "name": "Machine age", "order": 4, "advancement": "minecraft:end/kill_dragon", "hint": "Defeat the Ender Dragon to unlock",
                  "items": ["minecraft:netherite_pickaxe", "minecraft:stone_bricks", "minecraft:glass", "minecraft:cooked_beef"] }
                """);
            works.repka.ryscope.content.HelpContent.reload(mc().getResourceManager());
            works.repka.ryscope.runtime.Stages.reload(mc().getResourceManager());
        } catch (Exception e) {
            RYScope.LOG.error("[autotest] примеры данных не записаны", e);
        }
    }

    /**
     * Повторное открытие мира в большой сборке останавливается на вопросе «сделать резервную копию?»
     * (мир помечен как экспериментальный). Автотест отвечает «продолжить без копии».
     */
    private static void skipBackupQuestion() {
        if (!(mc().screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen)) return;
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button button
                && button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && "selectWorld.backupJoinSkipButton".equals(text.getKey())) {
                RYScope.LOG.info("[autotest] вопрос о резервной копии мира: продолжаю без копии");
                button.onPress();
                return;
            }
        }
    }

    private static void search(String text) {
        step("search:" + text, () -> Overlay.setSearch(text), 8);
    }

    private static void tick() {
        if (index >= STEPS.size()) return;
        if (++total > 20 * 600) {
            RYScope.LOG.error("[autotest] превышено время, шаг {}", STEPS.get(index).name);
            index = STEPS.size();
            mc().stop();
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Step step = STEPS.get(index);
        boolean ready;
        try {
            ready = step.ready.getAsBoolean();
        } catch (Throwable t) {
            ready = false;
        }
        if (!ready) {
            stuck++;
            return;
        }
        stuck = 0;
        RYScope.LOG.info("[autotest] {}", step.name);
        try {
            step.action.run();
        } catch (Throwable t) {
            RYScope.LOG.error("[autotest] шаг {} упал", step.name, t);
        }
        wait = step.waitAfter;
        index++;
    }
}
