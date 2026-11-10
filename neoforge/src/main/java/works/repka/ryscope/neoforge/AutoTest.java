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
import works.repka.ryscore.theme.ThemeManager;

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
            long now = System.nanoTime();
            if (lastFrameAt != 0) worstFrame = Math.max(worstFrame, now - lastFrameAt);
            lastFrameAt = now;
            frames++;
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

    /** Вкладки «Использования» у контроллера Modular Machinery: категории с превью структур и квестами. */
    private static void buildStructures() {
        step("controller", () -> {
            Stack found = null;
            try {
                Object item = Class.forName("es.degrassi.mmreborn.common.item.ControllerItem")
                    .getMethod("makeMachineItem", net.minecraft.resources.ResourceLocation.class)
                    .invoke(null, net.minecraft.resources.ResourceLocation.parse("repys:kinetic_foundry"));
                found = new ItemRes((ItemStack) item);
            } catch (ReflectiveOperationException e) {
                RYScope.LOG.error("[autotest] controller", e);
            }
            if (found != null) RecipeScreen.open(found, Lookup.Mode.USES);
        }, 60);
        shot("structures_0");
        for (int i = 1; i <= 3; i++) {
            step("next tab", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0), 40);
            shot("structures_" + i);
        }
        step("rotate and zoom", () -> {
            RecipeScreen.Placed p = ((RecipeScreen) mc().screen).placed().get(0);
            p.recipe().mouseClicked(p.x() + 100, p.y() + 60, 0);
            p.recipe().mouseDragged(p.x() + 100, p.y() + 60, 0, 60, 20);
            p.recipe().mouseReleased(p.x() + 100, p.y() + 60, 0);
            p.recipe().mouseScrolled(p.x() + 100, p.y() + 60, 0, 3);
        }, 10);
        shot("structures_rotated");
        step("layer", () -> {
            RecipeScreen.Placed p = ((RecipeScreen) mc().screen).placed().get(0);
            for (int i = 0; i < 3; i++) {
                p.recipe().mouseClicked(p.x() + 43, p.y() + 9, 0);
                p.recipe().mouseReleased(p.x() + 43, p.y() + 9, 0);
            }
        }, 30);
        shot("structures_layer3");
        step("glass", () -> {
            ThemeManager.select("ryscore:glass");
            command("time set midnight");
        }, 30);
        shot("structures_glass_night");
        step("gui scale 4", () -> {
            ThemeManager.select("ryscore:slate");
            mc().options.guiScale().set(4);
            mc().resizeDisplay();
        }, 30);
        shot("structures_gui4");
        step("restore", () -> {
            mc().options.guiScale().set(0);
            mc().resizeDisplay();
            command("time set noon");
        }, 5);
        step("jer enchant glass", () -> {
            ThemeManager.select("ryscore:glass");
            RecipeScreen.open(new ItemRes(new ItemStack(Items.DIAMOND_SWORD)), Lookup.Mode.USES);
            openJerTab("enchantment");
        }, 40);
        shot("jer_enchant_glass");
        step("jer mob", () -> {
            ThemeManager.select("ryscore:slate");
            RecipeScreen.open(new ItemRes(new ItemStack(Items.ROTTEN_FLESH)), Lookup.Mode.RECIPES);
            openSources();
            openJerTab("mob");
        }, 40);
        shot("jer_mob_slate");
        step("glass", () -> ThemeManager.select("ryscore:glass"), 20);
        shot("jer_mob_glass");
        step("slate", () -> ThemeManager.select("ryscore:slate"), 2);
        step("done", () -> mc().stop(), 1);
    }

    private static void openSources() {
        try {
            Class<?> section = Class.forName("works.repka.ryscope.ui.RecipeScreen$Section");
            Object sources = section.getEnumConstants()[2];
            java.lang.reflect.Method switchSection = RecipeScreen.class.getDeclaredMethod("switchSection", section);
            switchSection.setAccessible(true);
            switchSection.invoke(mc().screen, sources);
        } catch (ReflectiveOperationException e) {
            RYScope.LOG.error("[autotest] sources", e);
        }
    }

    /** Выбирает вкладку Just Enough Resources, id которой содержит {@code part}. */
    private static void openJerTab(String part) {
        try {
            java.lang.reflect.Field navField = RecipeScreen.class.getDeclaredField("nav");
            navField.setAccessible(true);
            Object nav = navField.get(mc().screen);
            java.lang.reflect.Field groupsField = nav.getClass().getDeclaredField("groups");
            groupsField.setAccessible(true);
            List<Lookup.Group> groups = (List<Lookup.Group>) groupsField.get(nav);
            for (int i = 0; i < groups.size(); i++) {
                String id = groups.get(i).category().id.toString();
                if (id.startsWith("jeresources") && id.contains(part)) {
                    java.lang.reflect.Method setGroup = RecipeScreen.class.getDeclaredMethod("setGroup", int.class);
                    setGroup.setAccessible(true);
                    setGroup.invoke(mc().screen, i);
                    RYScope.LOG.info("[autotest] tab {}", id);
                    return;
                }
            }
            RYScope.LOG.warn("[autotest] tab {} not found", part);
        } catch (ReflectiveOperationException e) {
            RYScope.LOG.error("[autotest] tab", e);
        }
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
            ThemeManager.select("ryscore:slate");
            // настройки живут между запусками: каждый запуск выставляет режим заново
            Config.get().replaceJeiInterface = !JEI_GUI;
            Config.get().overlayEnabled = !JEI_GUI;
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
        if ("design".equals(System.getProperty("ryscope.autotest"))) {
            buildDesign();
            return;
        }
        if ("matrix".equals(System.getProperty("ryscope.autotest"))) {
            buildMatrix();
            return;
        }
        if ("structures".equals(System.getProperty("ryscope.autotest"))) {
            buildStructures();
            return;
        }
        if ("jeiscroll".equals(System.getProperty("ryscope.autotest"))) {
            buildJeiScroll();
            return;
        }
        if ("ui".equals(System.getProperty("ryscope.autotest"))) {
            buildUi();
            return;
        }
        if ("loot".equals(System.getProperty("ryscope.autotest"))) {
            buildLoot();
            return;
        }
        if ("ae2".equals(System.getProperty("ryscope.autotest"))) {
            buildAe2();
            return;
        }
        if ("dock".equals(System.getProperty("ryscope.autotest"))) {
            buildDock();
            return;
        }
        if ("perf".equals(System.getProperty("ryscope.autotest"))) {
            buildPerf();
            return;
        }
        if ("shapes".equals(System.getProperty("ryscope.autotest"))) {
            buildShapes();
            return;
        }

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
        step("theme vanilla", () -> ThemeManager.select("ryscore:vanilla"), 6);
        shot("theme_vanilla_recipe");
        step("theme glass", () -> ThemeManager.select("ryscore:glass"), 6);
        shot("theme_glass_recipe");
        step("close", () -> mc().setScreen(null), 5);
        step("inventory glass", () -> mc().setScreen(new InventoryScreen(mc().player)), 15);
        shot("theme_glass_inventory");
        step("theme vanilla", () -> ThemeManager.select("ryscore:vanilla"), 6);
        shot("theme_vanilla_inventory");
        step("theme slate", () -> ThemeManager.select("ryscore:slate"), 6);
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
        // какие рецепты дерево видит для предмета и какой берёт по умолчанию: для разбора странных деревьев в сборках
        step("tree options", () -> {
            var probe = new works.repka.ryscope.runtime.CraftTree(itemProp("ryscope.autotest.treeProbe", "minecraft:bow"));
            var node = probe.root;
            RYScope.LOG.info("[autotest] варианты для {}: выбран {} из {}", node.stack.id(), node.choice + 1, node.options.size());
            for (int i = 0; i < Math.min(12, node.options.size()); i++) {
                var option = node.options.get(i);
                StringBuilder inputs = new StringBuilder();
                for (var ing : option.recipe.inputs()) {
                    if (!ing.isEmpty() && !ing.options.isEmpty()) inputs.append(ing.options.get(0).id()).append(' ');
                }
                RYScope.LOG.info("[autotest]   {}. {} | {} | обратимый: {} | входы: {}", i + 1, option.category.title.getString(),
                    option.recipe.id(), option.reversible, inputs.toString().trim());
            }
        }, 2);
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

    // --- сценарий perf: отклик нашего интерфейса или родного интерфейса JEI, сверка рецептов ---

    /** Мерить родной интерфейс JEI: наши панели выключены, интерфейс JEI не заменяется. */
    private static final boolean JEI_GUI = Boolean.getBoolean("ryscope.autotest.jeiGui");
    private static long frames, framesSince, lastFrameAt, worstFrame;

    private static void framesReset() {
        frames = 0;
        framesSince = System.nanoTime();
        lastFrameAt = 0;
        worstFrame = 0;
    }

    private static String framesReport() {
        double average = frames == 0 ? 0 : (System.nanoTime() - framesSince) / 1e6 / frames;
        return String.format(java.util.Locale.ROOT, "%.2f мс на кадр, худший %.1f мс, кадров %d", average, worstFrame / 1e6, frames);
    }

    private static void setPanels(boolean on) {
        if (JEI_GUI) AutoTestJei.overlay(on);
        else Config.get().overlayEnabled = on;
    }

    private static long usedMegabytes() {
        System.gc();
        System.gc();
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }

    /** Деревья нескольких предметов: обычный выбор рецептов против кратчайшего пути. */
    private static void treeReport() {
        for (String id : new String[] {"minecraft:dispenser", "minecraft:comparator", "minecraft:beacon", "mekanism:steel_casing",
            "create:mechanical_crafter", "ae2:controller", "mekanism:digital_miner"}) {
            var key = net.minecraft.resources.ResourceLocation.parse(id);
            if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) continue;
            var stack = new ItemRes(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key)));
            long t0 = System.nanoTime();
            var tree = new works.repka.ryscope.runtime.CraftTree(stack);
            long usualMs = (System.nanoTime() - t0) / 1_000_000;
            int usualSteps = tree.steps, usualDepth = tree.maxDepth, usualItems = tree.examined();
            t0 = System.nanoTime();
            tree.setShortest(true);
            long shortMs = (System.nanoTime() - t0) / 1_000_000;
            RYScope.LOG.info("[perf] дерево {}: обычный выбор {} шагов, глубина {}, построение {} мс, просмотрено {}; "
                + "кратчайший путь {} шагов, глубина {}, поиск {} мс, просмотрено {}",
                id, usualSteps, usualDepth, usualMs, usualItems, tree.steps, tree.maxDepth, shortMs, tree.examined());
        }
    }

    /** Стенд скруглений: тень под окном и сетка карточек с рамками, как в книге квестов. */
    private static final class ShapeBench extends net.minecraft.client.gui.screens.Screen {
        static long nanos, cpu, count;

        static String report() {
            return count == 0 ? "нет кадров" : String.format(java.util.Locale.ROOT, "процессор %.0f мкс, вместе с видеокартой %.0f мкс на кадр, кадров %d",
                cpu / 1000.0 / count, nanos / 1000.0 / count, count);
        }

        static void reset() {
            nanos = 0;
            cpu = 0;
            count = 0;
        }

        ShapeBench() {
            super(net.minecraft.network.chat.Component.literal("shapes"));
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics parent, int mouseX, int mouseY, float partialTick) {
            parent.flush();
            org.lwjgl.opengl.GL11.glFinish();
            long t0 = System.nanoTime();
            body(parent);
            parent.flush();
            cpu += System.nanoTime() - t0;
            org.lwjgl.opengl.GL11.glFinish();
            nanos += System.nanoTime() - t0;
            count++;
        }

        private void body(net.minecraft.client.gui.GuiGraphics parent) {
            works.repka.ryscore.ui.UiScale.render(parent, g -> {
                works.repka.ryscore.theme.Theme t = ThemeManager.get();
                int w = Math.min(600, g.guiWidth() - 40), h = Math.min(360, g.guiHeight() - 40);
                int x = (g.guiWidth() - w) / 2, y = (g.guiHeight() - h) / 2;
                works.repka.ryscore.ui.Draw.shadow(g, x, y, w, h, 7f, 1f);
                works.repka.ryscore.ui.Draw.roundBox(g, x, y, w, h, 7f, t.c("panel.bg"), t.c("panel.border"));
                for (int row = 0; row < 8; row++) {
                    for (int col = 0; col < 6; col++) {
                        float cx = x + 10 + col * (w - 20) / 6f, cy = y + 10 + row * (h - 20) / 8f;
                        float cw = (w - 20) / 6f - 6, ch = (h - 20) / 8f - 6;
                        works.repka.ryscore.ui.Draw.roundBox(g, cx, cy, cw, ch, 4f, t.c("card.bg"), t.c("card.border"));
                        works.repka.ryscore.ui.Draw.roundBox(g, cx + 4, cy + 4, 16, 16, 3f, t.c("slot.bg"), t.c("slot.border"));
                        works.repka.ryscore.ui.Draw.dot(g, (int) (cx + cw - 10), (int) (cy + 6), 5, t.c("accent"));
                        works.repka.ryscore.ui.Draw.round(g, cx + 24, cy + ch - 10, cw - 30, 4, 2f, t.c("accent"));
                    }
                }
            });
        }
    }

    /** Текст и скругления под уменьшающей матрицей, как на карте квестов с зумом. */
    private static final class ZoomBench extends net.minecraft.client.gui.screens.Screen {
        ZoomBench() {
            super(net.minecraft.network.chat.Component.literal("zoom"));
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics parent, int mouseX, int mouseY, float partialTick) {
            works.repka.ryscore.ui.UiScale.render(parent, g -> {
                works.repka.ryscore.theme.Theme t = ThemeManager.get();
                works.repka.ryscore.ui.Draw.rect(g, 0, 0, g.guiWidth(), g.guiHeight(), t.c("panel.bg"));
                float[] zooms = {1f, 0.6f, 0.35f};
                float y = 10;
                for (float zoom : zooms) {
                    g.pose().pushPose();
                    g.pose().translate(10, y, 0);
                    g.pose().scale(zoom, zoom, 1f);
                    for (int i = 0; i < 3; i++) {
                        float x = i * 190;
                        works.repka.ryscore.ui.Draw.roundBox(g, x, 0, 180, 60, 8f, t.c("card.bg"), t.c("card.border"));
                        works.repka.ryscore.ui.Draw.roundBorder(g, x + 8, 8, 24, 24, 12f, t.c("accent"), 1.5f);
                        works.repka.ryscore.ui.Draw.label(g, "Кинетическая литейня", x + 40, 16, 15f, works.repka.ryscore.ui.Type.Weight.SEMIBOLD, t.c("text.body"));
                        works.repka.ryscore.ui.Draw.label(g, "Quest 12 · 3/5 done", x + 40, 34, 12f, works.repka.ryscore.ui.Type.Weight.REGULAR, t.c("text.muted"));
                        works.repka.ryscore.ui.Draw.label(g, "Zoom " + zoom, x + 8, 50, 11f, works.repka.ryscore.ui.Type.Weight.REGULAR, t.c("text.body"));
                    }
                    g.pose().popPose();
                    y += 70 * zoom + 8;
                }
            });
        }
    }

    private static void buildShapes() {
        step("shapes: setup", () -> {
            mc().options.enableVsync().set(false);
            mc().options.framerateLimit().set(260);
            mc().getWindow().updateVsync(false);
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 1920, 1080);
            mc().setScreen(new ShapeBench());
        }, 80);
        for (String theme : new String[] {"ryscore:slate", "ryscore:glass"}) {
            step("shapes: " + theme, () -> {
                ThemeManager.select(theme);
                ShapeBench.reset();
                framesReset();
            }, 200);
            step("shapes: report", () -> RYScope.LOG.info("[perf] стенд скруглений, {}: {}; {}", theme, ShapeBench.report(), framesReport()), 2);
            shot("shapes_" + theme.substring(8));
        }
        step("shapes: zoom", () -> mc().setScreen(new ZoomBench()), 20);
        shot("shapes_zoom");
        step("shapes: recipes", () -> {
            ThemeManager.select("ryscore:slate");
            RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES);
        }, 40);
        step("shapes: recipes reset", AutoTest::framesReset, 200);
        step("shapes: recipes report", () -> RYScope.LOG.info("[perf] окно рецептов: {}", framesReport()), 2);
        shot("shapes_recipes");
        step("shapes: inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 40);
        step("shapes: inventory reset", AutoTest::framesReset, 200);
        step("shapes: inventory report", () -> RYScope.LOG.info("[perf] инвентарь с панелями: {}", framesReport()), 2);
        shot("shapes_inventory");
        step("quit", () -> mc().stop(), 0);
    }

    private static void buildPerf() {
        String who = JEI_GUI ? "интерфейс JEI" : "RYScope";
        step("perf: setup", () -> {
            mc().options.enableVsync().set(false);
            mc().options.framerateLimit().set(260);
            mc().getWindow().updateVsync(false);
            mc().setScreen(new InventoryScreen(mc().player));
            RYScope.LOG.info("[perf] режим: {}; память после сборки мусора: {} МБ", who, usedMegabytes());
        }, 60);
        for (int round = 1; round <= 2; round++) {
            int r = round;
            step("perf: panels off", () -> {
                setPanels(false);
                framesReset();
            }, 100);
            step("perf: baseline", () -> {
                RYScope.LOG.info("[perf] круг {}: инвентарь без панелей: {}", r, framesReport());
                setPanels(true);
            }, 20);
            step("perf: panels on", AutoTest::framesReset, 100);
            step("perf: with panels", () -> RYScope.LOG.info("[perf] круг {}: инвентарь с панелями ({}): {}", r, who, framesReport()), 2);
        }
        shot("perf_inventory");
        for (String query : new String[] {"piston", "iron", "@mekanism", "#c:ingots", "слиток", "zzzz"}) {
            if (JEI_GUI) {
                step("perf: search " + query, () -> RYScope.LOG.info("[perf] поиск JEI «{}»: {}", query, AutoTestJei.search(query)), 10);
            } else {
                step("perf: search " + query, () -> Overlay.setSearch(query), 15);
                step("perf: search result", () -> RYScope.LOG.info("[perf] поиск RYScope «{}»: {} мкс в фоновом потоке, найдено {}",
                    query, SearchService.results().micros, SearchService.results().stacks.size()), 2);
            }
        }
        step("perf: search clear", () -> {
            if (JEI_GUI) AutoTestJei.search("");
            else Overlay.setSearch("");
        }, 10);
        Object[][] cases = {
            {Items.IRON_INGOT, Lookup.Mode.USES}, {Items.IRON_INGOT, Lookup.Mode.RECIPES}, {Items.PISTON, Lookup.Mode.RECIPES},
            {Items.REDSTONE, Lookup.Mode.USES}, {Items.OAK_PLANKS, Lookup.Mode.USES}};
        for (Object[] c : cases) {
            net.minecraft.world.item.Item item = (net.minecraft.world.item.Item) c[0];
            Lookup.Mode mode = (Lookup.Mode) c[1];
            boolean uses = mode == Lookup.Mode.USES;
            String label = (uses ? "использования " : "рецепты ") + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            step("perf: open " + label, () -> {
                ItemStack stack = new ItemStack(item);
                String volume = AutoTestJei.volume(stack, uses);
                long t0 = System.nanoTime();
                if (JEI_GUI) {
                    AutoTestJei.openRecipes(stack, uses);
                } else {
                    RecipeScreen.open(new ItemRes(stack), mode);
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                framesReset();
                RYScope.LOG.info("[perf] окно рецептов ({}), {}: открытие {} мс; у JEI для этого запроса {}", who, label, ms, volume);
            }, 30);
            step("perf: first frames", () -> RYScope.LOG.info("[perf]   первые кадры окна: {}", framesReport()), 2);
            step("perf: back", () -> mc().setScreen(new InventoryScreen(mc().player)), 10);
        }
        shot("perf_after_recipes");
        step("perf: memory", () -> RYScope.LOG.info("[perf] память после сборки мусора в конце: {} МБ", usedMegabytes()), 10);
        if (!JEI_GUI) {
            step("perf: trees", AutoTest::treeReport, 5);
            step("audit", AutoTestJei::audit, 5);
            step("summary", () -> {
                var snap = Indexer.snapshot();
                RYScope.LOG.info("[perf] индекс: ресурсов {}, рецептов {}, построен за {} мс в фоне; отрисовка наших панелей {} мкс на кадр",
                    snap.stacks.size(), snap.recipeCount, snap.buildMillis, Overlay.averageFrameMicros());
            }, 5);
        }
        step("quit", () -> mc().stop(), 0);
    }

    // --- сценарий ae2: настоящая сеть, терминалы, перенос и шаблоны ---

    private static final BlockPos AE_CELL = new BlockPos(0, -60, 2);
    private static final BlockPos AE_DRIVE = new BlockPos(1, -60, 2);
    private static final BlockPos AE_CABLE = new BlockPos(0, -59, 2);

    private static void use(BlockPos pos, Direction face, Vec3 hit) {
        mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
    }

    private static void hold(String id) {
        mc().player.getInventory().selected = 8;
        command("item replace entity @s hotbar.8 with " + id);
    }

    private static void quickMoveAll(net.minecraft.world.item.Item item) {
        var menu = mc().player.containerMenu;
        for (var slot : menu.slots) {
            if (slot.container == mc().player.getInventory() && slot.getItem().is(item)) {
                mc().gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, mc().player);
            }
        }
    }

    private static RecipeView firstRecipe(net.minecraft.world.item.Item item, String categoryPart) {
        for (var group : Lookup.find(new ItemRes(new ItemStack(item)), Lookup.Mode.RECIPES)) {
            if (!group.category().id.toString().contains(categoryPart)) continue;
            // в большой сборке первым бывает рецепт из чужого мода: для проверки нужен обычный
            var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            for (RecipeView r : group.recipes()) if (key.equals(r.id())) return r;
            return group.recipes().get(0);
        }
        return null;
    }

    private static void logTransfer(String label, net.minecraft.world.item.Item item, String category) {
        RecipeView recipe = firstRecipe(item, category);
        var result = recipe == null || !(mc().screen instanceof AbstractContainerScreen<?> acs) ? null : recipe.transfer(acs, false, true);
        RYScope.LOG.info("[ae2] {}: рецепт {}, проверка: {}, подсказка: {}", label, recipe == null ? "НЕ НАЙДЕН" : recipe.id(),
            result == null ? "нет" : result.status(), result == null ? "" : result.lines().stream().map(c -> c.getString()).toList());
    }

    private static void doTransfer(net.minecraft.world.item.Item item, String category) {
        RecipeView recipe = firstRecipe(item, category);
        if (recipe != null && mc().screen instanceof AbstractContainerScreen<?> acs) {
            RYScope.LOG.info("[ae2] перенос {}: {}", recipe.id(), recipe.transfer(acs, false, false).status());
        }
    }

    /** Что лежит в особых слотах терминала: сетка крафта или слоты шаблона. */
    private static String slotsOf(String classPart) {
        List<String> out = new ArrayList<>();
        for (var slot : mc().player.containerMenu.slots) {
            if (slot.getClass().getSimpleName().contains(classPart) && !slot.getItem().isEmpty()) out.add(slot.getItem().getItem().toString() + " x" + slot.getItem().getCount());
        }
        return out.toString();
    }

    private static void buildAe2() {
        step("ae2: setup", () -> {
            command("clear @s");
            command("fill 0 -60 2 1 -58 2 minecraft:air");
            command("setblock 0 -60 2 ae2:creative_energy_cell");
            command("setblock 1 -60 2 ae2:drive");
            command("give @s ae2:item_storage_cell_1k");
            command("give @s minecraft:oak_planks 64");
            command("give @s minecraft:redstone 16");
            command("give @s ae2:blank_pattern 4");
            hold("ae2:fluix_glass_cable 4");
        }, 20);
        step("ae2: cable 1", () -> use(AE_CELL, Direction.UP, new Vec3(0.5, -59, 2.5)), 10);
        step("ae2: cable 2", () -> use(AE_DRIVE, Direction.UP, new Vec3(1.5, -59, 2.5)), 10);
        step("ae2: hold terminal", () -> hold("ae2:crafting_terminal"), 10);
        step("ae2: place terminal", () -> use(AE_CABLE, Direction.NORTH, new Vec3(0.5, -58.5, 2.3)), 10);
        step("ae2: hold pattern", () -> hold("ae2:pattern_encoding_terminal"), 10);
        step("ae2: place pattern", () -> use(AE_CABLE, Direction.WEST, new Vec3(0.3, -58.5, 2.5)), 10);
        step("ae2: empty hand", () -> command("item replace entity @s hotbar.8 with minecraft:air"), 10);
        step("ae2: open drive", () -> use(AE_DRIVE, Direction.NORTH, new Vec3(1.5, -59.5, 2)), 20);
        step("ae2: insert cell", () -> {
            RYScope.LOG.info("[ae2] экран диска: {}", mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName());
            quickMoveAll(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("ae2:item_storage_cell_1k")));
        }, 15);
        step("ae2: close drive", () -> mc().player.closeContainer(), 30);
        step("ae2: open terminal", () -> use(AE_CABLE, Direction.NORTH, new Vec3(0.5, -58.5, 2.1)), 30);
        step("ae2: fill network", () -> {
            RYScope.LOG.info("[ae2] экран терминала: {}", mc().screen == null ? "нет" : mc().screen.getClass().getName());
            quickMoveAll(Items.OAK_PLANKS);
        }, 30);
        shot("ae2_terminal");
        step("ae2: simulate", () -> {
            logTransfer("сундук (доски есть в сети)", Items.CHEST, "crafting");
            logTransfer("поршень (не хватает части)", Items.PISTON, "crafting");
            logTransfer("алмазная кирка (ничего нет)", Items.DIAMOND_PICKAXE, "crafting");
            logTransfer("стекло в печи (не верстак)", Items.GLASS, "smelting");
        }, 5);
        step("ae2: recipes over terminal", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES), 25);
        shot("ae2_recipe_partial");
        step("ae2: back to terminal", () -> mc().screen.onClose(), 10);
        step("ae2: transfer chest", () -> doTransfer(Items.CHEST, "crafting"), 25);
        step("ae2: check grid", () -> RYScope.LOG.info("[ae2] сетка терминала после переноса: {}", slotsOf("CraftingMatrix")), 2);
        shot("ae2_terminal_filled");
        step("ae2: close terminal", () -> mc().player.closeContainer(), 15);
        step("ae2: open pattern", () -> use(AE_CABLE, Direction.WEST, new Vec3(0.1, -58.5, 2.5)), 30);
        step("ae2: pattern state", () -> {
            RYScope.LOG.info("[ae2] экран шаблонов: {}", mc().screen == null ? "нет" : mc().screen.getClass().getName());
            logTransfer("шаблон сундука", Items.CHEST, "crafting");
            logTransfer("шаблон стекла", Items.GLASS, "smelting");
        }, 5);
        step("ae2: encode crafting", () -> doTransfer(Items.CHEST, "crafting"), 25);
        step("ae2: check crafting pattern", () -> RYScope.LOG.info("[ae2] слоты шаблона после записи верстака: {}", slotsOf("Fake")), 2);
        shot("ae2_pattern_crafting");
        step("ae2: encode processing", () -> doTransfer(Items.GLASS, "smelting"), 25);
        step("ae2: check processing pattern", () -> RYScope.LOG.info("[ae2] слоты шаблона после записи печи: {}", slotsOf("Fake")), 2);
        shot("ae2_pattern_processing");
        step("ae2: card height", () -> {
            RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES);
        }, 12);
        step("ae2: card top 1", () -> RYScope.LOG.info("[card] поршень: верх {}, высота {}", cardTop(), cardHeight()), 2);
        step("ae2: card iron", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.IRON_INGOT)), Lookup.Mode.USES), 12);
        step("ae2: card top 2", () -> RYScope.LOG.info("[card] железо: верх {}, высота {}", cardTop(), cardHeight()), 2);
        shot("card_after_navigation");
        step("quit", () -> mc().stop(), 0);
    }

    private static int cardTop() {
        return mc().screen instanceof RecipeScreen rs ? rs.cardBounds()[1] : -1;
    }

    private static int cardHeight() {
        return mc().screen instanceof RecipeScreen rs ? rs.cardBounds()[3] : -1;
    }

    // --- сценарий ui: что поменялось в панелях и окнах ---

    private static void buildUi() {
        step("ui: setup", () -> {
            works.repka.ryscope.runtime.Bookmarks.addFolder("Механизмы");
            works.repka.ryscope.runtime.Board.goals().clear();
            works.repka.ryscope.runtime.Board.toggle(new ItemRes(new ItemStack(Items.COMPARATOR)), 2);
            works.repka.ryscope.runtime.Board.toggle(new ItemRes(new ItemStack(Items.PISTON)), 1);
            Config.get().listPaged = true;
            mc().setScreen(new InventoryScreen(mc().player));
        }, 25);
        step("ui: list", () -> {
            long groups = SearchService.results().stacks.stream().filter(s -> s instanceof works.repka.ryscope.runtime.Groups.GroupStack).count();
            RYScope.LOG.info("[ui] ячеек в списке: {}, свёрнутых групп: {}", SearchService.results().stacks.size(), groups);
            List<String> sample = new ArrayList<>();
            for (var s : SearchService.results().stacks) {
                if (s instanceof works.repka.ryscope.runtime.Groups.GroupStack g && g.group.id.startsWith("auto:") && sample.size() < 30) sample.add(g.name().getString() + " x" + g.count);
            }
            RYScope.LOG.info("[ui] автоматические группы, примеры: {}", sample);
        }, 2);
        shot("ui_inventory_paged");
        step("ui: next page", () -> Overlay.nextPageForTest(), 8);
        shot("ui_inventory_page2");
        step("ui: search bottom", () -> {
            Config.get().searchBottom = true;
            Config.get().savedSearches.clear();
            Config.get().savedSearches.add("uses:piston");
            Config.get().savedSearches.add("craftable");
        }, 6);
        step("ui: help hover", () -> {
            int sw = mc().getWindow().getGuiScaledWidth(), sh = mc().getWindow().getGuiScaledHeight();
            // значок «?»: слева от «i» и кнопки настроек в подвале списка
            moveMouse(sw - 4 - 5 - 10 - 15 - 13 + 5, sh - 4 - 5 - 10 + 1 + 5);
        }, 6);
        shot("ui_search_bottom_help");
        step("ui: focus bottom search", () -> {
            int sw = mc().getWindow().getGuiScaledWidth(), sh = mc().getWindow().getGuiScaledHeight();
            moveMouse(sw - 40, sh - 4 - 5 - 10 - 2 - 14 + 6);
            click(0);
        }, 6);
        shot("ui_search_bottom_saved");
        step("ui: search top", () -> {
            pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
            Config.get().searchBottom = false;
        }, 4);
        step("ui: goal remove", () -> {
            int[] p = Overlay.goalCenterForTest();
            if (p != null) {
                moveMouse(p[0], p[1]);
            }
        }, 4);
        step("ui: goal right click", () -> click(1), 8);
        step("ui: goals left", () -> RYScope.LOG.info("[ui] целей после ПКМ (должно быть: 1): {}", works.repka.ryscope.runtime.Board.goals().size()), 2);
        step("ui: paged off", () -> Config.get().listPaged = false, 2);
        step("ui: recipe focus", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES);
        }, 15);
        shot("ui_recipe_header");
        for (String part : new String[] {"forestry:carpenter", "ars_nouveau:enchanting_apparatus", "mekanism:crystallizing", "malum:spirit_infusion", "extendedcrafting:ender_crafting"}) {
            step("ui: category " + part, () -> {
                mc().setScreen(new InventoryScreen(mc().player));
                for (var c : Lookup.allCategories()) {
                    if (c.id.toString().equals(part)) {
                        RYScope.LOG.info("[ui] категория {}: {}", part, RecipeScreen.openCategory(c));
                        break;
                    }
                }
            }, 15);
            shot("ui_" + part.replace(':', '_'));
        }
        step("ui: planner", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            works.repka.ryscope.ui.PlannerScreen.open(new ItemRes(new ItemStack(Items.PISTON)));
        }, 2);
        step("ui: planner state", () -> RYScope.LOG.info("[ui] план через 2 тика: экран {}", mc().screen.getClass().getSimpleName()), 30);
        shot("ui_planner_done");
        step("ui: compare", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            works.repka.ryscope.ui.CompareScreen.open(new ItemRes(new ItemStack(Items.STICK)));
        }, 40);
        shot("ui_compare_done");
        step("ui: board hud", () -> mc().setScreen(null), 10);
        shot("ui_board_hud");
        step("ui: lens cow", () -> {
            var view = works.repka.ryscope.content.LootContent.forEntity(net.minecraft.world.entity.EntityType.COW);
            RYScope.LOG.info("[ui] корова показывает: {}", view == null ? "нет" : view.outputs().stream().map(o -> o.id().toString()).toList());
            var zombie = works.repka.ryscope.content.LootContent.forEntity(net.minecraft.world.entity.EntityType.ZOMBIE);
            RYScope.LOG.info("[ui] зомби показывает: {}", zombie == null ? "нет" : zombie.outputs().stream().map(o -> o.id().toString()).toList());
        }, 2);
        step("quit", () -> mc().stop(), 0);
    }

    /** Та же сцена, что в макете: снимки сравниваются с design/Main.dc.html при окне 1280×800 и масштабе 2. */
    private static void buildDesign() {
        step("design: setup", () -> {
            while (works.repka.ryscope.runtime.Bookmarks.folders().size() > 1) works.repka.ryscope.runtime.Bookmarks.removeActive();
            for (Stack s : new ArrayList<>(works.repka.ryscope.runtime.Bookmarks.list())) works.repka.ryscope.runtime.Bookmarks.toggle(s);
            for (var item : List.of(Items.STONE, Items.SAND, Items.SLIME_BALL, Items.DIAMOND, Items.OBSIDIAN, Items.COPPER_INGOT, Items.GRAVEL, Items.AMETHYST_SHARD)) {
                works.repka.ryscope.runtime.Bookmarks.toggle(new ItemRes(new ItemStack(item)));
            }
            works.repka.ryscope.runtime.Board.goals().clear();
            works.repka.ryscope.runtime.Board.toggle(new ItemRes(new ItemStack(Items.PISTON)), 12);
            works.repka.ryscope.runtime.Board.toggle(new ItemRes(new ItemStack(Items.BEACON)), 3);
            Config.get().listPaged = true;
            Config.get().searchBottom = false;
            Overlay.setSearch("");
            mc().setScreen(new InventoryScreen(mc().player));
        }, 30);
        shot("design_inventory");
        step("design: chips", () -> Overlay.setSearch("@create #c:plates лист"), 12);
        shot("design_search");
        step("design: recipe", () -> {
            Overlay.setSearch("");
            RecipeScreen.clearTabs();
            RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES);
        }, 30);
        shot("design_recipe");
        step("design: lens", () -> mc().setScreen(null), 10);
        shot("design_world");
        step("design: tree", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            works.repka.ryscope.ui.TreeScreen.open(new ItemRes(new ItemStack(Items.COMPARATOR)));
        }, 30);
        shot("design_tree");
        step("design: plan", () -> works.repka.ryscope.ui.PlannerScreen.open(new ItemRes(new ItemStack(Items.PISTON))), 40);
        shot("design_plan");
        step("design: compare", () -> works.repka.ryscope.ui.CompareScreen.open(new ItemRes(new ItemStack(Items.STICK))), 40);
        shot("design_compare");
        step("design: settings", () -> mc().setScreen(new works.repka.ryscope.ui.SettingsScreen(null)), 10);
        shot("design_settings");
        step("quit", () -> mc().stop(), 0);
    }

    // --- сценарий matrix: окна на двух разрешениях, четырёх GUI Scale и в двух темах ---

    private static void buildMatrix() {
        record Size(int w, int h) {}
        step("matrix: setup", () -> {
            Config.get().listPaged = true;
            Overlay.setSearch("");
            works.repka.ryscope.runtime.Board.goals().clear();
            works.repka.ryscope.runtime.Board.toggle(new ItemRes(new ItemStack(Items.PISTON)), 12);
        }, 10);
        for (Size size : List.of(new Size(2560, 1440), new Size(1920, 1080))) {
            for (int scale : List.of(2, 3, 4, 0)) {
                for (String theme : List.of("glass", "slate")) {
                    String tag = size.w + "_s" + scale + "_" + theme;
                    step("matrix: window " + tag, () -> {
                        mc().setScreen(null);
                        works.repka.ryscore.theme.ThemeManager.select("ryscore:" + theme);
                        mc().getWindow().setWindowed(size.w, size.h);
                        mc().options.guiScale().set(scale);
                        mc().resizeDisplay();
                        RYScope.LOG.info("[autotest] {}: окно {}×{}, GUI {}, пикселей на единицу {}, холст {}×{}", tag, mc().getWindow().getWidth(), mc().getWindow().getHeight(),
                            mc().getWindow().getGuiScale(), works.repka.ryscore.ui.UiScale.pixels(), works.repka.ryscore.ui.UiScale.width(), works.repka.ryscore.ui.UiScale.height());
                    }, 8);
                    step("matrix: inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 10);
                    shot("m_" + tag + "_inv");
                    step("matrix: card", () -> {
                        RecipeScreen.clearTabs();
                        RecipeScreen.open(new ItemRes(new ItemStack(Items.OAK_LOG)), Lookup.Mode.USES);
                        RecipeScreen.open(new ItemRes(new ItemStack(Items.PAPER)), Lookup.Mode.RECIPES);
                        RecipeScreen.open(new ItemRes(new ItemStack(Items.STICK)), Lookup.Mode.RECIPES);
                    }, 12);
                    shot("m_" + tag + "_card");
                }
            }
        }
        step("matrix: scale 5", () -> {
            mc().setScreen(null);
            works.repka.ryscore.theme.ThemeManager.select("ryscore:glass");
            mc().getWindow().setWindowed(2560, 1440);
            mc().options.guiScale().set(5);
            mc().resizeDisplay();
        }, 8);
        step("matrix: scale 5 card", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES);
        }, 12);
        shot("m_2560_s5_glass_card");
        step("matrix: collapse", () -> RecipeScreen.testExtraWidth = 420, 8);
        shot("m_collapsed_sidebar");
        step("matrix: expand sidebar", () -> {
            if (mc().screen instanceof RecipeScreen rs) {
                double k = works.repka.ryscore.ui.UiScale.factor();
                rs.mouseClicked((works.repka.ryscore.ui.UiScale.width() - 8) * k, 200 * k, 0);
            }
        }, 8);
        shot("m_expanded_sidebar");
        step("matrix: restore card", () -> {
            RecipeScreen.testExtraWidth = 0;
            Overlay.showCard();
            mc().setScreen(null);
        }, 10);
        shot("m_world");
        step("matrix: restore", () -> {
            works.repka.ryscore.theme.ThemeManager.select("ryscore:slate");
            mc().options.guiScale().set(3);
        }, 2);
        step("quit", () -> mc().stop(), 0);
    }

    // --- сценарий jeiscroll: колесо и ползунок внутри рецепта JEI, вкладки, упор у края ---

    private static boolean scrollFound;

    private static void buildJeiScroll() {
        step("jeiscroll: open", () -> {
            works.repka.ryscore.theme.ThemeManager.select("ryscore:glass");
            RYScope.LOG.info("[autotest] тема: {}", works.repka.ryscore.theme.ThemeManager.get().id);
            RecipeScreen.clearTabs();
            mc().setScreen(new InventoryScreen(mc().player));
            RecipeScreen.open(new ItemRes(new ItemStack(Items.OAK_LOG)), Lookup.Mode.RECIPES);
        }, 30);
        shot("jeiscroll_tabs");
        for (int i = 0; i < 40; i++) {
            final int index = i;
            step("jeiscroll: probe " + i, () -> {
                if (scrollFound || !(mc().screen instanceof RecipeScreen rs)) return;
                for (RecipeScreen.Placed p : rs.placed()) {
                    if (p.recipe() instanceof works.repka.ryscope.jei.JeiRecipeView<?>) {
                        boolean took = p.recipe().mouseScrolled(p.x() + p.recipe().width() / 2.0, p.y() + p.recipe().height() / 2.0, 0, -1);
                        if (took) {
                            scrollFound = true;
                            RYScope.LOG.info("[autotest] колесо принято рецептом JEI в группе {}", index);
                            return;
                        }
                    }
                }
                rs.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
            }, 6);
        }
        shot("jeiscroll_after_wheel");
        step("jeiscroll: drag", () -> {
            if (!(mc().screen instanceof RecipeScreen rs) || !scrollFound || rs.placed().isEmpty()) return;
            RecipeScreen.Placed p = rs.placed().get(0);
            // ползунок после одного щелчка колеса стоит у верха полосы
            double x = p.x() + p.recipe().width() - 10, y = p.y() + 30;
            double k = works.repka.ryscore.ui.UiScale.factor();
            boolean click = rs.mouseClicked(x * k, y * k, 0);
            boolean drag = rs.mouseDragged(x * k, (y + 20) * k, 0, 0, 20 * k);
            drag |= rs.mouseDragged(x * k, (y + 40) * k, 0, 0, 20 * k);
            rs.mouseReleased(x * k, (y + 40) * k, 0);
            RYScope.LOG.info("[autotest] ползунок: нажатие {}, перетаскивание {}", click, drag);
        }, 6);
        shot("jeiscroll_after_drag");
        step("jeiscroll: result", () -> RYScope.LOG.info("[autotest] рецепт с прокруткой найден: {}", scrollFound), 2);
        step("jeiscroll: slate", () -> works.repka.ryscore.theme.ThemeManager.select("ryscore:slate"), 6);
        shot("jeiscroll_slate");
        step("jeiscroll: world lens", () -> {
            mc().setScreen(null);
            command("setblock 0 -59 3 minecraft:iron_ore");
            works.repka.ryscope.client.Keys.forceLens = true;
        }, 20);
        shot("jeiscroll_world_lens");
        step("quit", () -> mc().stop(), 0);
    }

    // --- сценарий loot: что на самом деле лежит в таблицах мобов на сервере ---

    private static void buildLoot() {
        step("loot: dump", () -> {
            var server = mc().getSingleplayerServer();
            var ops = server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            for (String id : new String[] {"cow", "pig", "sheep", "chicken", "zombie"}) {
                var key = net.minecraft.resources.ResourceKey.create(Registries.LOOT_TABLE, net.minecraft.resources.ResourceLocation.withDefaultNamespace("entities/" + id));
                var table = server.reloadableRegistries().getLootTable(key);
                String text = net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.encodeStart(ops, table).result().map(Object::toString).orElse("не кодируется");
                RYScope.LOG.info("[loot] {} таблица: {}", id, text.length() > 5000 ? text.substring(0, 5000) : text);
                var type = net.minecraft.world.entity.EntityType.byString("minecraft:" + id).orElseThrow();
                var view = works.repka.ryscope.content.LootContent.forEntity(type);
                RYScope.LOG.info("[loot] {} показываем: {}", id, view == null ? "нет" : view.outputs().stream().map(o -> o.id() + " " + o.chance).toList());
            }
        }, 5);
        step("quit", () -> mc().stop(), 0);
    }

    // --- сценарий dock: панель кнопок FTB ---

    private static boolean ftbWidgetOnScreen() {
        return mc().screen.children().stream().anyMatch(works.repka.ryscope.ui.Dock::isFtbWidget);
    }

    private static void setOverlay(boolean on) {
        Config.get().overlayEnabled = on;
        mc().screen.resize(mc(), mc().screen.width, mc().screen.height);
    }

    private static void buildDock() {
        step("dock: open", () -> mc().setScreen(new InventoryScreen(mc().player)), 30);
        step("dock: state", () -> RYScope.LOG.info("[dock] панель видна: {}, виджет FTB на экране: {}, кнопки: {}",
            works.repka.ryscope.ui.Dock.visible(), ftbWidgetOnScreen(), works.repka.ryscope.ui.Dock.keys()), 2);
        shot("dock_inventory");
        step("dock: hover", () -> {
            int[] p = works.repka.ryscope.ui.Dock.center("ftbquests_quests");
            RYScope.LOG.info("[dock] кнопка квестов: {}", p == null ? "НЕТ" : p[0] + ", " + p[1]);
            if (p != null) moveMouse(p[0], p[1]);
        }, 8);
        shot("dock_hover");
        step("dock: click quests", () -> click(0), 40);
        step("dock: after click", () -> RYScope.LOG.info("[dock] после клика открыт экран: {}", mc().screen == null ? "нет" : mc().screen.getClass().getName()), 2);
        shot("dock_quests_opened");
        step("dock: back", () -> mc().setScreen(new InventoryScreen(mc().player)), 15);
        step("dock: chest size", () -> {
            mc().setScreen(new net.minecraft.client.gui.screens.inventory.CraftingScreen(
                new net.minecraft.world.inventory.CraftingMenu(0, mc().player.getInventory()), mc().player.getInventory(), net.minecraft.network.chat.Component.empty()));
        }, 15);
        step("dock: crafting state", () -> RYScope.LOG.info("[dock] экран верстака: панель видна {}, виджет FTB {}",
            works.repka.ryscope.ui.Dock.visible(), ftbWidgetOnScreen()), 2);
        shot("dock_crafting");
        step("dock: inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 15);
        step("dock: overlay off", () -> setOverlay(false), 15);
        step("dock: off state", () -> RYScope.LOG.info("[dock] наши панели выключены: виджет FTB вернулся: {}", ftbWidgetOnScreen()), 2);
        shot("dock_overlay_off");
        step("dock: overlay on", () -> setOverlay(true), 15);
        step("dock: on state", () -> RYScope.LOG.info("[dock] наши панели включены: панель видна {}, виджет FTB {}",
            works.repka.ryscope.ui.Dock.visible(), ftbWidgetOnScreen()), 2);
        step("quit", () -> mc().stop(), 0);
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
