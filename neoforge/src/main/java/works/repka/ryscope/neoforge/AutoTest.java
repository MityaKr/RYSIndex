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
        // все кадры, с окном и без: худший кадр и FPS для сценария leak
        bus.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Post e) -> {
            long now = System.nanoTime();
            if (gameFrameAt != 0) gameWorst = Math.max(gameWorst, now - gameFrameAt);
            gameFrameAt = now;
            gameFrames++;
        });
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
        if ("links".equals(System.getProperty("ryscope.autotest"))) {
            buildLinks();
            return;
        }
        if ("strip".equals(System.getProperty("ryscope.autotest"))) {
            buildStrip();
            return;
        }
        if ("help".equals(System.getProperty("ryscope.autotest"))) {
            buildHelp();
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
        if ("feedback".equals(System.getProperty("ryscope.autotest"))) {
            buildFeedback();
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
        if ("corekit".equals(System.getProperty("ryscope.autotest"))) {
            buildCoreKit();
            return;
        }
        if ("notices".equals(System.getProperty("ryscope.autotest"))) {
            buildNotices();
            return;
        }
        if ("perf".equals(System.getProperty("ryscope.autotest"))) {
            buildPerf();
            return;
        }
        if ("soak".equals(System.getProperty("ryscope.autotest"))) {
            buildSoak();
            return;
        }
        if ("leak".equals(System.getProperty("ryscope.autotest"))) {
            buildLeak();
            return;
        }
        if ("craft".equals(System.getProperty("ryscope.autotest"))) {
            buildCraft();
            return;
        }
        if ("next".equals(System.getProperty("ryscope.autotest"))) {
            buildNext();
            return;
        }
        if ("audit".equals(System.getProperty("ryscope.autotest"))) {
            buildAudit();
            return;
        }
        if ("batch".equals(System.getProperty("ryscope.autotest"))) {
            buildBatch();
            return;
        }
        if ("searchload".equals(System.getProperty("ryscope.autotest"))) {
            buildSearchLoad();
            return;
        }
        if ("focus".equals(System.getProperty("ryscope.autotest"))) {
            buildFocus();
            return;
        }
        if ("subst".equals(System.getProperty("ryscope.autotest"))) {
            buildSubstitution();
            return;
        }
        if ("grid".equals(System.getProperty("ryscope.autotest"))) {
            // замечание автора: предметы, которые уже лежат в сетке крафта, не учитывались в «есть в инвентаре/сети»
            step("grid: survival, 4 planks", () -> {
                command("gamemode survival");
                command("clear @s");
                command("give @s minecraft:oak_planks 4");
            }, 20);
            step("grid: inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 10);
            step("grid: pick planks", () -> mc().gameMode.handleInventoryMouseClick(mc().player.containerMenu.containerId, 36, 0, net.minecraft.world.inventory.ClickType.PICKUP, mc().player), 5);
            step("grid: put into the 2x2", () -> mc().gameMode.handleInventoryMouseClick(mc().player.containerMenu.containerId, 1, 0, net.minecraft.world.inventory.ClickType.PICKUP, mc().player), 15);
            step("grid: count", () -> RYScope.LOG.info("[grid] в сетке {}, в инвентаре {}, RYScope видит {}",
                mc().player.containerMenu.getSlot(1).getItem(), mc().player.getInventory().countItem(Items.OAK_PLANKS),
                works.repka.ryscope.runtime.InventoryView.snapshot().getOrDefault(Items.OAK_PLANKS, 0)), 2);
            step("grid: recipe", () -> {
                parkMouse();
                RecipeScreen.open(new ItemRes(new ItemStack(Items.CRAFTING_TABLE)), Lookup.Mode.RECIPES);
            }, 20);
            shot("grid_crafting_table");
            step("grid: back", () -> mc().setScreen(null), 5);
            step("quit", () -> mc().stop(), 0);
            return;
        }
        if ("energy".equals(System.getProperty("ryscope.autotest"))) {
            // замечание автора: шкала энергии рецепта Арены симуляций (Custom Machinery) выходит за окошко рецепта
            step("energy: wait jei", () -> works.repka.ryscope.runtime.Foreign.get() != null
                && works.repka.ryscope.runtime.Indexer.state() == works.repka.ryscope.runtime.Indexer.State.READY
                && works.repka.ryscope.runtime.Indexer.snapshot().stacks.size() > 5000, () -> {}, 40);
            step("energy: open", () -> {
                parkMouse();
                RecipeScreen.open(new ItemRes(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.parse("kubejs:neural_matrix")))), Lookup.Mode.RECIPES);
            }, 40);
            // раздел рецептов индукционной плавильни Modular Machinery Reborn: шкала энергии в (8, 8), высота рецепта 34
            final boolean[] mmr = {false};
            for (int i = 0; i < 15; i++) {
                step("energy: find mmr", () -> {
                    if (mmr[0] || !(mc().screen instanceof RecipeScreen rs) || rs.placed().isEmpty()) return;
                    try {
                        java.lang.reflect.Field f = rs.placed().get(0).recipe().getClass().getDeclaredField("category");
                        f.setAccessible(true);
                        Object c = f.get(rs.placed().get(0).recipe());
                        if (c instanceof mezz.jei.api.recipe.category.IRecipeCategory<?> cat && cat.getRecipeType().getUid().getPath().contains("simulation_arena") && !cat.getRecipeType().getUid().getNamespace().equals("kubejs")) mmr[0] = true;
                    } catch (ReflectiveOperationException ignored) {
                    }
                    if (!mmr[0]) mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
                }, 8);
            }
            step("energy: report", () -> {
                if (!(mc().screen instanceof RecipeScreen rs)) return;
                for (RecipeScreen.Placed p : rs.placed()) {
                    RYScope.LOG.info("[energy] вид {} {}x{} в {} {}", p.recipe().getClass().getSimpleName(), p.recipe().width(), p.recipe().height(), p.x(), p.y());
                    try {
                        java.lang.reflect.Field f = p.recipe().getClass().getDeclaredField("layout");
                        f.setAccessible(true);
                        if (f.get(p.recipe()) instanceof mezz.jei.api.gui.IRecipeLayoutDrawable<?> l) {
                            RYScope.LOG.info("[energy] категория {} {}x{}, rect {}, с рамкой {}", l.getRecipeCategory().getRecipeType().getUid(),
                                l.getRecipeCategory().getWidth(), l.getRecipeCategory().getHeight(), l.getRect(), l.getRectWithBorder());
                            for (var slot : l.getRecipeSlotsView().getSlotViews()) {
                                var r = slot instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable d ? d.getRect() : null;
                                RYScope.LOG.info("[energy] ячейка {} {} {}", slot.getRole(), r == null ? "-" : r.getX() + "," + r.getY() + " " + r.getWidth() + "x" + r.getHeight(), slot.getDisplayedIngredient().map(i -> String.valueOf(i.getIngredient())).orElse("-"));
                            }
                        }
                    } catch (ReflectiveOperationException e) {
                        RYScope.LOG.info("[energy] не JEI: {}", e.toString());
                    }
                }
            }, 5);
            shot("energy_arena");
            step("energy: restyle off", () -> works.repka.ryscope.runtime.Config.get().restyleRecipes = false, 5);
            shot("energy_arena_plain");
            step("energy: restyle on", () -> works.repka.ryscope.runtime.Config.get().restyleRecipes = true, 2);
            step("quit", () -> mc().stop(), 0);
            return;
        }
        if ("fluids".equals(System.getProperty("ryscope.autotest"))) {
            // замечание автора: в «Разделении жидкостей» Oritech сосуд на миг пустеет при смене жидкости, вода тёмная
            step("fluids: wait jei", () -> works.repka.ryscope.runtime.Foreign.get() != null
                && works.repka.ryscope.runtime.Indexer.state() == works.repka.ryscope.runtime.Indexer.State.READY
                && works.repka.ryscope.runtime.Indexer.snapshot().stacks.size() > 5000, () -> {}, 40);
            step("fluids: open", () -> {
                parkMouse();
                var groups = works.repka.ryscope.runtime.Foreign.get().categoryById(net.minecraft.resources.ResourceLocation.parse("oritech:centrifuge_fluid"));
                RYScope.LOG.info("[fluids] групп {}", groups.size());
                if (!groups.isEmpty()) RecipeScreen.openCategory(groups.get(0).category());
            }, 40);
            // страница с рецептом, где в сосуде перебирается несколько жидкостей
            final boolean[] many = {false};
            for (int page = 0; page < 30; page++) {
                step("fluids: find many", () -> {
                    if (many[0] || !(mc().screen instanceof RecipeScreen rs)) return;
                    for (RecipeScreen.Placed p : rs.placed()) {
                        try {
                            java.lang.reflect.Field f = p.recipe().getClass().getDeclaredField("layout");
                            f.setAccessible(true);
                            if (!(f.get(p.recipe()) instanceof mezz.jei.api.gui.IRecipeLayoutDrawable<?> l)) continue;
                            for (var slot : l.getRecipeSlotsView().getSlotViews()) {
                                if (slot.getAllIngredients().filter(i -> !(i.getIngredient() instanceof ItemStack)).count() > 1) many[0] = true;
                            }
                        } catch (ReflectiveOperationException ignored) {
                        }
                    }
                    if (!many[0]) mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0);
                }, 3);
            }
            step("fluids: found", () -> RYScope.LOG.info("[fluids] страница с перебором жидкостей найдена: {}", many[0]), 2);
            for (int tick = 0; tick < 70; tick++) {
                final int at = tick;
                step("fluids: tick", () -> {
                    if (!(mc().screen instanceof RecipeScreen rs)) return;
                    StringBuilder line = new StringBuilder();
                    for (RecipeScreen.Placed p : rs.placed()) {
                        try {
                            java.lang.reflect.Field f = p.recipe().getClass().getDeclaredField("layout");
                            f.setAccessible(true);
                            Object layout = f.get(p.recipe());
                            if (!(layout instanceof mezz.jei.api.gui.IRecipeLayoutDrawable<?> l)) continue;
                            for (var slot : l.getRecipeSlotsView().getSlotViews()) {
                                var shown = slot.getDisplayedIngredient();
                                boolean fluid = slot.getAllIngredients().anyMatch(i -> !(i.getIngredient() instanceof ItemStack));
                                if (!fluid) continue;
                                line.append(shown.map(i -> String.valueOf(i.getIngredient())).orElse("ПУСТО")).append(" | ");
                            }
                        } catch (ReflectiveOperationException e) {
                            line.append("? ");
                        }
                    }
                    RYScope.LOG.info("[fluids] {} {}", at, line);
                }, 1);
                if (tick % 5 == 0) shotWithToasts("fluids_t" + tick);
            }
            step("fluids: restyle off", () -> works.repka.ryscope.runtime.Config.get().restyleRecipes = false, 5);
            shot("fluids_plain");
            step("fluids: restyle on", () -> works.repka.ryscope.runtime.Config.get().restyleRecipes = true, 2);
            step("quit", () -> mc().stop(), 0);
            return;
        }
        if ("fb15".equals(System.getProperty("ryscope.autotest"))) {
            // замечания автора 15.11: подсказка Almost Unified над рецептом не режется краем окна, рейка способов предметами,
            // закладка ставится без уведомления
            step("fb15: wait jei", () -> works.repka.ryscope.runtime.Foreign.get() != null
                && works.repka.ryscope.runtime.Indexer.state() == works.repka.ryscope.runtime.Indexer.State.READY
                && works.repka.ryscope.runtime.Indexer.snapshot().stacks.size() > 5000, () -> {}, 40);
            step("fb15: open golden sheet", () -> {
                parkMouse();
                RecipeScreen.open(new ItemRes(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.parse("create:golden_sheet")))), Lookup.Mode.RECIPES);
            }, 40);
            shot("fb15_rail");
            final boolean[] found = {false};
            for (int group = 0; group < 12; group++) {
                for (int k = 0; k < 4; k++) {
                    final int dx = new int[] {-1, -3, 1, -5}[k], dy = new int[] {-1, -3, 1, -5}[k];
                    step("fb15: aim", () -> {
                        if (found[0] || !(mc().screen instanceof RecipeScreen rs) || rs.placed().isEmpty()) return;
                        RecipeScreen.Placed p = rs.placed().get(0);
                        moveMouse(works.repka.ryscore.ui.UiScale.toGui(p.x() + p.recipe().width() + dx), works.repka.ryscore.ui.UiScale.toGui(p.y() + p.recipe().height() + dy));
                    }, 4);
                    step("fb15: check", () -> {
                        if (!found[0] && works.repka.ryscope.ui.TooltipDefer.lastCount > 0) {
                            found[0] = true;
                            RYScope.LOG.info("[fb15] подсказка из рецепта отложена и нарисована поверх окна");
                        }
                    }, 1);
                }
                step("fb15: next group", () -> {
                    if (!found[0] && mc().screen != null) mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
                }, 15);
            }
            step("fb15: report", () -> RYScope.LOG.info("[fb15] подсказка найдена: {}", found[0]), 2);
            shot("fb15_au_tooltip");
            step("fb15: bookmark", () -> {
                parkMouse();
                works.repka.ryscope.ui.Overlay.toggleBookmarkForTest(new ItemRes(new ItemStack(Items.GOLD_INGOT)));
            }, 10);
            shotWithToasts("fb15_bookmark_no_toast");
            step("fb15: unbookmark", () -> works.repka.ryscope.ui.Overlay.toggleBookmarkForTest(new ItemRes(new ItemStack(Items.GOLD_INGOT))), 5);
            step("quit", () -> mc().stop(), 0);
            return;
        }
        if ("fb17".equals(System.getProperty("ryscope.autotest"))) {
            buildFb17();
            return;
        }
        if ("subtype".equals(System.getProperty("ryscope.autotest"))) {
            // JEI подключается позже входа в мир: без него нет подтипов, мода книг и превью структур
            step("subtype: wait jei", () -> works.repka.ryscope.runtime.Foreign.get() != null
                && works.repka.ryscope.runtime.Indexer.state() == works.repka.ryscope.runtime.Indexer.State.READY
                && works.repka.ryscope.runtime.Indexer.snapshot().stacks.size() > 5000, () -> {}, 40);
            step("subtype: recipes", () -> {
                try {
                    Object item = Class.forName("es.degrassi.mmreborn.common.item.ControllerItem")
                        .getMethod("makeMachineItem", net.minecraft.resources.ResourceLocation.class)
                        .invoke(null, net.minecraft.resources.ResourceLocation.parse("repys:kinetic_foundry"));
                    int typed = 0, plain = 0;
                    for (Lookup.Group g : Lookup.find(new ItemRes((ItemStack) item), Lookup.Mode.RECIPES)) typed += g.recipes().size();
                    for (Lookup.Group g : Lookup.find(new ItemRes(new ItemStack(((ItemStack) item).getItem())), Lookup.Mode.RECIPES)) plain += g.recipes().size();
                    RYScope.LOG.info("[subtype] рецептов у литейни {}, у контроллера без машины {}", typed, plain);
                    var foreign = works.repka.ryscope.runtime.Foreign.get();
                    RYScope.LOG.info("[subtype] источник {}, подтип литейни {}", foreign == null ? "нет" : foreign.name(),
                        foreign == null ? null : foreign.subtypeUid(new ItemRes((ItemStack) item)));
                    for (var st : works.repka.ryscope.runtime.Indexer.snapshot().stacks) {
                        if (!st.id().toString().equals("patchouli:guide_book")) continue;
                        RYScope.LOG.info("[subtype] книга {}: мод {}, подтип {}", st.name().getString(),
                            foreign == null ? null : foreign.displayModId(st), foreign == null ? null : foreign.subtypeUid(st));
                    }
                } catch (ReflectiveOperationException e) {
                    RYScope.LOG.error("[subtype]", e);
                }
                mc().setScreen(new InventoryScreen(mc().player));
            }, 20);
            for (String q : new String[] {"@naturesaura", "острота", "sharpness"}) {
                step("subtype: search " + q, () -> Overlay.setSearch(q), 40);
                step("subtype: result " + q, () -> {
                    var results = works.repka.ryscope.runtime.SearchService.results();
                    long books = results.stacks.stream().filter(st -> st.id().toString().equals("patchouli:guide_book")).count();
                    long enchanted = results.stacks.stream().filter(st -> st.id().toString().equals("minecraft:enchanted_book")).count();
                    RYScope.LOG.info("[subtype] «{}»: найдено {}, книг Patchouli {}, зачарованных книг {}", q, results.stacks.size(), books, enchanted);
                }, 2);
            }
            step("subtype: clear", () -> Overlay.setSearch(""), 5);
            step("variants: open", () -> {
                parkMouse();
                try {
                    Object item = Class.forName("es.degrassi.mmreborn.common.item.ControllerItem")
                        .getMethod("makeMachineItem", net.minecraft.resources.ResourceLocation.class)
                        .invoke(null, net.minecraft.resources.ResourceLocation.parse("repys:kinetic_foundry"));
                    RecipeScreen.open(new ItemRes((ItemStack) item), Lookup.Mode.USES);
                } catch (ReflectiveOperationException e) {
                    RYScope.LOG.error("[variants]", e);
                }
            }, 40);
            for (int tab = 0; tab < 4; tab++) {
                step("variants: find structure", () -> {
                    if (structureOnScreen() != null) return;
                    mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
                }, 40);
            }
            step("variants: click", () -> {
                var view = structureOnScreen();
                if (view == null) {
                    RYScope.LOG.info("[variants] превью структуры не найдено");
                    return;
                }
                try {
                    java.lang.reflect.Field inputsF = view.getClass().getDeclaredField("inputs");
                    java.lang.reflect.Field lx = view.getClass().getDeclaredField("lastX");
                    java.lang.reflect.Field ly = view.getClass().getDeclaredField("lastY");
                    for (var f : new java.lang.reflect.Field[] {inputsF, lx, ly}) f.setAccessible(true);
                    List<?> inputs = (List<?>) inputsF.get(view);
                    for (int i = 0; i < inputs.size(); i++) {
                        int n = ((works.repka.ryscope.model.Ing) inputs.get(i)).options.size();
                        if (n < 2) continue;
                        int x = lx.getInt(view) + i % 10 * 20 + 9, y = ly.getInt(view) + 132 + 4 + i / 10 * 20 + 9;
                        boolean taken = view.clickBeforeStack(x, y, 0);
                        RYScope.LOG.info("[variants] ячейка {}: вариантов {}, клик принят {}", i, n, taken);
                        return;
                    }
                    RYScope.LOG.info("[variants] ячеек с вариантами нет");
                } catch (ReflectiveOperationException e) {
                    RYScope.LOG.error("[variants]", e);
                }
            }, 10);
            shot("variants_popup");
            step("quit", () -> mc().stop(), 0);
            return;
        }
        if ("lensyield".equals(System.getProperty("ryscope.autotest"))) {
            step("lens: inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 20);
            step("lens: check", () -> {
                works.repka.ryscope.client.Keys.forceLens = true;
                mc().options.advancedItemTooltips = false;
                boolean plain = works.repka.ryscope.ui.Lens.held();
                mc().options.advancedItemTooltips = true;
                boolean advanced = works.repka.ryscope.ui.Lens.held();
                mc().options.advancedItemTooltips = false;
                works.repka.ryscope.client.Keys.forceLens = false;
                RYScope.LOG.info("[lens] без F3+H линза {}, с F3+H линза {}", plain, advanced);
            }, 2);
            step("quit", () -> mc().stop(), 0);
            return;
        }
        if ("states".equals(System.getProperty("ryscope.autotest"))) {
            buildStates();
            return;
        }
        if ("scroll".equals(System.getProperty("ryscope.autotest"))) {
            buildScroll();
            return;
        }
        if ("ghost".equals(System.getProperty("ryscope.autotest"))) {
            buildGhost();
            return;
        }
        if ("originals".equals(System.getProperty("ryscope.autotest"))) {
            buildOriginals();
            return;
        }
        if ("jeiscale".equals(System.getProperty("ryscope.autotest"))) {
            buildJeiScale();
            return;
        }
        if ("mekcalc".equals(System.getProperty("ryscope.autotest"))) {
            buildMekCalc();
            return;
        }
        if ("mmr".equals(System.getProperty("ryscope.autotest"))) {
            buildMmr();
            return;
        }
        if ("detach".equals(System.getProperty("ryscope.autotest"))) {
            buildDetach();
            return;
        }
        if ("supply".equals(System.getProperty("ryscope.autotest"))) {
            buildSupply();
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

    /** Строки статуса открытых рецептов. */
    private static String statusLines() {
        try {
            java.lang.reflect.Field f = RecipeScreen.class.getDeclaredField("statuses");
            f.setAccessible(true);
            StringBuilder out = new StringBuilder();
            for (Object status : ((java.util.Map<?, ?>) f.get(mc().screen)).values()) {
                if (status == null) continue;
                java.lang.reflect.Field head = status.getClass().getDeclaredField("head");
                java.lang.reflect.Field tail = status.getClass().getDeclaredField("tail");
                head.setAccessible(true);
                tail.setAccessible(true);
                out.append(" [").append(head.get(status)).append(' ').append(tail.get(status)).append(']');
            }
            return out.toString();
        } catch (ReflectiveOperationException | ClassCastException e) {
            return "не прочитано: " + e;
        }
    }

    private static net.minecraft.client.gui.components.EditBox searchBox() {
        try {
            java.lang.reflect.Method m = Overlay.class.getDeclaredMethod("search");
            m.setAccessible(true);
            return (net.minecraft.client.gui.components.EditBox) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void searchKey(int key) {
        Overlay.keyPressed(mc().screen, key, 0, 0);
    }

    private static void overlayCall(String method) {
        try {
            java.lang.reflect.Method m = Overlay.class.getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Наибольшая и текущая прокрутка страницы в карточке рецептов. */
    private static int[] scrollState() {
        try {
            var screen = mc().screen;
            java.lang.reflect.Method max = RecipeScreen.class.getDeclaredMethod("maxScroll");
            java.lang.reflect.Field now = RecipeScreen.class.getDeclaredField("pageScroll");
            max.setAccessible(true);
            now.setAccessible(true);
            return new int[] {(int) max.invoke(screen), now.getInt(screen)};
        } catch (ReflectiveOperationException | ClassCastException e) {
            return new int[] {-1, -1};
        }
    }

    /** Карточка постоянной высоты: обычный рецепт, затем высокая страница на низком окне с прокруткой. */
    private static void buildScroll() {
        step("scroll: piston", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES), 30);
        shot("scroll_piston");
        step("scroll: wheel", () -> RecipeScreen.open(new ItemRes(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.parse("create:crushing_wheel")))), Lookup.Mode.USES), 30);
        shot("scroll_wheel_uses");
        step("scroll: low window", () -> org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 1280, 250), 30);
        step("scroll: structure", () -> {
            try {
                Object item = Class.forName("es.degrassi.mmreborn.common.item.ControllerItem")
                    .getMethod("makeMachineItem", net.minecraft.resources.ResourceLocation.class)
                    .invoke(null, net.minecraft.resources.ResourceLocation.parse("repys:kinetic_foundry"));
                RecipeScreen.open(new ItemRes((ItemStack) item), Lookup.Mode.USES);
            } catch (ReflectiveOperationException e) {
                RYScope.LOG.error("[scroll] controller", e);
            }
        }, 60);
        for (int tab = 0; tab < 4; tab++) {
            step("scroll: find tall tab", () -> {
                if (scrollState()[0] > 0) return;
                mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
            }, 40);
        }
        step("scroll: state", () -> RYScope.LOG.info("[scroll] прокрутка до {}, сейчас {}", scrollState()[0], scrollState()[1]), 2);
        shot("scroll_structure_top");
        for (int i = 0; i < 6; i++) {
            step("scroll: down", () -> {
                var screen = mc().screen;
                // у правого края карточки, мимо 3D-превью: над ним колесо приближает модель
                int[] b = ((RecipeScreen) screen).cardBounds();
                double x = works.repka.ryscore.ui.UiScale.toGui(b[0] + b[2] - 8), y = works.repka.ryscore.ui.UiScale.toGui(b[1] + b[3] / 2.0);
                screen.mouseScrolled(x, y, 0, -1);
            }, 3);
        }
        step("scroll: after", () -> RYScope.LOG.info("[scroll] после колеса: до {}, сейчас {}", scrollState()[0], scrollState()[1]), 2);
        shot("scroll_structure_down");
        step("scroll: restore", () -> org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 1280, 720), 20);
        step("quit", () -> mc().stop(), 0);
    }

    private static void setGhost(Object stack) {
        try {
            java.lang.reflect.Field f = Overlay.class.getDeclaredField("ghostStack");
            f.setAccessible(true);
            f.set(null, stack);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Перетаскивание предмета в фильтр Create и порядок категорий колёс. */
    private static void buildGhost() {
        step("ghost: order", () -> {
            StringBuilder out = new StringBuilder();
            for (Lookup.Group g : Lookup.find(new ItemRes(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.parse("create:crushing_wheel")))), Lookup.Mode.USES)) out.append(' ').append(g.category().id);
            RYScope.LOG.info("[ghost] порядок категорий колёс в использованиях:{}", out);
            StringBuilder rec = new StringBuilder();
            for (Lookup.Group g : Lookup.find(new ItemRes(new ItemStack(Items.IRON_INGOT)), Lookup.Mode.RECIPES)) rec.append(' ').append(g.category().id);
            RYScope.LOG.info("[ghost] порядок категорий рецептов слитка:{}", rec);
            command("clear @s");
            command("give @s create:filter");
        }, 20);
        step("ghost: hold", () -> mc().player.getInventory().selected = 0, 5);
        step("ghost: open", () -> mc().gameMode.useItem(mc().player, InteractionHand.MAIN_HAND), 30);
        ItemRes iron = new ItemRes(new ItemStack(Items.IRON_INGOT));
        step("ghost: start", () -> {
            var foreign = works.repka.ryscope.runtime.Foreign.get();
            boolean any = foreign != null && foreign.ghostStart(mc().screen, iron);
            RYScope.LOG.info("[ghost] экран {}, целей {}", mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName(),
                foreign == null ? -1 : foreign.ghostAreas().size());
            logAreas();
            setGhost(iron);
        }, 5);
        shot("ghost_drag");
        step("ghost: drop", () -> {
            var foreign = works.repka.ryscope.runtime.Foreign.get();
            List<int[]> areas = foreign.ghostAreas();
            boolean placed = !areas.isEmpty() && foreign.ghostDrop(areas.get(0)[0] + areas.get(0)[2] / 2.0, areas.get(0)[1] + areas.get(0)[3] / 2.0);
            foreign.ghostEnd();
            setGhost(null);
            RYScope.LOG.info("[ghost] положен: {}", placed);
        }, 10);
        step("ghost: check", () -> {
            List<String> items = new ArrayList<>();
            for (var slot : mc().player.containerMenu.slots) {
                if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory) && !slot.getItem().isEmpty()) items.add(slot.getItem().getItem().toString());
            }
            RYScope.LOG.info("[ghost] в ячейках фильтра: {}", items);
        }, 3);
        shot("ghost_done");
        step("quit", () -> mc().stop(), 0);
    }

    /** Рецепты модов: вид JEI от разработчиков, наши раскладки только там, где оригинала нет. */
    private static void buildOriginals() {
        String[] items = {"create:crushing_wheel", "ae2:inscriber", "mekanism:crusher"};
        for (String id : items) {
            step("originals: " + id, () -> {
                ItemStack stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id)));
                StringBuilder out = new StringBuilder();
                for (Lookup.Group g : Lookup.find(new ItemRes(stack), Lookup.Mode.USES)) {
                    int ours = 0, jei = 0;
                    for (var r : g.recipes()) {
                        if (r instanceof works.repka.ryscope.content.NativeRecipe n && n.fromLayout) ours++;
                        else jei++;
                    }
                    out.append(" [").append(g.category().id).append(": JEI ").append(jei).append(", наших ").append(ours).append(']');
                }
                RYScope.LOG.info("[originals] {}:{}", id, out);
                RecipeScreen.open(new ItemRes(stack), Lookup.Mode.USES);
            }, 40);
            shot("originals_" + id.replace(':', '_'));
        }
        step("quit", () -> mc().stop(), 0);
    }

    /**
     * Масштаб рецептов JEI (0.2.10): у каждой категории множитель, размер и влезает ли рецепт в предел ширины; снимок окна.
     * Предмет, режим и пространство имён категории, которую показать.
     */
    private static void buildJeiScale() {
        String[][] cases = {
            {"mekanism:thermal_evaporation_controller", "USES", "jei_mekanism_multiblocks"},
            {"create:andesite_alloy", "RECIPES", "create"},
            {"create:mechanical_press", "USES", "create"},
            {"extendedcrafting:ultimate_table", "USES", "extendedcrafting"},
            {"minecraft:iron_ingot", "RECIPES", "minecraft"},
            {"minecraft:crafting_table", "USES", "minecraft"},
        };
        int[] bad = {0}, seen = {0};
        for (String[] c : cases) {
            String id = c[0], ns = c[2];
            Lookup.Mode mode = Lookup.Mode.valueOf(c[1]);
            java.util.function.Predicate<works.repka.ryscope.model.Category> pick = cat -> cat.id.getNamespace().equals(ns);
            step("jeiscale: " + id, () -> {
                ItemStack stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id)));
                int limit = works.repka.ryscope.jei.JeiScale.maxWidth();
                for (Lookup.Group g : Lookup.find(new ItemRes(stack), mode)) {
                    if (g.recipes().isEmpty()) continue;
                    var r = g.recipes().get(0);
                    boolean jei = r instanceof works.repka.ryscope.jei.JeiRecipeView<?>;
                    if (jei) {
                        seen[0]++;
                        if (r.width() > limit + 1) bad[0]++;
                    }
                    RYScope.LOG.info("[jeiscale] {} {} {}: {} {}x{}, предел {}, пикселей {}", id, mode, g.category().id, jei ? "JEI" : "наш",
                        r.width(), r.height(), limit, works.repka.ryscore.ui.UiScale.pixels());
                }
                RecipeScreen.open(new ItemRes(stack), mode);
            }, 40);
            step("jeiscale: select " + ns, () -> {
                boolean found = mc().screen instanceof RecipeScreen rs && rs.selectGroupForTest(pick);
                RYScope.LOG.info("[jeiscale] {}: категория {} выбрана: {}", id, ns, found);
                parkMouse();
            }, 20);
            step("jeiscale: window " + ns, () -> {
                int[] card = mc().screen instanceof RecipeScreen rs ? rs.cardBounds() : new int[4];
                if (card[2] > 280) bad[0]++;
                RYScope.LOG.info("[jeiscale] {}: окно {}x{}", id, card[2], card[3]);
                parkMouse();
            }, 20);
            shot("jeiscale_" + id.replace(':', '_'));
        }
        step("jeiscale: verdict", () -> RYScope.LOG.info("[jeiscale] {}: рецептов JEI {}, шире предела {}",
            seen[0] > 0 && bad[0] == 0 ? "PASS" : "FAIL", seen[0], bad[0]), 1);
        step("quit", () -> mc().stop(), 0);
    }

    /**
     * Калькуляторы JEI Mekanism Multiblocks: категория 180×120 прячет затраты ячейками в (9999, 9999), карточка не должна
     * растягиваться под них (0.2.9).
     */
    private static void buildMekCalc() {
        java.util.function.Predicate<works.repka.ryscope.model.Category> calc = c -> c.id.getNamespace().equals("jei_mekanism_multiblocks");
        step("mekcalc: sizes", () -> {
            ItemStack stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("mekanism:thermal_evaporation_controller")));
            int seen = 0, bad = 0;
            for (Lookup.Group g : Lookup.find(new ItemRes(stack), Lookup.Mode.USES)) {
                if (!calc.test(g.category())) continue;
                for (var r : g.recipes()) {
                    seen++;
                    if (r.width() > 400 || r.height() > 300) bad++;
                    RYScope.LOG.info("[mekcalc] {} {}x{}", g.category().id, r.width(), r.height());
                }
            }
            RYScope.LOG.info("[mekcalc] {}: рецептов {}, слишком больших {}", seen > 0 && bad == 0 ? "PASS" : "FAIL", seen, bad);
            RecipeScreen.open(new ItemRes(stack), Lookup.Mode.USES);
        }, 40);
        step("mekcalc: select", () -> {
            boolean found = mc().screen instanceof RecipeScreen rs && rs.selectGroupForTest(calc);
            RYScope.LOG.info("[mekcalc] категория калькулятора выбрана: {}", found);
            parkMouse();
        }, 20);
        shot("mekcalc_evaporation");
        step("quit", () -> mc().stop(), 0);
    }

    /** Окно контроллера Modular Machinery: всё окно рисуется внутри его renderBackground. */
    private static void buildMmr() {
        step("mmr: place", () -> {
            command("fill 3 -60 3 3 -58 3 minecraft:air");
            command("setblock 3 -60 3 modular_machinery_reborn:controller");
        }, 20);
        step("mmr: open", () -> use(new BlockPos(3, -60, 3), Direction.NORTH, new Vec3(3.5, -59.5, 3.0)), 30);
        step("mmr: state", () -> {
            RYScope.LOG.info("[mmr] экран: {}", mc().screen == null ? "нет" : mc().screen.getClass().getName());
            logAreas();
        }, 5);
        shot("mmr_controller");
        step("quit", () -> mc().stop(), 0);
    }

    private static void buildDetach() {
        step("detach: marks", () -> {
            works.repka.ryscope.runtime.Config.get().bookmarksDetached = false;
            int added = 0;
            for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                ItemRes res = new ItemRes(new ItemStack(item));
                if (!works.repka.ryscope.runtime.Bookmarks.contains(res)) works.repka.ryscope.runtime.Bookmarks.toggle(res);
                if (++added >= 30) break;
            }
            mc().setScreen(new InventoryScreen(mc().player));
        }, 30);
        shot("detach_pinned");
        step("detach: unpin", () -> overlayCall("toggleDetached"), 10);
        shot("detach_left");
        step("detach: recipes", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES), 30);
        shot("detach_left_recipes");
        step("detach: glass", () -> ThemeManager.select("ryscore:glass"), 10);
        shot("detach_left_glass");
        step("detach: slate", () -> {
            ThemeManager.select("ryscore:slate");
            mc().setScreen(new InventoryScreen(mc().player));
        }, 10);
        step("detach: pin back", () -> overlayCall("toggleDetached"), 10);
        shot("detach_back");
        step("quit", () -> mc().stop(), 0);
    }

    private static void buildSupply() {
        step("supply: survival", () -> command("gamemode survival"), 10);
        step("supply: clear", () -> command("clear @s"), 10);
        step("supply: 3 ingots", () -> command("give @s minecraft:iron_ingot 3"), 20);
        step("supply: open", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            RecipeScreen.open(new ItemRes(new ItemStack(Items.IRON_BLOCK)), Lookup.Mode.RECIPES);
        }, 40);
        step("supply: report 3", () -> RYScope.LOG.info("[supply] 3 слитка:{}", statusLines()), 2);
        shot("supply_3");
        step("supply: 6 more", () -> command("give @s minecraft:iron_ingot 6"), 30);
        step("supply: report 9", () -> RYScope.LOG.info("[supply] 9 слитков:{}", statusLines()), 2);
        shot("supply_9");
        step("supply: inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 20);
        String[] queries = {"iron", "gold", "@create"};
        for (String q : queries) {
            step("history: type " + q, () -> {
                net.minecraft.client.gui.components.EditBox box = searchBox();
                box.setFocused(true);
                box.setValue(q);
            }, 3);
            step("history: leave", () -> searchKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER), 3);
        }
        step("history: focus", () -> {
            searchBox().setFocused(true);
            searchBox().setValue("");
        }, 3);
        StringBuilder seen = new StringBuilder();
        int[] keys = {org.lwjgl.glfw.GLFW.GLFW_KEY_UP, org.lwjgl.glfw.GLFW.GLFW_KEY_UP, org.lwjgl.glfw.GLFW.GLFW_KEY_UP, org.lwjgl.glfw.GLFW.GLFW_KEY_UP, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN};
        for (int key : keys) {
            step("history: key", () -> {
                searchKey(key);
                seen.append(key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP ? " ↑" : " ↓").append('«').append(searchBox().getValue()).append('»');
            }, 2);
        }
        step("history: report", () -> RYScope.LOG.info("[supply] история поиска:{}", seen), 2);
        step("history: done", () -> searchKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE), 2);
        step("quit", () -> mc().stop(), 0);
    }

    /** Пункт 5: общая сумма по целям, станции, кеш руд. */
    private static void buildNext() {
        step("next: wait jei", () -> works.repka.ryscope.runtime.Foreign.get() != null
            && works.repka.ryscope.runtime.Indexer.state() == works.repka.ryscope.runtime.Indexer.State.READY
            && works.repka.ryscope.runtime.Indexer.snapshot().stacks.size() > 5000, () -> {
                command("clear @s");
                command("gamemode survival @s");
            }, 40);
        ItemRes pick = new ItemRes(new ItemStack(Items.WOODEN_PICKAXE)), shovel = new ItemRes(new ItemStack(Items.WOODEN_SHOVEL));
        step("next: goals", () -> {
            works.repka.ryscope.runtime.Board.set(pick, 1, null);
            works.repka.ryscope.runtime.Board.set(shovel, 1, null);
            works.repka.ryscope.runtime.Config.get().stickerMode = "sum";
        }, 10);
        step("next: combined", () -> works.repka.ryscope.runtime.Board.combined() != null || stuck > 2000, () -> {
            var all = works.repka.ryscope.runtime.Board.combined();
            long sum = 0, separate = 0;
            if (all != null) for (var r : all.raw.values()) sum += r.need;
            List<String> perGoal = new ArrayList<>();
            for (var g : works.repka.ryscope.runtime.Board.goals()) {
                // отдельное дерево цели считаем здесь же, синхронно: дерево доски может быть ещё не готово
                var t = new works.repka.ryscope.runtime.CraftTree(g.stack, false);
                t.setAmount(g.amount);
                long own = 0;
                for (var r : t.raw.values()) own += r.need;
                separate += own;
                perGoal.add(g.stack.id().getPath() + "×" + g.amount + "=" + own);
            }
            RYScope.LOG.info("[next] сырьё по целям: {}", perGoal);
            RYScope.LOG.info("[next] сырьё суммой {}, по целям отдельно {}, целей {}, неполный {}", sum, separate,
                all == null ? -1 : all.goalCount(), all != null && all.incomplete);
            if (all != null) {
                List<String> raw = new ArrayList<>();
                for (var r : all.raw.values()) raw.add(r.stack.id() + " x" + r.need);
                RYScope.LOG.info("[next] сырьё суммы: {}", raw);
            }
        }, 5);
        step("next: sticks", () -> {
            nextCombinedBefore = System.identityHashCode(works.repka.ryscope.runtime.Board.combined());
            command("give @s minecraft:stick 2");
        }, 200);
        step("next: combined state", () -> {
            try {
                java.lang.reflect.Field building = works.repka.ryscope.runtime.Board.class.getDeclaredField("combinedBuilding");
                java.lang.reflect.Field failed = works.repka.ryscope.runtime.Board.class.getDeclaredField("combinedFailed");
                building.setAccessible(true);
                failed.setAccessible(true);
                Object b = building.get(null);
                var all = works.repka.ryscope.runtime.Board.combined();
                if (all != null) {
                    List<String> dump = new ArrayList<>();
                    java.util.ArrayDeque<works.repka.ryscope.runtime.CraftTree.Node> q = new java.util.ArrayDeque<>(all.root.children);
                    while (!q.isEmpty()) {
                        var n = q.poll();
                        if (n.depth > 3) continue;
                        if (n.depth == 1 && !n.stack.id().getPath().startsWith("wooden")) continue;
                        dump.add(n.depth + ":" + n.stack.id().getPath() + " нужно " + n.needed + " инв " + n.fromInventory + " изл " + n.fromSurplus + " крафтов " + n.crafts + " выбор " + n.choice);
                        q.addAll(n.children);
                    }
                    RYScope.LOG.info("[next] ветки кирки и лопаты: {}", dump);
                }
                RYScope.LOG.info("[next] сумма сменилась: {}, строится: {}, готова ли строящаяся: {}, сбой: {}, просмотрено {}",
                    nextCombinedBefore != System.identityHashCode(works.repka.ryscope.runtime.Board.combined()), b != null,
                    b != null && ((works.repka.ryscope.runtime.CraftTree) b).ready(), failed.get(null), works.repka.ryscope.runtime.Board.combinedExamined());
            } catch (ReflectiveOperationException e) {
                RYScope.LOG.error("[next]", e);
            }
        }, 2);
        step("next: sticks counted", () -> {
            var all = works.repka.ryscope.runtime.Board.combined();
            var leaf = all == null ? null : all.leaves.get(Items.STICK);
            RYScope.LOG.info("[next] палки в итоге суммы: из инвентаря {}; учёт инвентаря {}, палок в инвентаре {}, строки итога {}",
                leaf == null ? "нет строки" : leaf.inventory, works.repka.ryscope.runtime.InventoryView.tracking(),
                works.repka.ryscope.runtime.InventoryView.count(new ItemRes(new ItemStack(Items.STICK))),
                all == null ? null : all.leaves.values().stream().map(l -> l.stack.id().getPath() + ":" + l.need + "/" + l.inventory).toList());
        }, 2);
        step("next: all goals screen", works.repka.ryscope.ui.TreeScreen::openAll, 40);
        step("next: all goals state", () -> RYScope.LOG.info("[next] экран «Все цели»: {}",
            mc().screen instanceof works.repka.ryscope.ui.TreeScreen ts && ts.showsAll()), 2);
        shot("next_all_goals");
        step("next: stations", () -> {
            mc().setScreen(null);
            var tree = new works.repka.ryscope.runtime.CraftTree(new ItemRes(new ItemStack(Items.DISPENSER)), false);
            List<String> cats = new ArrayList<>();
            java.util.ArrayDeque<works.repka.ryscope.runtime.CraftTree.Node> queue = new java.util.ArrayDeque<>(List.of(tree.root));
            while (!queue.isEmpty()) {
                var n = queue.poll();
                var o = n.chosen();
                if (o != null) cats.add(n.stack.id().getPath() + "→" + o.category.id + " " + o.recipe.id()
                    + " src=" + n.sources + (n.stationMissing ? " (нет станции)" : ""));
                queue.addAll(n.children);
            }
            List<String> missing = new ArrayList<>();
            for (var c : tree.missingStations) missing.add(c.id.toString());
            RYScope.LOG.info("[next] раздатчик: шагов {}, выбор {}, нужны станции {}", tree.steps, cats, missing);
        }, 5);
        step("next: ores cache", () -> {
            java.io.File f = new java.io.File(mc().gameDirectory, "config/ryscope/ores-cache.json");
            RYScope.LOG.info("[next] кеш руд: есть {}, размер {}", f.exists(), f.length());
        }, 2);
        step("next: ore card", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.IRON_ORE)), Lookup.Mode.RECIPES), 40);
        shot("next_ore_card");
        step("next: cleanup", () -> {
            works.repka.ryscope.runtime.Board.remove(pick);
            works.repka.ryscope.runtime.Board.remove(shovel);
            works.repka.ryscope.runtime.Config.get().stickerMode = "goals";
            command("gamemode creative @s");
        }, 5);
        step("quit", () -> mc().stop(), 0);
    }

    private static long soakFrames, soakAt;
    private static long gameFrames, gameFrameAt, gameWorst, leakFrames, leakAt;
    /** Предел длительности автотеста в тиках: 10 минут, долгому прогону больше. */
    private static int tickLimit = 20 * 600;

    /** Размер статической коллекции или карты по имени класса и поля, -1 если не нашлось. */
    private static int staticSize(String className, String field) {
        try {
            java.lang.reflect.Field f = Class.forName(className).getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof java.util.Map<?, ?> m) return m.size();
            if (v instanceof java.util.Collection<?> c) return c.size();
            if (v instanceof it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> m) return m.size();
            return -2;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void soakReport(int minute) {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long now = System.currentTimeMillis();
        double fps = soakAt == 0 ? 0 : (frames - soakFrames) * 1000.0 / Math.max(1, now - soakAt);
        soakFrames = frames;
        soakAt = now;
        int textures = -1;
        try {
            java.lang.reflect.Field f = net.minecraft.client.renderer.texture.TextureManager.class.getDeclaredField("byPath");
            f.setAccessible(true);
            var map = (java.util.Map<?, ?>) f.get(mc().getTextureManager());
            textures = map.size();
            java.util.TreeMap<String, Integer> byMod = new java.util.TreeMap<>();
            for (Object k : map.keySet()) {
                String id = k.toString();
                String key = id.contains("/") ? id.substring(0, id.indexOf('/')) : id;
                byMod.merge(key, 1, Integer::sum);
            }
            RYScope.LOG.info("[soak] текстуры по модам: {}", byMod);
        } catch (Throwable ignored) {
        }
        RYScope.LOG.info("[soak] минута {}: память {} МБ, FPS {}, текстур {}, страниц атласа {}, глифов {}, фигур {}, значков {}, ячеек {}, живых структур {}, общий кеш дерева {}, линза {}",
            minute, used, String.format("%.0f", fps), textures,
            staticSize("works.repka.ryscore.ui.Atlas", "PAGES"), staticSize("works.repka.ryscore.ui.Type", "GLYPHS"),
            staticSize("works.repka.ryscore.ui.Shapes", "CACHE"), staticSize("works.repka.ryscore.ui.Icons", "CACHE"),
            staticSize("works.repka.ryscore.ui.Tiles", "CACHE"), staticSize("works.repka.ryscope.jei.StructureRecipeView", "LIVE"),
            staticSize("works.repka.ryscope.runtime.CraftTree", "MAP"), staticSize("works.repka.ryscope.ui.Lens", "found"));
    }

    /** Долгая игра: типичные действия по кругу, раз в минуту память, FPS и размеры кешей. */
    private static void buildSoak() {
        int minutes = Integer.getInteger("ryscope.soak.minutes", 25);
        tickLimit = 20 * 60 * (minutes + 10);
        step("soak: wait", () -> works.repka.ryscope.runtime.Foreign.get() != null
            && works.repka.ryscope.runtime.Indexer.state() == works.repka.ryscope.runtime.Indexer.State.READY
            && works.repka.ryscope.runtime.Indexer.snapshot().stacks.size() > 5000, () -> {
                mc().options.enableVsync().set(false);
                mc().options.framerateLimit().set(260);
                mc().getWindow().updateVsync(false);
            }, 40);
        step("soak: start", () -> soakReport(0), 2);
        String[] queries = {"iron", "железн", "@create", "меч", "piston", "gold", "$острота", "plate", "уголь", "gear"};
        int cycle = 0;
        for (int minute = 1; minute <= minutes; minute++) {
            for (int k = 0; k < 6; k++, cycle++) {
                final int c = cycle;
                step("soak: inventory", () -> {
                    mc().setScreen(new InventoryScreen(mc().player));
                    Overlay.setSearch(queries[c % queries.length]);
                }, 30);
                step("soak: card", () -> {
                    var stacks = works.repka.ryscope.runtime.Indexer.snapshot().stacks;
                    Stack st = stacks.get((c * 7919) % stacks.size());
                    RecipeScreen.open(st, c % 2 == 0 ? Lookup.Mode.RECIPES : Lookup.Mode.USES);
                }, 20);
                step("soak: page", () -> {
                    if (mc().screen != null) mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0);
                }, 15);
                step("soak: tree", () -> works.repka.ryscope.ui.TreeScreen.open(new ItemRes(new ItemStack(c % 2 == 0 ? Items.PISTON : Items.DISPENSER))), 30);
                if (c % 3 == 0) {
                    step("soak: structure", () -> {
                        try {
                            Object item = Class.forName("es.degrassi.mmreborn.common.item.ControllerItem")
                                .getMethod("makeMachineItem", net.minecraft.resources.ResourceLocation.class)
                                .invoke(null, net.minecraft.resources.ResourceLocation.parse("repys:kinetic_foundry"));
                            RecipeScreen.open(new ItemRes((ItemStack) item), Lookup.Mode.USES);
                        } catch (ReflectiveOperationException e) {
                            RYScope.LOG.error("[soak]", e);
                        }
                    }, 20);
                    for (int t = 0; t < 4; t++) {
                        step("soak: find structure", () -> {
                            if (structureOnScreen() != null || mc().screen == null) return;
                            mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
                        }, 20);
                    }
                    step("soak: watch structure", () -> {}, 60);
                }
                step("soak: close", () -> mc().setScreen(null), 10);
            }
            final int m = minute;
            step("soak: report", () -> soakReport(m), 2);
        }
        shot("soak_end");
        step("quit", () -> mc().stop(), 0);
    }

    /**
     * Утечки между входами в мир: несколько сессий подряд с типичными действиями (окно, поиск, листание, карточка,
     * дерево, структура), между ними выход в главное меню и вход снова. Память после GC пишется каждые две минуты
     * и в меню после каждого выхода; там же снимается гистограмма классов (logs/ryscope-leak-hist-N.txt).
     * Свойства: ryscope.leak.sessions (5), ryscope.leak.minutes (6).
     */
    private static void buildLeak() {
        int sessions = Integer.getInteger("ryscope.leak.sessions", 5);
        int minutes = Integer.getInteger("ryscope.leak.minutes", 6);
        tickLimit = 20 * 60 * (sessions * (minutes + 6) + 20);
        String[] queries = {"iron", "железн", "@create", "меч", "piston", "gold", "$острота", "plate", "уголь", "gear", "@applied", "#c:ingots"};
        int cycle = 0;
        for (int s = 1; s <= sessions; s++) {
            final int session = s;
            step("leak: ready " + s, () -> Foreign.get() != null && mc().player != null
                && Indexer.state() == Indexer.State.READY && Indexer.snapshot().stacks.size() > 5000, () -> {
                    mc().options.enableVsync().set(false);
                    mc().options.framerateLimit().set(120);
                    mc().getWindow().updateVsync(false);
                    leakReport("сессия " + session + " вход");
                }, 100);
            for (int minute = 1; minute <= minutes; minute++) {
                for (int k = 0; k < 6; k++, cycle++) {
                    final int c = cycle;
                    step("leak: inventory", () -> {
                        mc().setScreen(new InventoryScreen(mc().player));
                        Overlay.setSearch(queries[c % queries.length]);
                    }, 25);
                    step("leak: scroll", () -> {
                        Screen sc = mc().screen;
                        if (sc == null) return;
                        double x = sc.width - 40, y = sc.height / 2.0;
                        for (int i = 0; i < 6; i++) works.repka.ryscope.client.RyscopeClient.mouseScrolled(sc, x, y, -1);
                    }, 10);
                    step("leak: clear search", () -> Overlay.setSearch(""), 10);
                    step("leak: card", () -> {
                        var stacks = Indexer.snapshot().stacks;
                        if (stacks.isEmpty()) return;
                        Stack st = stacks.get((c * 7919) % stacks.size());
                        RecipeScreen.open(st, c % 2 == 0 ? Lookup.Mode.RECIPES : Lookup.Mode.USES);
                    }, 20);
                    step("leak: page", () -> {
                        if (mc().screen != null) mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0);
                    }, 15);
                    if (c % 2 == 0) {
                        step("leak: tree", () -> works.repka.ryscope.ui.TreeScreen.open(new ItemRes(new ItemStack(c % 4 == 0 ? Items.PISTON : Items.DISPENSER))), 30);
                    }
                    if (c % 5 == 0) {
                        step("leak: structure", () -> {
                            try {
                                Object item = Class.forName("es.degrassi.mmreborn.common.item.ControllerItem")
                                    .getMethod("makeMachineItem", net.minecraft.resources.ResourceLocation.class)
                                    .invoke(null, net.minecraft.resources.ResourceLocation.parse("repys:kinetic_foundry"));
                                RecipeScreen.open(new ItemRes((ItemStack) item), Lookup.Mode.USES);
                            } catch (ReflectiveOperationException e) {
                                RYScope.LOG.error("[leak]", e);
                            }
                        }, 20);
                        for (int t = 0; t < 3; t++) {
                            step("leak: find structure", () -> {
                                if (structureOnScreen() != null || mc().screen == null) return;
                                mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
                            }, 20);
                        }
                        step("leak: watch structure", () -> {}, 40);
                    }
                    step("leak: close", () -> mc().setScreen(null), 15);
                }
                if (minute % 2 == 0) {
                    final int m = minute;
                    step("leak: report", () -> leakReport("сессия " + session + ", минута " + m), 2);
                }
            }
            if (s == sessions) break;
            step("leak: logout " + s, AutoTest::leakLogout, 20);
            step("leak: menu " + s, () -> mc().level == null && mc().screen instanceof TitleScreen && mc().getOverlay() == null, () -> {}, 200);
            step("leak: menu report " + s, () -> {
                leakReport("меню после сессии " + session);
                leakHistogram(session);
            }, 60);
            step("leak: join " + (s + 1), () -> mc().createWorldOpenFlows().openWorld(WORLD, () -> RYScope.LOG.error("[leak] мир не открылся")), 20);
            step("leak: world " + (s + 1), () -> {
                skipBackupQuestion();
                return mc().player != null && mc().level != null && mc().screen == null;
            }, () -> {}, 40);
        }
        step("leak: final report", () -> {
            leakReport("конец");
            leakHistogram(99);
        }, 60);
        step("leak: logout final", AutoTest::leakLogout, 20);
        step("leak: menu final", () -> mc().level == null && mc().screen instanceof TitleScreen, () -> {}, 200);
        step("leak: menu final report", () -> {
            leakReport("меню в конце");
            leakHistogram(100);
        }, 60);
        step("quit", () -> mc().stop(), 0);
    }

    /**
     * Выход в главное меню, как кнопкой меню паузы. Не прямо из обработчика тика: выход сам крутит тики игры,
     * пока встроенный сервер не остановится, и шаг автотеста выполнился бы повторно изнутри себя.
     */
    private static void leakLogout() {
        mc().tell(() -> {
            if (mc().level == null) return;
            mc().setScreen(null);
            boolean local = mc().isLocalServer();
            mc().level.disconnect();
            if (local) mc().disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(net.minecraft.network.chat.Component.literal("saving")));
            else mc().disconnect();
            mc().setScreen(new TitleScreen());
        });
    }

    /** Куча после полной сборки мусора и размеры кешей мода и кора. */
    private static void leakReport(String where) {
        // кадры до сборки мусора: её пауза в худший кадр не входит
        long now = System.currentTimeMillis();
        double fps = leakAt == 0 ? 0 : (gameFrames - leakFrames) * 1000.0 / Math.max(1, now - leakAt);
        long worst = gameWorst / 1_000_000;
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 2; i++) System.gc();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        RYScope.LOG.info("[leak] {}: куча после GC {} МБ (выделено {} МБ), FPS {}, худший кадр {} мс, снимок {} ресурсов, мост {}, "
                + "атлас {}, глифов {}, фигур {}, значков {}, ячеек {}, структур {}, дерево {}, линза {}",
            where, used, rt.totalMemory() / (1024 * 1024), String.format("%.0f", fps), worst,
            Indexer.snapshot().stacks.size(), Foreign.get() == null ? "нет" : Foreign.get().name(),
            staticSize("works.repka.ryscore.ui.Atlas", "PAGES"), staticSize("works.repka.ryscore.ui.Type", "GLYPHS"),
            staticSize("works.repka.ryscore.ui.Shapes", "CACHE"), staticSize("works.repka.ryscore.ui.Icons", "CACHE"),
            staticSize("works.repka.ryscore.ui.Tiles", "CACHE"), staticSize("works.repka.ryscope.jei.StructureRecipeView", "LIVE"),
            staticSize("works.repka.ryscope.runtime.CraftTree", "MAP"), staticSize("works.repka.ryscope.ui.Lens", "found"));
        // счёт кадров начинается после сборки мусора
        leakFrames = gameFrames;
        leakAt = System.currentTimeMillis();
        gameFrameAt = 0;
        gameWorst = 0;
    }

    /** Гистограмма классов кучи через jcmd этого же процесса: logs/ryscope-leak-hist-N.txt. */
    private static void leakHistogram(int n) {
        try {
            long pid = ProcessHandle.current().pid();
            java.nio.file.Path jcmd = java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "jcmd.exe" : "jcmd");
            java.nio.file.Path out = mc().gameDirectory.toPath().resolve("logs").resolve("ryscope-leak-hist-" + n + ".txt");
            new ProcessBuilder(jcmd.toString(), String.valueOf(pid), "GC.class_histogram")
                .redirectErrorStream(true).redirectOutput(out.toFile()).start();
            RYScope.LOG.info("[leak] гистограмма {} пишется в {}", n, out);
        } catch (Throwable t) {
            RYScope.LOG.error("[leak] гистограмма не снята", t);
        }
    }

    /** Быстрый крафт в обычном верстаке: обновляется ли слот результата. */
    private static void buildCraft() {
        step("craft: wait jei", () -> works.repka.ryscope.runtime.Foreign.get() != null
            && works.repka.ryscope.runtime.Indexer.state() == works.repka.ryscope.runtime.Indexer.State.READY, () -> {
                command("clear @s");
                command("setblock 4 -60 2 minecraft:crafting_table");
            }, 40);
        for (String mode : new String[] {"book", "clicks"}) {
            step("craft: give " + mode, () -> {
                command("clear @s");
                command("give @s minecraft:oak_planks 16");
                if (mode.equals("clicks")) command("recipe take @s minecraft:chest");
                else command("recipe give @s minecraft:chest");
            }, 15);
            step("craft: open " + mode, () -> use(new BlockPos(4, -60, 2), Direction.UP, new Vec3(4.5, -59, 2.5)), 25);
            step("craft: run " + mode, () -> {
                try {
                    Class<?> qc = Class.forName("works.repka.ryscope.ui.QuickCraft");
                    java.lang.reflect.Method run = qc.getDeclaredMethod("run", net.minecraft.client.gui.screens.Screen.class, works.repka.ryscope.model.Stack.class, boolean.class);
                    run.setAccessible(true);
                    RYScope.LOG.info("[craft] {}: экран {}, обработан {}", mode, mc().screen == null ? null : mc().screen.getClass().getSimpleName(),
                        run.invoke(null, mc().screen, new ItemRes(new ItemStack(Items.CHEST)), false));
                } catch (ReflectiveOperationException e) {
                    RYScope.LOG.error("[craft]", e);
                }
            }, 20);
            step("craft: result " + mode, () -> {
                var menu = mc().player.containerMenu;
                List<String> grid = new ArrayList<>();
                for (int i = 0; i <= 9 && i < menu.slots.size(); i++) grid.add(menu.getSlot(i).getItem().getCount() + "×" + menu.getSlot(i).getItem().getItem());
                RYScope.LOG.info("[craft] {}: клиент, результат и сетка {}", mode, grid);
                var server = mc().getSingleplayerServer();
                var uuid = mc().player.getUUID();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(uuid);
                    List<String> sg = new ArrayList<>();
                    for (int i = 0; i <= 9 && i < sp.containerMenu.slots.size(); i++) sg.add(sp.containerMenu.getSlot(i).getItem().getCount() + "×" + sp.containerMenu.getSlot(i).getItem().getItem());
                    RYScope.LOG.info("[craft] {}: сервер, результат и сетка {}", mode, sg);
                });
            }, 10);
            shot("craft_" + mode);
            step("craft: close " + mode, () -> mc().player.closeContainer(), 10);
        }
        step("quit", () -> mc().stop(), 0);
    }

    private static long auditStart, liveBefore, liveStart;
    private static int nextCombinedBefore;

    /** Находки аудита: поиск, скрытие предмета, стрелка печи, быстрый крафт, новые окна. */
    private static void buildAudit() {
        step("audit: wait jei", () -> works.repka.ryscope.runtime.Foreign.get() != null
            && works.repka.ryscope.runtime.Indexer.state() == works.repka.ryscope.runtime.Indexer.State.READY
            && works.repka.ryscope.runtime.Indexer.snapshot().stacks.size() > 5000, () -> mc().setScreen(new InventoryScreen(mc().player)), 40);
        for (String q : new String[] {"iron | uses:stick", "железо | uses:stick", "drop:zombie", "drop:зомби", "loot:chest", "has:x", "mechanicl", "piston"}) {
            step("audit: search " + q, () -> {
                auditStart = System.currentTimeMillis();
                Overlay.setSearch(q);
            }, 0);
            step("audit: result " + q, () -> !works.repka.ryscope.runtime.SearchService.busy(), () -> {
                var r = works.repka.ryscope.runtime.SearchService.results();
                RYScope.LOG.info("[audit] поиск «{}»: найдено {}, поиск {} мс, первые: {}", q, r.stacks.size(), r.micros / 1000,
                    r.stacks.stream().limit(4).map(st -> st.id().toString()).toList());
            }, 5);
        }
        step("audit: hide diamond", () -> {
            boolean hidden = works.repka.ryscope.runtime.HiddenItems.toggle(new ItemRes(new ItemStack(Items.DIAMOND)), false);
            RYScope.LOG.info("[audit] алмаз скрыт: {}", hidden);
        }, 10);
        step("audit: hidden search", () -> works.repka.ryscope.runtime.Indexer.state() == works.repka.ryscope.runtime.Indexer.State.READY,
            () -> Overlay.setSearch("diamond"), 30);
        step("audit: hidden result", () -> !works.repka.ryscope.runtime.SearchService.busy(), () -> {
            boolean present = works.repka.ryscope.runtime.SearchService.results().stacks.stream().anyMatch(st -> st.id().toString().equals("minecraft:diamond"));
            RYScope.LOG.info("[audit] после скрытия алмаз в выдаче: {}", present);
            works.repka.ryscope.runtime.HiddenItems.toggle(new ItemRes(new ItemStack(Items.DIAMOND)), false);
            Overlay.setSearch("");
        }, 30);
        step("audit: furnace", () -> {
            mc().setScreen(null);
            command("setblock 3 -60 2 minecraft:furnace");
        }, 15);
        step("audit: open furnace", () -> use(new BlockPos(3, -60, 2), Direction.UP, new Vec3(3.5, -59, 2.5)), 25);
        step("audit: furnace arrow", () -> {
            if (!(mc().screen instanceof AbstractContainerScreen<?> acs)) {
                RYScope.LOG.info("[audit] печь не открылась: {}", mc().screen);
                return;
            }
            try {
                java.lang.reflect.Field l = AbstractContainerScreen.class.getDeclaredField("leftPos"), t = AbstractContainerScreen.class.getDeclaredField("topPos");
                l.setAccessible(true);
                t.setAccessible(true);
                double x = l.getInt(acs) + 79 + 12, y = t.getInt(acs) + 34 + 8;
                boolean taken = works.repka.ryscope.client.RyscopeClient.mouseClicked(acs, x, y, 0) || works.repka.ryscope.ui.ClickAreas.click(acs, x, y, 0);
                RYScope.LOG.info("[audit] клик по стрелке печи принят: {}", taken);
            } catch (ReflectiveOperationException e) {
                RYScope.LOG.error("[audit]", e);
            }
        }, 30);
        step("audit: furnace card", () -> {
            int groups = -1;
            if (mc().screen instanceof RecipeScreen rs) {
                try {
                    java.lang.reflect.Field nav = RecipeScreen.class.getDeclaredField("nav");
                    nav.setAccessible(true);
                    Object n = nav.get(rs);
                    java.lang.reflect.Field gf = n.getClass().getDeclaredField("groups");
                    gf.setAccessible(true);
                    groups = ((List<?>) gf.get(n)).size();
                } catch (ReflectiveOperationException e) {
                    RYScope.LOG.error("[audit]", e);
                }
            }
            RYScope.LOG.info("[audit] после клика экран {}, категорий {}", mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName(), groups);
        }, 2);
        shot("audit_furnace_card");
        step("audit: inventory", () -> {
            // закрыть как игрок: setScreen(null) не сообщает серверу, и он считает печь открытой
            mc().player.closeContainer();
            command("clear @s");
            command("give @s minecraft:oak_planks 8");
        }, 15);
        step("audit: open inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 15);
        step("audit: quick craft", () -> {
            try {
                Class<?> qc = Class.forName("works.repka.ryscope.ui.QuickCraft");
                java.lang.reflect.Method pick = qc.getDeclaredMethod("pick", works.repka.ryscope.model.Stack.class, AbstractContainerScreen.class);
                pick.setAccessible(true);
                Object chosen = pick.invoke(null, new ItemRes(new ItemStack(Items.CRAFTING_TABLE)), mc().screen);
                if (chosen instanceof works.repka.ryscope.content.NativeRecipe nr) {
                    RYScope.LOG.info("[audit] рецепт {}, в книге игрока: {}", nr.holder == null ? null : nr.holder.id(),
                        nr.holder != null && mc().player.getRecipeBook().contains(nr.holder));
                }
                RYScope.LOG.info("[audit] быстрый крафт выбрал {}: {}", chosen == null ? "ничего" : chosen.getClass().getSimpleName(),
                    chosen == null ? "" : ((works.repka.ryscope.model.RecipeView) chosen).transfer((AbstractContainerScreen<?>) mc().screen, false, true));
                java.lang.reflect.Method run = qc.getDeclaredMethod("run", net.minecraft.client.gui.screens.Screen.class, works.repka.ryscope.model.Stack.class, boolean.class);
                run.setAccessible(true);
                Object handled = run.invoke(null, mc().screen, new ItemRes(new ItemStack(Items.CRAFTING_TABLE)), false);
                RYScope.LOG.info("[audit] быстрый крафт верстака обработан: {}", handled);
            } catch (ReflectiveOperationException e) {
                RYScope.LOG.error("[audit]", e);
            }
        }, 20);
        step("audit: grid wait", () -> {}, 40);
        step("audit: grid", () -> {
            var menu = mc().player.inventoryMenu;
            List<String> grid = new ArrayList<>();
            for (int i = 0; i <= 4; i++) grid.add(menu.getSlot(i).getItem().toString());
            RYScope.LOG.info("[audit] сетка 2x2 и результат: {}", grid);
        }, 2);
        shot("audit_quick_craft");
        step("audit: card", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES), 40);
        shot("audit_card");
        step("audit: tree", () -> works.repka.ryscope.ui.TreeScreen.open(new ItemRes(new ItemStack(Items.PISTON))), 80);
        shot("audit_tree");
        step("audit: settings", () -> mc().setScreen(new works.repka.ryscope.ui.SettingsScreen(null)), 30);
        shot("audit_settings");
        step("quit", () -> mc().stop(), 0);
    }

    private static long batchFrames;

    /** Пачка кора: вызовы отрисовки кора за кадр и время кадра со старым режимом и с пачкой, снимки обоих. */
    private static void buildBatch() {
        step("batch: setup", () -> {
            mc().options.enableVsync().set(false);
            mc().options.framerateLimit().set(260);
            mc().getWindow().updateVsync(false);
            ThemeManager.select("ryscore:slate");
        }, 20);
        String[][] screens = {{"recipes", "окно рецептов"}, {"inventory", "инвентарь с панелями"}};
        for (String[] screen : screens) {
            step("batch: open " + screen[0], () -> {
                if (screen[0].equals("recipes")) RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES);
                else mc().setScreen(new InventoryScreen(mc().player));
            }, 60);
            for (boolean immediate : new boolean[] {true, false}) {
                String mode = immediate ? "сразу" : "пачкой";
                step("batch: " + screen[0] + " " + mode, () -> {
                    works.repka.ryscore.ui.Batch.immediate = immediate;
                    framesReset();
                    works.repka.ryscore.ui.Batch.takeDrawCalls();
                    batchFrames = frames;
                }, 200);
                step("batch: report " + screen[0] + " " + mode, () -> {
                    long n = Math.max(1, frames - batchFrames);
                    RYScope.LOG.info("[batch] {}, {}: вызовов кора за кадр {}, {}", screen[1], mode,
                        works.repka.ryscore.ui.Batch.takeDrawCalls() / n, framesReport());
                }, 2);
                shot("batch_" + screen[0] + (immediate ? "_old" : "_new"));
            }
        }
        step("batch: done", () -> works.repka.ryscore.ui.Batch.immediate = false, 2);
        step("quit", () -> mc().stop(), 0);
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

    /** Рамка окна и чужие области в единицах GUI игры: по ним раскладываются наши колонки. */
    private static void logAreas() {
        if (!(mc().screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs)) return;
        StringBuilder out = new StringBuilder(java.util.Arrays.toString(works.repka.ryscope.platform.Platform.get().screenBounds(acs)));
        works.repka.ryscope.runtime.Foreign.Source foreign = works.repka.ryscope.runtime.Foreign.get();
        if (foreign != null) for (int[] area : foreign.exclusionAreas(acs)) out.append(' ').append(java.util.Arrays.toString(area));
        RYScope.LOG.info("[areas] окно {} и области:{}, экран {}x{}", acs.getClass().getSimpleName(), out, acs.width, acs.height);
    }

    private static final BlockPos SUB_PROVIDER = new BlockPos(2, -60, 2), SUB_CHEST = new BlockPos(3, -60, 2), SUB_CPU = new BlockPos(-1, -60, 2);
    private static java.util.concurrent.Future<appeng.api.networking.crafting.ICraftingPlan> subPlan;

    @org.jetbrains.annotations.Nullable
    private static appeng.api.networking.IGrid subGrid(net.minecraft.server.level.ServerLevel level) {
        if (!(level.getBlockEntity(SUB_PROVIDER) instanceof appeng.api.networking.IInWorldGridNodeHost host)) return null;
        for (Direction d : Direction.values()) {
            var node = host.getGridNode(d);
            if (node != null && node.getGrid() != null) return node.getGrid();
        }
        return null;
    }

    private static int slotOf(net.minecraft.world.item.Item item, boolean playerInventory) {
        for (var slot : mc().player.containerMenu.slots) {
            if ((slot.container == mc().player.getInventory()) == playerInventory && slot.getItem().is(item)) return slot.index;
        }
        return -1;
    }

    /**
     * Замещение в шаблоне обработки: стекло из песка (рецепт печи с песком и красным песком) кодируется,
     * когда в сети нет ни того ни другого, в шаблон попадает обычный песок; потом в сеть кладётся только
     * красный песок, и автокрафт должен отправить в сундук у провайдера именно его.
     */
    private static long searchStart, lastFrameNs, enteredAt;

    /** Сколько ждёт ответ на запрос: по имени, сразу после смены инвентаря и по подсказкам; снимок загрузки. */
    private static void buildSearchLoad() {
        step("searchload: wait index", () -> works.repka.ryscope.runtime.Indexer.state() == works.repka.ryscope.runtime.Indexer.State.READY
            && works.repka.ryscope.runtime.Indexer.snapshot().stacks.size() > 5000, () -> {
                enteredAt = System.currentTimeMillis();
                mc().setScreen(new InventoryScreen(mc().player));
            }, 40);
        for (String q : new String[] {"iron", "железн", "меч", "@create", "golden apple"}) searchTimed(q, false);
        searchTimed("gold", true);
        step("searchload: craftable", () -> RYScope.LOG.info("[searchload] «Доступно» посчитано за {} мс",
            works.repka.ryscope.runtime.SearchService.craftableMillis()), 60);
        step("searchload: craftable again", () -> RYScope.LOG.info("[searchload] «Доступно» посчитано за {} мс",
            works.repka.ryscope.runtime.SearchService.craftableMillis()), 0);
        step("searchload: warm", () -> works.repka.ryscope.runtime.SearchService.tooltipsWarmed() >= 1f || stuck > 12000, () -> RYScope.LOG.info(
            "[searchload] подсказки собраны заранее: {}% через {} с после входа", Math.round(works.repka.ryscope.runtime.SearchService.tooltipsWarmed() * 100), (System.currentTimeMillis() - enteredAt) / 1000), 5);
        step("searchload: tooltips", () -> {
            searchStart = System.currentTimeMillis();
            Overlay.setSearch("$острота");
        }, 8);
        step("searchload: busy", () -> RYScope.LOG.info("[searchload] через 8 кадров: идёт {} мс, просмотрено {}",
            works.repka.ryscope.runtime.SearchService.busyMillis(), works.repka.ryscope.runtime.SearchService.examined()), 0);
        shot("search_loading");
        step("searchload: tooltips done", () -> !works.repka.ryscope.runtime.SearchService.busy(), () -> RYScope.LOG.info("[searchload] «$острота»: {} мс, найдено {}",
            System.currentTimeMillis() - searchStart, works.repka.ryscope.runtime.SearchService.results().stacks.size()), 5);
        shot("search_done");
        step("quit", () -> mc().stop(), 0);
    }

    private static void searchTimed(String q, boolean afterInventory) {
        step("searchload: " + q, () -> {
            if (afterInventory) {
                // смена инвентаря запускает пересчёт «Доступно»: запрос не должен ждать его
                mc().player.getInventory().add(new ItemStack(Items.IRON_INGOT, 3));
                works.repka.ryscope.runtime.SearchService.updateCraftable();
            }
            searchStart = System.currentTimeMillis();
            Overlay.setSearch(q);
        }, 0);
        step("searchload: " + q + " done", () -> {
            long frame = System.nanoTime();
            if (lastFrameNs != 0 && frame - lastFrameNs > 200_000_000L) RYScope.LOG.info("[searchload] долгий кадр {} мс", (frame - lastFrameNs) / 1_000_000);
            lastFrameNs = frame;
            return !works.repka.ryscope.runtime.SearchService.busy();
        }, () -> {
            lastFrameNs = 0;
            var r = works.repka.ryscope.runtime.SearchService.results();
            RYScope.LOG.info("[searchload] «{}»{}: увидели через {} мс, готов через {} мс, сам поиск {} мс, найдено {}", q, afterInventory ? " после смены инвентаря" : "",
                System.currentTimeMillis() - searchStart, r.readyAt - searchStart, r.micros / 1000, r.stacks.size());
        }, 10);
    }

    /** Поиск терминала AE2 в фокусе, затем клик в наш поиск: печатать должно только наше поле. */
    private static void buildFocus() {
        ae2Setup();
        step("focus: ae2 field", () -> {
            var field = ae2Search();
            if (field == null) {
                RYScope.LOG.info("[focus] поле поиска AE2 не найдено, экран {}", mc().screen);
                return;
            }
            mc().screen.setFocused(field);
            field.setFocused(true);
            for (char c : "iron".toCharArray()) mc().screen.charTyped(c, 0);
            RYScope.LOG.info("[focus] AE2: «{}», фокус {}", field.getValue(), field.isFocused());
        }, 10);
        step("focus: click ours", () -> {
            int[] r = searchRect();
            Screen screen = mc().screen;
            // как в игре: клик по нашему полю отменяет событие, экран его не видит
            Overlay.mouseClicked(screen, r[0] + 20, r[1] + r[3] / 2.0, 1);
            for (char c : "gold".toCharArray()) {
                if (!works.repka.ryscope.client.RyscopeClient.charTyped(screen, c, 0)) screen.charTyped(c, 0);
            }
        }, 20);
        step("focus: check ours", () -> {
            var field = ae2Search();
            RYScope.LOG.info("[focus] после клика в наш поиск: наш «{}» фокус {}, AE2 «{}» фокус {}",
                works.repka.ryscope.runtime.SearchService.text(), Overlay.searchFocused(),
                field == null ? null : field.getValue(), field != null && field.isFocused());
        }, 2);
        shot("focus_ours");
        step("focus: click ae2", () -> {
            var field = ae2Search();
            if (field == null) return;
            Screen screen = mc().screen;
            double x = field.getX() + 5, y = field.getY() + field.getHeight() / 2.0;
            if (!works.repka.ryscope.client.RyscopeClient.mouseClicked(screen, x, y, 0)) screen.mouseClicked(x, y, 0);
            field.setValue("");
            for (char c : "coal".toCharArray()) {
                if (!works.repka.ryscope.client.RyscopeClient.charTyped(screen, c, 0)) screen.charTyped(c, 0);
            }
        }, 20);
        step("focus: check ae2", () -> {
            var field = ae2Search();
            RYScope.LOG.info("[focus] после клика в поиск AE2: наш «{}» фокус {}, AE2 «{}» фокус {}",
                works.repka.ryscope.runtime.SearchService.text(), Overlay.searchFocused(),
                field == null ? null : field.getValue(), field != null && field.isFocused());
        }, 2);
        shot("focus_ae2");
        step("quit", () -> mc().stop(), 0);
    }

    @org.jetbrains.annotations.Nullable
    private static net.minecraft.client.gui.components.EditBox ae2Search() {
        if (mc().screen == null) return null;
        for (Class<?> c = mc().screen.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("searchField");
                f.setAccessible(true);
                return (net.minecraft.client.gui.components.EditBox) f.get(mc().screen);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
        return null;
    }

    private static int[] searchRect() {
        try {
            java.lang.reflect.Field f = Overlay.class.getDeclaredField("searchRect");
            f.setAccessible(true);
            return (int[]) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void buildSubstitution() {
        // предметы берутся из реестра внутри шагов: при сборке сценария моды ещё не зарегистрировали их
        java.util.function.Supplier<net.minecraft.world.item.Item> processing = () -> net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("ae2:processing_pattern"));
        ae2Setup();
        step("subst: close", () -> mc().player.closeContainer(), 10);
        step("subst: blocks", () -> {
            command("setblock 2 -60 2 ae2:pattern_provider");
            command("setblock 3 -60 2 minecraft:chest");
            command("setblock -1 -60 2 ae2:1k_crafting_storage");
        }, 60);
        step("subst: open pattern", () -> use(AE_CABLE, Direction.WEST, new Vec3(0.1, -58.5, 2.5)), 30);
        step("subst: substitute on", () -> {
            RYScope.LOG.info("[subst] экран {}", mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName());
            ((appeng.menu.me.items.PatternEncodingTermMenu) mc().player.containerMenu).setSubstitute(true);
        }, 10);
        step("subst: transfer glass", () -> doTransfer(Items.GLASS, "smelting"), 25);
        shot("subst_terminal");
        step("subst: blank", () -> quickMoveAll(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("ae2:blank_pattern"))), 10);
        step("subst: encode", () -> {
            var term = (appeng.menu.me.items.PatternEncodingTermMenu) mc().player.containerMenu;
            List<String> in = new ArrayList<>();
            for (var slot : term.getProcessingInputSlots()) if (!slot.getItem().isEmpty()) in.add(slot.getItem().getHoverName().getString());
            RYScope.LOG.info("[subst] входы шаблона перед записью: {}, замещение {}", in, term.isSubstitute());
            ((appeng.menu.me.items.PatternEncodingTermMenu) mc().player.containerMenu).encode();
        }, 15);
        step("subst: take pattern", () -> {
            int slot = slotOf(processing.get(), false);
            if (slot >= 0) mc().gameMode.handleInventoryMouseClick(mc().player.containerMenu.containerId, slot, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, mc().player);
            else RYScope.LOG.info("[subst] записанного шаблона нет");
        }, 10);
        step("subst: pattern info", () -> {
            for (ItemStack stack : mc().player.getInventory().items) {
                if (stack.isEmpty() || !stack.is(processing.get())) continue;
                var alternatives = stack.get(works.repka.ryscope.neoforge.ae2.ProcessingSubstitution.ALTERNATIVES.get());
                List<String> tip = new ArrayList<>();
                for (var line : stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.EMPTY, mc().player, net.minecraft.world.item.TooltipFlag.NORMAL)) tip.add(line.getString());
                RYScope.LOG.info("[subst] шаблон: варианты {}, подсказка {}", alternatives, tip);
            }
            mc().player.closeContainer();
        }, 10);
        step("subst: open provider", () -> use(SUB_PROVIDER, Direction.UP, new Vec3(2.5, -59, 2.5)), 30);
        step("subst: put pattern", () -> {
            RYScope.LOG.info("[subst] экран провайдера {}", mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName());
            quickMoveAll(processing.get());
        }, 10);
        step("subst: close provider", () -> mc().player.closeContainer(), 10);
        step("subst: red sand", () -> command("give @s minecraft:red_sand 8"), 10);
        step("subst: open terminal", () -> use(AE_CABLE, Direction.NORTH, new Vec3(0.5, -58.5, 2.1)), 30);
        step("subst: red sand in", () -> {
            RYScope.LOG.info("[subst] экран для песка {}, красного песка в инвентаре {}", mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName(),
                mc().player.getInventory().countItem(Items.RED_SAND));
            quickMoveAll(Items.RED_SAND);
        }, 20);
        // скорость отметок: сколько проходит от изменения сети до того, как его видит RYScope
        step("live: give", () -> command("give @s minecraft:cobblestone 7"), 10);
        step("live: start", () -> {
            works.repka.ryscope.runtime.Ae2Terminal.setActive(mc().player.containerMenu);
            liveBefore = works.repka.ryscope.runtime.Ae2Terminal.amounts().getOrDefault(Items.COBBLESTONE, 0L);
            liveStart = System.currentTimeMillis();
            quickMoveAll(Items.COBBLESTONE);
        }, 0);
        step("live: seen", () -> works.repka.ryscope.runtime.Ae2Terminal.amounts().getOrDefault(Items.COBBLESTONE, 0L) != liveBefore
            || System.currentTimeMillis() - liveStart > 5000, () -> RYScope.LOG.info("[live] булыжник в сети виден через {} мс: было {}, стало {}, версия сети {}",
            System.currentTimeMillis() - liveStart, liveBefore, works.repka.ryscope.runtime.Ae2Terminal.amounts().getOrDefault(Items.COBBLESTONE, 0L),
            works.repka.ryscope.runtime.Ae2Terminal.networkVersion()), 2);
        step("subst: close terminal", () -> {
            works.repka.ryscope.runtime.Ae2Terminal.setActive(null);
            mc().player.closeContainer();
        }, 10);
        step("subst: plan", () -> {
            var server = mc().getSingleplayerServer();
            server.execute(() -> {
                var grid = subGrid(server.overworld());
                if (grid == null) {
                    RYScope.LOG.info("[subst] сеть у провайдера не найдена");
                    return;
                }
                RYScope.LOG.info("[subst] процессоров крафта {}", grid.getCraftingService().getCpus().size());
                var stock = grid.getStorageService().getInventory().getAvailableStacks();
                RYScope.LOG.info("[subst] в сети: песок {}, красный песок {}, доски {}", stock.get(appeng.api.stacks.AEItemKey.of(Items.SAND)),
                    stock.get(appeng.api.stacks.AEItemKey.of(Items.RED_SAND)), stock.get(appeng.api.stacks.AEItemKey.of(Items.OAK_PLANKS)));
                // расчёт ищет шаблоны через узел сети того, кто просит: без узла AE2 не видит ни одного шаблона
                appeng.api.networking.IGridNode node = null;
                if (server.overworld().getBlockEntity(SUB_PROVIDER) instanceof appeng.api.networking.IInWorldGridNodeHost host) {
                    for (Direction d : Direction.values()) if (node == null) node = host.getGridNode(d);
                }
                appeng.api.networking.IGridNode requesterNode = node;
                appeng.api.networking.crafting.ICraftingSimulationRequester requester = new appeng.api.networking.crafting.ICraftingSimulationRequester() {
                    @Override
                    public appeng.api.networking.security.IActionSource getActionSource() {
                        return appeng.api.networking.security.IActionSource.empty();
                    }

                    @Override
                    public appeng.api.networking.IGridNode getGridNode() {
                        return requesterNode;
                    }
                };
                subPlan = grid.getCraftingService().beginCraftingCalculation(server.overworld(), requester,
                    appeng.api.stacks.AEItemKey.of(Items.GLASS), 1, appeng.api.networking.crafting.CalculationStrategy.REPORT_MISSING_ITEMS);
            });
        }, 60);
        step("subst: submit", () -> {
            var server = mc().getSingleplayerServer();
            server.execute(() -> {
                try {
                    if (subPlan == null || !subPlan.isDone()) {
                        RYScope.LOG.info("[subst] план не посчитан");
                        return;
                    }
                    var plan = subPlan.get();
                    java.util.function.Function<appeng.api.stacks.KeyCounter, String> show = counter -> {
                        List<String> out = new ArrayList<>();
                        for (var e : counter) out.add(e.getKey() + " x" + e.getLongValue());
                        return out.toString();
                    };
                    RYScope.LOG.info("[subst] план: берёт {}, не хватает {}", show.apply(plan.usedItems()), show.apply(plan.missingItems()));
                    var patterns = subGrid(server.overworld()).getCraftingService().getCraftingFor(appeng.api.stacks.AEItemKey.of(Items.GLASS));
                    for (var pattern : patterns) {
                        List<String> inputs = new ArrayList<>();
                        for (var input : pattern.getInputs()) inputs.add(java.util.Arrays.toString(input.getPossibleInputs()));
                        RYScope.LOG.info("[subst] шаблон стекла в сети: {} входы {}", pattern.getClass().getSimpleName(), inputs);
                    }
                    var result = subGrid(server.overworld()).getCraftingService().submitJob(plan, null, null, false, appeng.api.networking.security.IActionSource.empty());
                    RYScope.LOG.info("[subst] задание: {}", result.successful() ? "принято" : "отклонено " + result.errorCode());
                } catch (Exception e) {
                    RYScope.LOG.error("[subst]", e);
                }
            });
        }, 80);
        step("subst: chest", () -> {
            var server = mc().getSingleplayerServer();
            server.execute(() -> {
                List<String> items = new ArrayList<>();
                if (server.overworld().getBlockEntity(SUB_CHEST) instanceof net.minecraft.world.Container chest) {
                    for (int i = 0; i < chest.getContainerSize(); i++) if (!chest.getItem(i).isEmpty()) items.add(chest.getItem(i).getItem() + " x" + chest.getItem(i).getCount());
                }
                RYScope.LOG.info("[subst] в сундуке у провайдера: {}", items);
            });
        }, 10);
        step("next: ae2 craftable", () -> {
            RYScope.LOG.info("[next] сеть умеет: стекло {}, всего крафтуемых {}, данные на {}",
                works.repka.ryscope.runtime.Ae2Terminal.craftableKnown(Items.GLASS), works.repka.ryscope.runtime.Storages.craftable().size(),
                works.repka.ryscope.runtime.Storages.craftableAt());
            var cfg = works.repka.ryscope.runtime.Config.get();
            boolean saved = cfg.treeAutocraft;
            cfg.treeAutocraft = true;
            var tree = new works.repka.ryscope.runtime.CraftTree(new ItemRes(new ItemStack(Items.GLASS_PANE, 16)), false);
            tree.setAmount(16);
            List<String> ordered = new ArrayList<>();
            for (var r : tree.ordered.values()) ordered.add(r.stack.id() + " x" + r.need);
            int withAuto = tree.steps;
            cfg.treeAutocraft = false;
            var plain = new works.repka.ryscope.runtime.CraftTree(new ItemRes(new ItemStack(Items.GLASS_PANE, 16)), false);
            plain.setAmount(16);
            cfg.treeAutocraft = saved;
            RYScope.LOG.info("[next] дерево стеклянной панели: с автокрафтом шагов {}, закажет сеть {}; без автокрафта шагов {}, заказов {}",
                withAuto, ordered, plain.steps, plain.ordered.size());
        }, 5);
        step("next: ae2 tree screen", () -> works.repka.ryscope.ui.TreeScreen.open(new ItemRes(new ItemStack(Items.GLASS_PANE))), 60);
        shot("next_ae2_tree");
        step("cycle: open", () -> {
            parkMouse();
            try {
                Object item = Class.forName("es.degrassi.mmreborn.common.item.ControllerItem")
                    .getMethod("makeMachineItem", net.minecraft.resources.ResourceLocation.class)
                    .invoke(null, net.minecraft.resources.ResourceLocation.parse("repys:kinetic_foundry"));
                RecipeScreen.open(new ItemRes((ItemStack) item), Lookup.Mode.USES);
            } catch (ReflectiveOperationException e) {
                RYScope.LOG.error("[cycle]", e);
            }
        }, 40);
        for (int tab = 0; tab < 4; tab++) {
            step("cycle: find structure", () -> {
                if (structureOnScreen() != null) return;
                mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
            }, 40);
        }
        step("cycle: state", () -> {
            var view = structureOnScreen();
            if (view == null) {
                RYScope.LOG.info("[cycle] превью не найдено");
                return;
            }
            try {
                java.lang.reflect.Field variants = view.getClass().getDeclaredField("variants");
                java.lang.reflect.Field step = view.getClass().getDeclaredField("shownStep");
                variants.setAccessible(true);
                step.setAccessible(true);
                var map = (java.util.Map<?, ?>) variants.get(view);
                int most = 0;
                for (Object list : map.values()) most = Math.max(most, ((List<?>) list).size());
                RYScope.LOG.info("[cycle] позиций с вариантами {}, больше всего вариантов {}, шаг {}", map.size(), most, step.getLong(view));
            } catch (ReflectiveOperationException e) {
                RYScope.LOG.error("[cycle]", e);
            }
        }, 2);
        shot("cycle_a");
        step("cycle: wait", () -> {}, 40);
        shot("cycle_b");
        step("quit", () -> mc().stop(), 0);
    }

    /** Сеть AE2 с дисками и досками, открыт терминал крафта. */
    private static void ae2Setup() {
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
            logAreas();
            quickMoveAll(Items.OAK_PLANKS);
        }, 30);
    }

    /**
     * Замечания автора 17.11: поиск мода через @ по названию с пробелами и двоеточием; «+» у превью мультиблока
     * записывает постройку по умолчанию в шаблон обработки AE2 (без терминала кнопка неактивна с подсказкой).
     */
    private static void buildFb17() {
        step("fb17: wait jei", () -> works.repka.ryscope.runtime.Foreign.get() != null
            && Indexer.state() == Indexer.State.READY && Indexer.snapshot().stacks.size() > 5000, () -> {}, 40);
        step("fb17: inventory", () -> {
            parkMouse();
            mc().setScreen(new InventoryScreen(mc().player));
        }, 20);
        String[][] queries = {
            {"@ae2", "ae2"}, {"@appliedenergistics", "ae2"}, {"@applied energistics 2", "ae2"}, {"@Applied Energistics 2", "ae2"},
            {"@actually additions: tiers", "aatiers"}, {"@aatiers", "aatiers"}, {"@\"actually additions: tiers\"", "aatiers"},
            {"@applied energistics 2 cable", "ae2"}, {"@ae2 cable", "ae2"},
        };
        for (String[] q : queries) {
            step("fb17: search " + q[0], () -> Overlay.setSearch(q[0]), 30);
            step("fb17: result " + q[0], () -> !works.repka.ryscope.runtime.SearchService.busy() || stuck > 200, () -> {
                var results = works.repka.ryscope.runtime.SearchService.results();
                java.util.Map<String, Integer> byMod = new java.util.TreeMap<>();
                for (var st : results.stacks) byMod.merge(st.id().getNamespace(), 1, Integer::sum);
                int own = byMod.getOrDefault(q[1], 0);
                List<String> names = new ArrayList<>();
                for (int i = 0; i < Math.min(4, results.stacks.size()); i++) names.add(results.stacks.get(i).name().getString());
                RYScope.LOG.info("[fb17] «{}»: найдено {}, из мода {} {}, по модам {}, первые {}", q[0], results.stacks.size(), q[1], own,
                    byMod.size() > 6 ? byMod.size() + " модов" : byMod, names);
            }, 2);
            if (q[0].equals("@applied energistics 2") || q[0].equals("@actually additions: tiers")) shot("fb17_search_" + q[1]);
        }
        step("fb17: clear search", () -> Overlay.setSearch(""), 5);

        // превью литейни поверх инвентаря: «+» есть, но неактивен с подсказкой про терминал
        step("fb17: open over inventory", () -> openFoundryPreview(), 40);
        for (int tab = 0; tab < 4; tab++) {
            step("fb17: find structure", () -> {
                if (structureOnScreen() != null) return;
                mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
            }, 40);
        }
        step("fb17: build", () -> {
            var view = structureOnScreen();
            if (view == null) {
                RYScope.LOG.info("[fb17] превью структуры не найдено");
                return;
            }
            List<String> build = new ArrayList<>();
            for (Stack st : view.build()) build.add(st.id() + " x" + st.amount);
            RYScope.LOG.info("[fb17] постройка по умолчанию: {}; машина {} {}", build, view.machineItem(), view.machineItem().getComponentsPatch());
            hoverPlus();
        }, 20);
        step("fb17: hint without terminal", () -> {
            var view = structureOnScreen();
            if (view != null) {
                RYScope.LOG.info("[fb17] без терминала: «+» показан {}, перенос в инвентарь {}", view.showsTransfer(),
                    view.transfer(new InventoryScreen(mc().player), false, true).status());
            }
        }, 2);
        shot("fb17_plus_no_terminal");
        step("fb17: close card", () -> mc().setScreen(null), 10);

        ae2Setup();
        step("fb17: close terminal", () -> mc().player.closeContainer(), 10);
        step("fb17: open pattern", () -> use(AE_CABLE, Direction.WEST, new Vec3(0.1, -58.5, 2.5)), 30);
        step("fb17: open over pattern", () -> {
            RYScope.LOG.info("[fb17] экран {}", mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName());
            openFoundryPreview();
        }, 40);
        for (int tab = 0; tab < 4; tab++) {
            step("fb17: find structure", () -> {
                if (structureOnScreen() != null) return;
                mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
            }, 40);
        }
        step("fb17: hover plus", () -> hoverPlus(), 30);
        shot("fb17_plus_terminal");
        step("fb17: click plus", () -> {
            int[] at = plusCenter();
            if (at == null) {
                RYScope.LOG.info("[fb17] «+» не найден");
                return;
            }
            boolean taken = mc().screen.mouseClicked(works.repka.ryscore.ui.UiScale.toGui(at[0]), works.repka.ryscore.ui.UiScale.toGui(at[1]), 0);
            mc().screen.mouseReleased(works.repka.ryscore.ui.UiScale.toGui(at[0]), works.repka.ryscore.ui.UiScale.toGui(at[1]), 0);
            RYScope.LOG.info("[fb17] клик по «+» принят {}, экран после {}", taken, mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName());
        }, 30);
        step("fb17: pattern slots", () -> {
            parkMouse();
            if (!(mc().player.containerMenu instanceof appeng.menu.me.items.PatternEncodingTermMenu term)) {
                RYScope.LOG.info("[fb17] терминал шаблонов не открыт: {}", mc().player.containerMenu.getClass().getSimpleName());
                return;
            }
            List<String> in = new ArrayList<>(), out = new ArrayList<>();
            for (var slot : term.getProcessingInputSlots()) {
                var gs = appeng.api.stacks.GenericStack.fromItemStack(slot.getItem());
                if (gs != null) in.add(gs.what().getId() + " x" + gs.amount());
            }
            for (var slot : term.getProcessingOutputSlots()) {
                var gs = appeng.api.stacks.GenericStack.fromItemStack(slot.getItem());
                if (gs != null) out.add(gs.what().getId() + " x" + gs.amount() + " " + (gs.what() instanceof appeng.api.stacks.AEItemKey k ? k.toStack().getComponentsPatch() : ""));
            }
            RYScope.LOG.info("[fb17] режим {}, входы шаблона {}: {}; выход {}", term.getMode(), in.size(), in, out);
        }, 10);
        shot("fb17_pattern_filled");
        step("fb17: blank", () -> quickMoveAll(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("ae2:blank_pattern"))), 10);
        step("fb17: encode", () -> {
            if (mc().player.containerMenu instanceof appeng.menu.me.items.PatternEncodingTermMenu term) term.encode();
        }, 15);
        step("fb17: encoded", () -> {
            var processing = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("ae2:processing_pattern"));
            for (var slot : mc().player.containerMenu.slots) {
                if (!slot.getItem().is(processing)) continue;
                List<String> tip = new ArrayList<>();
                for (var line : slot.getItem().getTooltipLines(net.minecraft.world.item.Item.TooltipContext.EMPTY, mc().player, net.minecraft.world.item.TooltipFlag.NORMAL)) tip.add(line.getString());
                RYScope.LOG.info("[fb17] записанный шаблон: {}", tip);
                return;
            }
            RYScope.LOG.info("[fb17] записанного шаблона нет");
        }, 5);
        shot("fb17_pattern_encoded");
        step("quit", () -> mc().stop(), 0);
    }

    private static void openFoundryPreview() {
        parkMouse();
        try {
            Object item = Class.forName("es.degrassi.mmreborn.common.item.ControllerItem")
                .getMethod("makeMachineItem", net.minecraft.resources.ResourceLocation.class)
                .invoke(null, net.minecraft.resources.ResourceLocation.parse("repys:kinetic_foundry"));
            RecipeScreen.open(new ItemRes((ItemStack) item), Lookup.Mode.USES);
        } catch (ReflectiveOperationException e) {
            RYScope.LOG.error("[fb17]", e);
        }
    }

    /** Центр «+» у превью структуры в единицах интерфейса RYScope, как его ставит RecipeScreen.plusY. */
    @org.jetbrains.annotations.Nullable
    private static int[] plusCenter() {
        if (!(mc().screen instanceof RecipeScreen rs)) return null;
        for (var p : rs.placed()) {
            if (!(p.recipe() instanceof works.repka.ryscope.jei.StructureRecipeView)) continue;
            int button = 14;
            return new int[] {p.rowX() + p.rowW() - button + button / 2, p.rowY() + (p.rowH() - button) / 2 + button / 2};
        }
        return null;
    }

    private static void hoverPlus() {
        int[] at = plusCenter();
        if (at != null) moveMouse(works.repka.ryscore.ui.UiScale.toGui(at[0]), works.repka.ryscore.ui.UiScale.toGui(at[1]));
    }

    @org.jetbrains.annotations.Nullable
    private static works.repka.ryscope.jei.StructureRecipeView structureOnScreen() {
        if (!(mc().screen instanceof RecipeScreen rs)) return null;
        for (var p : rs.placed()) if (p.recipe() instanceof works.repka.ryscope.jei.StructureRecipeView v) return v;
        return null;
    }

    /** Курсор в левый верхний угол: подсказки не закрывают кнопки на снимках. */
    private static void parkMouse() {
        try {
            for (String name : new String[] {"xpos", "ypos"}) {
                java.lang.reflect.Field f = net.minecraft.client.MouseHandler.class.getDeclaredField(name);
                f.setAccessible(true);
                f.setDouble(mc().mouseHandler, 2);
            }
        } catch (ReflectiveOperationException e) {
            RYScope.LOG.warn("[autotest] курсор не перенесён: {}", e.toString());
        }
    }

    private static void injectCraftable(net.minecraft.world.item.Item... items) {
        try {
            Class<?> c = works.repka.ryscope.runtime.Ae2Terminal.class;
            java.lang.reflect.Field craftable = c.getDeclaredField("craftable");
            java.lang.reflect.Field cachedAt = c.getDeclaredField("cachedAt");
            java.lang.reflect.Field cachedFor = c.getDeclaredField("cachedFor");
            java.lang.reflect.Field active = c.getDeclaredField("active");
            for (var f : new java.lang.reflect.Field[] {craftable, cachedAt, cachedFor, active}) f.setAccessible(true);
            cachedFor.set(null, active.get(null));
            cachedAt.setLong(null, Long.MAX_VALUE / 4);
            craftable.set(null, new java.util.HashSet<>(java.util.Arrays.asList(items)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Состояния кнопки «+» и ячеек над терминалом AE2 и вне станции. */
    private static void buildStates() {
        step("states: survival", () -> command("gamemode survival"), 10);
        // курсор в угол: подсказки не закрывают кнопки на снимках
        step("states: cursor", () -> parkMouse(), 2);
        ae2Setup();
        String[][] cases = {{"minecraft:chest", "full"}, {"minecraft:piston", "partial"}};
        for (String[] c : cases) {
            step("states: " + c[1], () -> RecipeScreen.open(new ItemRes(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.parse(c[0])))), Lookup.Mode.RECIPES), 40);
            step("states: cursor", () -> parkMouse(), 2);
            shot("state_" + c[1]);
            step("states: back", () -> mc().screen.onClose(), 15);
        }
        step("states: hover open", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES), 40);
        step("states: hover plus", () -> {
            // курсор на «+» первого рецепта: подсветка ячеек появляется только так
            var row = ((RecipeScreen) mc().screen).placed().get(0);
            int bx = row.rowX() + row.rowW() - 14, by = Math.max(row.rowY() + (row.rowH() - 14) / 2, row.rowY() + 17);
            double px = works.repka.ryscore.ui.UiScale.pixels();
            var window = mc().getWindow();
            try {
                java.lang.reflect.Field fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
                java.lang.reflect.Field fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
                fx.setAccessible(true);
                fy.setAccessible(true);
                fx.setDouble(mc().mouseHandler, (bx + 7) * px * window.getScreenWidth() / window.getWidth());
                fy.setDouble(mc().mouseHandler, (by + 7) * px * window.getScreenHeight() / window.getHeight());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }, 5);
        shot("state_partial_hover");
        step("states: hover back", () -> {
            parkMouse();
            mc().screen.onClose();
        }, 15);
        step("states: order open", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES), 40);
        step("states: order", () -> {
            // сеть «умеет сделать» все ингредиенты открытого рецепта: в тестовом мире нет шаблонов автокрафта
            List<net.minecraft.world.item.Item> items = new ArrayList<>();
            for (var placed : ((RecipeScreen) mc().screen).placed()) {
                for (var ing : placed.recipe().inputs()) {
                    for (var option : ing.options) if (option.key() instanceof net.minecraft.world.item.Item item) items.add(item);
                }
            }
            items.remove(Items.OAK_PLANKS);
            items.remove(Items.REDSTONE);
            injectCraftable(items.toArray(new net.minecraft.world.item.Item[0]));
            StringBuilder st = new StringBuilder();
            for (var placed : ((RecipeScreen) mc().screen).placed()) {
                st.append(java.util.Arrays.toString(works.repka.ryscope.runtime.Supply.states(placed.recipe().inputs())));
            }
            RYScope.LOG.info("[states] подставлено {}, активен терминал {}, состояния {}", items.size(),
                works.repka.ryscope.runtime.Ae2Terminal.active(), st);
        }, 20);
        step("states: cursor", () -> parkMouse(), 2);
        shot("state_order");
        step("states: order glass", () -> ThemeManager.select("ryscore:glass"), 15);
        shot("state_order_glass");
        step("states: slate", () -> {
            ThemeManager.select("ryscore:slate");
            mc().screen.onClose();
        }, 15);
        step("states: none", () -> {
            injectCraftable();
            RecipeScreen.open(new ItemRes(new ItemStack(Items.DIAMOND_PICKAXE)), Lookup.Mode.RECIPES);
        }, 40);
        step("states: cursor", () -> parkMouse(), 2);
        shot("state_none");
        step("states: close all", () -> {
            mc().screen.onClose();
            mc().player.closeContainer();
        }, 20);
        step("states: no host", () -> RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES), 40);
        step("states: cursor", () -> parkMouse(), 2);
        shot("state_no_host");
        step("quit", () -> mc().stop(), 0);
    }

    private static void buildAe2() {
        ae2Setup();
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

    // --- сценарий links: сведения модов семьи (магазин и награды книги, чип квестов) на тестовом поставщике ---

    private static void linksOpen(net.minecraft.world.item.Item item, Lookup.Mode mode) {
        mc().setScreen(new InventoryScreen(mc().player));
        RecipeScreen.clearTabs();
        RecipeScreen.open(new ItemRes(new ItemStack(item)), mode);
    }

    /** Навести мышь на середину прямоугольника в единицах карточки. */
    private static void linksHover(int[] r) {
        if (r == null) return;
        moveMouse(works.repka.ryscore.ui.UiScale.toGui(r[0] + r[2] / 2.0), works.repka.ryscore.ui.UiScale.toGui(r[1] + r[3] / 2.0));
    }

    private static void linksClick(int[] r, String expect, String tag) {
        RecipeScreen rs = AutoTestLinks.screen();
        if (rs == null || r == null) {
            AutoTestLinks.check(tag, false, "нечего нажать");
            return;
        }
        AutoTestLinks.lastAction = "";
        rs.clickForTest(r[0] + r[2] / 2.0, r[1] + r[3] / 2.0, 0);
        AutoTestLinks.check(tag, expect.equals(AutoTestLinks.lastAction), "действие «" + AutoTestLinks.lastAction + "», ждали «" + expect + "»");
    }

    private static void buildLinks() {
        step("links: setup", () -> {
            Overlay.setSearch("");
            ThemeManager.select("ryscore:slate");
            stripWindow(1280, 800);
            AutoTestLinks.uninstall();
            AutoTestLinks.install();
        }, 5);
        // осколок эха: рецептов нет, «Источники» с магазином и наградой квеста, чип «2 квеста»
        step("links: open shard", () -> {
            parkMouse();
            linksOpen(Items.ECHO_SHARD, Lookup.Mode.RECIPES);
        }, 20);
        step("links: shop", () -> {
            RecipeScreen rs = AutoTestLinks.screen();
            boolean found = rs != null && rs.selectGroupForTest(AutoTestLinks::isShop);
            AutoTestLinks.check("магазин", found, "категория магазина в «Источниках»");
        }, 6);
        step("links: shop log", () -> {
            AutoTestLinks.log("магазин, страница 1");
            RecipeScreen rs = AutoTestLinks.screen();
            if (rs == null) return;
            AutoTestLinks.check("раздел", "sources".equals(rs.sectionsForTest().current()), "открыт раздел " + rs.sectionsForTest().current());
            AutoTestLinks.check("счётчик", rs.stripForTest().pager().contains("сделк") || rs.stripForTest().pager().contains("из"),
                "счётчик «" + rs.stripForTest().pager() + "»");
            List<works.repka.ryscope.content.LinkCardView> cards = AutoTestLinks.cards();
            AutoTestLinks.check("карточка во всю ширину", !cards.isEmpty() && cards.get(0).width() > works.repka.ryscope.content.LinkCardView.MIN_W,
                cards.isEmpty() ? "карточек нет" : "ширина " + cards.get(0).width());
        }, 1);
        shot("links_shop_locked");
        // предмет цены ведёт себя как предмет просмотрщика: подсказка игры
        step("links: hover cost", () -> {
            List<works.repka.ryscope.content.LinkCardView> cards = AutoTestLinks.cards();
            RecipeScreen rs = AutoTestLinks.screen();
            if (rs == null || cards.isEmpty()) return;
            for (RecipeScreen.Placed p : rs.placed()) {
                if (p.recipe() != cards.get(0)) continue;
                // первая строка «Нужно доставить»: шапка 22, промежуток 5, заголовок 6, промежуток 5
                moveMouse(works.repka.ryscore.ui.UiScale.toGui(p.x() + 9), works.repka.ryscore.ui.UiScale.toGui(p.y() + 38 + 9));
                Stack s = rs.hoveredStackAtMouse();
                AutoTestLinks.check("предмет цены", s != null && s.item().is(Items.NETHERITE_INGOT), "под курсором " + (s == null ? "ничего" : s.id()));
            }
        }, 6);
        shot("links_cost_tooltip");
        step("links: lock link", () -> {
            parkMouse();
            List<works.repka.ryscope.content.LinkCardView> cards = AutoTestLinks.cards();
            linksClick(cards.isEmpty() ? null : cards.get(0).actionRectForTest(true), "lock_link", "ссылка под замком");
        }, 4);
        step("links: open button", () -> {
            List<works.repka.ryscope.content.LinkCardView> cards = AutoTestLinks.cards();
            linksClick(cards.isEmpty() ? null : cards.get(0).actionRectForTest(false), "open_shop_locked", "кнопка «Открыть в магазине»");
        }, 4);
        step("links: next page", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0), 6);
        step("links: page 2 log", () -> AutoTestLinks.log("магазин, страница 2"), 1);
        shot("links_shop_open");
        // карточка обновляется сама: поставщик поднял версию, числа новые, страница на месте
        step("links: version", () -> {
            AutoTestLinks.PROVIDER.delivered = 1984;
            AutoTestLinks.PROVIDER.version++;
        }, 4);
        step("links: version log", () -> {
            AutoTestLinks.log("после новой версии");
            boolean updated = false;
            for (works.repka.ryscope.content.LinkCardView v : AutoTestLinks.cards()) {
                if (v.card().id().getPath().endsWith("trophy_open")) updated = v.card().takes().get(0).have() == 1984;
            }
            AutoTestLinks.check("версия", updated, "открытая карточка перечитала поставку");
        }, 1);
        shot("links_shop_updated");
        step("links: rewards", () -> {
            RecipeScreen rs = AutoTestLinks.screen();
            AutoTestLinks.check("награды квестов", rs != null && rs.selectGroupForTest(AutoTestLinks::isRewards), "категория наград в «Источниках»");
        }, 6);
        step("links: rewards log", () -> AutoTestLinks.log("награды квестов"), 1);
        shot("links_rewards");
        // чип в шапке и список
        step("links: chip", () -> {
            RecipeScreen rs = AutoTestLinks.screen();
            if (rs == null) return;
            RecipeScreen.LinksForTest l = rs.linksForTest();
            AutoTestLinks.check("чип", l.chips().equals(List.of("2 квеста")) && l.chipRects().get(0) != null, "чипы " + l.chips());
            linksHover(l.chipRects().isEmpty() ? null : l.chipRects().get(0));
        }, 8);
        step("links: chip log", () -> {
            AutoTestLinks.log("список по наведению");
            RecipeScreen rs = AutoTestLinks.screen();
            if (rs == null) return;
            RecipeScreen.LinksForTest l = rs.linksForTest();
            AutoTestLinks.check("список", l.open() == 0 && l.rows().size() == 3, "открыт " + l.open() + ", строк " + l.rows().size());
        }, 1);
        shot("links_chip_popover");
        step("links: hover row", () -> {
            RecipeScreen rs = AutoTestLinks.screen();
            if (rs != null && !rs.linksForTest().rowRects().isEmpty()) linksHover(rs.linksForTest().rowRects().get(1));
        }, 6);
        shot("links_chip_row_hover");
        step("links: click row", () -> {
            RecipeScreen rs = AutoTestLinks.screen();
            linksClick(rs == null || rs.linksForTest().rowRects().isEmpty() ? null : rs.linksForTest().rowRects().get(1), "mention_eternity", "строка списка");
        }, 4);
        step("links: themes glass", () -> {
            parkMouse();
            ThemeManager.select("ryscore:glass");
            RecipeScreen rs = AutoTestLinks.screen();
            if (rs != null) {
                rs.selectGroupForTest(AutoTestLinks::isShop);
                rs.openMentionsForTest(0);
            }
        }, 8);
        shot("links_glass");
        step("links: themes vanilla", () -> ThemeManager.select("ryscore:vanilla"), 8);
        shot("links_vanilla");
        step("links: slate", () -> {
            ThemeManager.select("ryscore:slate");
            RecipeScreen rs = AutoTestLinks.screen();
            if (rs != null) rs.openMentionsForTest(-1);
        }, 4);
        // продажа: предмет в цене сделки уходит в «Использования»
        step("links: uses", () -> {
            parkMouse();
            linksOpen(Items.ECHO_SHARD, Lookup.Mode.USES);
            RecipeScreen rs = AutoTestLinks.screen();
            if (rs != null) {
                rs.switchSectionForTest("uses");
                AutoTestLinks.check("продажа", rs.selectGroupForTest(AutoTestLinks::isShop), "сделка в «Использованиях»");
            }
        }, 20);
        step("links: uses log", () -> AutoTestLinks.log("использования"), 1);
        shot("links_uses");
        // узкое окно: сжатая база, чип и карточка помещаются
        step("links: narrow", () -> {
            mc().setScreen(null);
            stripWindow(960, 540);
        }, 10);
        step("links: narrow open", () -> {
            linksOpen(Items.ECHO_SHARD, Lookup.Mode.RECIPES);
            RecipeScreen rs = AutoTestLinks.screen();
            if (rs != null) rs.selectGroupForTest(AutoTestLinks::isShop);
        }, 20);
        step("links: narrow log", () -> AutoTestLinks.log("узкое окно"), 1);
        shot("links_narrow");
        step("links: wide", () -> {
            mc().setScreen(null);
            stripWindow(1280, 800);
        }, 10);
        // сломанный поставщик: ошибка в лог один раз, окно работает
        step("links: broken", () -> {
            works.repka.ryscore.links.ItemLinks.register(new AutoTestLinks.Broken());
            linksOpen(Items.ECHO_SHARD, Lookup.Mode.RECIPES);
            AutoTestLinks.check("сломанный поставщик", AutoTestLinks.screen() != null, "карточка открылась");
        }, 20);
        step("links: broken log", () -> AutoTestLinks.log("со сломанным поставщиком"), 1);
        // без поставщика: категорий и чипа нет, всё как раньше
        step("links: none", () -> {
            AutoTestLinks.uninstall();
            linksOpen(Items.ECHO_SHARD, Lookup.Mode.RECIPES);
        }, 20);
        step("links: none log", () -> {
            AutoTestLinks.log("без поставщика");
            RecipeScreen rs = AutoTestLinks.screen();
            boolean clean = rs == null || rs.linksForTest().chips().isEmpty() && !rs.selectGroupForTest(c -> c instanceof works.repka.ryscope.runtime.Links.LinkCategory);
            AutoTestLinks.check("без поставщика", clean, rs == null ? "карточка не открылась (у предмета ничего нет)" : "ни чипа, ни категорий поставщика");
        }, 1);
        shot("links_none");
        step("quit", () -> {
            mc().setScreen(null);
            mc().stop();
        }, 0);
    }

    // --- сценарий strip: строка категории карточки (станции 12, счётчик, «+N», длинное название, узкое окно) ---

    /** Категория с самым длинным названием среди тех, где больше одной станции: проверка обрезки. */
    private static works.repka.ryscope.model.Category stripLong;

    private static void stripLog(String tag) {
        if (!(mc().screen instanceof RecipeScreen rs)) {
            RYScope.LOG.info("[strip] {}: карточка не открыта", tag);
            return;
        }
        RecipeScreen.StripForTest s = rs.stripForTest();
        RYScope.LOG.info("[strip] {}: счётчик «{}», название «{}», значков станций {}, «+N» {}, карточка {}", tag, s.pager(), s.title(),
            s.stations().size(), s.chip()[2] > 0 ? s.chip()[0] + "," + s.chip()[1] + " " + s.chip()[2] + "×" + s.chip()[3] : "нет",
            java.util.Arrays.toString(rs.cardBounds()));
        for (int[] st : s.stations()) {
            if (st[2] != 12) RYScope.LOG.info("[strip] FAIL {}: значок станции {} вместо 12", tag, st[2]);
        }
    }

    private static void stripWindow(int w, int h) {
        mc().getWindow().setWindowed(w, h);
        mc().options.guiScale().set(0);
        mc().resizeDisplay();
    }

    /** Карточка пороха на категории с наибольшим числом станций (у пороха их десятки). */
    private static void stripStations() {
        mc().setScreen(new InventoryScreen(mc().player));
        RecipeScreen.clearTabs();
        RecipeScreen.open(new ItemRes(new ItemStack(Items.GUNPOWDER)), Lookup.Mode.RECIPES);
        if (!(mc().screen instanceof RecipeScreen rs)) return;
        int most = 0;
        for (var c : Lookup.allCategories()) most = Math.max(most, c.stations.size());
        // самая «станционная» категория именно этой карточки
        int[] best = {0};
        rs.selectGroupForTest(c -> {
            best[0] = Math.max(best[0], c.stations.size());
            return false;
        });
        rs.selectGroupForTest(c -> c.stations.size() == best[0]);
        RYScope.LOG.info("[strip] порох: станций в категории {}, больше всего в игре {}", best[0], most);
    }

    private static void stripLongOpen() {
        mc().setScreen(new InventoryScreen(mc().player));
        RecipeScreen.clearTabs();
        if (stripLong == null) return;
        boolean inCard = RecipeScreen.open(stripLong.stations.get(0), Lookup.Mode.USES) && mc().screen instanceof RecipeScreen rs
            && rs.selectGroupForTest(c -> c.id.equals(stripLong.id));
        // станция не показывает категорию в использованиях: тогда просмотр самой категории (без ссылки в названии)
        if (!inCard) RecipeScreen.openCategory(stripLong);
        RYScope.LOG.info("[strip] длинное название: {} «{}», в карточке станции {}", stripLong.id, stripLong.title.getString(), inCard);
    }

    private static void stripHover(java.util.function.Function<RecipeScreen.StripForTest, int[]> at, String what) {
        if (!(mc().screen instanceof RecipeScreen rs)) return;
        int[] p = at.apply(rs.stripForTest());
        if (p == null) {
            RYScope.LOG.info("[strip] навести на {}: нет на экране", what);
            return;
        }
        moveMouse(works.repka.ryscore.ui.UiScale.toGui(p[0]), works.repka.ryscore.ui.UiScale.toGui(p[1]));
    }

    private static void buildStrip() {
        step("strip: setup", () -> {
            Overlay.setSearch("");
            ThemeManager.select("ryscore:slate");
            stripWindow(1280, 800);
            int longest = 0;
            for (var c : Lookup.allCategories()) {
                int len = c.title.getString().length();
                if (c.stations.size() > 1 && len > longest) {
                    longest = len;
                    stripLong = c;
                }
            }
        }, 10);
        // счётчик на одной странице: «N рецептов» без стрелок
        step("strip: piston", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            RecipeScreen.clearTabs();
            RecipeScreen.open(new ItemRes(new ItemStack(Items.PISTON)), Lookup.Mode.RECIPES);
        }, 20);
        step("strip: piston log", () -> stripLog("поршень"), 1);
        shot("strip_piston");
        // несколько страниц: «1–3 из N» и стрелки
        step("strip: stick", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            RecipeScreen.clearTabs();
            RecipeScreen.open(new ItemRes(new ItemStack(Items.STICK)), Lookup.Mode.RECIPES);
        }, 20);
        step("strip: stick log", () -> stripLog("палка"), 1);
        shot("strip_stick");
        step("strip: stick next page", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0), 6);
        step("strip: stick page 2 log", () -> stripLog("палка, страница 2"), 1);
        shot("strip_stick_page2");
        // станции 12 единиц, «+N» и плашка всех станций в полный размер
        step("strip: gunpowder", AutoTest::stripStations, 25);
        step("strip: gunpowder log", () -> stripLog("порох"), 1);
        shot("strip_stations");
        step("strip: hover station", () -> stripHover(s -> s.stations().size() > 1
            ? new int[] {s.stations().get(1)[0] + 6, s.stations().get(1)[1] + 6} : null, "вторую станцию"), 6);
        shot("strip_station_tip");
        step("strip: hover more", () -> stripHover(s -> s.chip()[2] > 0
            ? new int[] {s.chip()[0] + s.chip()[2] / 2, s.chip()[1] + s.chip()[3] / 2} : null, "«+N»"), 6);
        shot("strip_stations_popup");
        step("strip: glass", () -> {
            moveMouse(2, 2);
            ThemeManager.select("ryscore:glass");
        }, 10);
        shot("strip_stations_glass");
        step("strip: slate", () -> ThemeManager.select("ryscore:slate"), 4);
        // длинное название: многоточие, полное название в подсказке, «+N» остаётся на месте
        step("strip: long", AutoTest::stripLongOpen, 25);
        step("strip: long log", () -> stripLog("длинное название"), 1);
        shot("strip_long");
        step("strip: hover long title", () -> stripHover(s -> new int[] {s.titleX() + 10, s.titleY()}, "название"), 6);
        shot("strip_long_tip");
        // узкое окно: сжатая база, строка не налезает на счётчик
        step("strip: narrow", () -> {
            moveMouse(2, 2);
            mc().setScreen(null);
            stripWindow(960, 540);
        }, 10);
        step("strip: narrow long", AutoTest::stripLongOpen, 25);
        step("strip: narrow long log", () -> stripLog("узкое окно, длинное название"), 1);
        shot("strip_narrow_long");
        step("strip: narrow gunpowder", AutoTest::stripStations, 25);
        step("strip: narrow gunpowder log", () -> stripLog("узкое окно, порох"), 1);
        shot("strip_narrow_stations");
        // значки 12 при разном числе пикселей в единице: 2 (1280×800), 2.5 (1920×1080) и 3 (2560×1440 при 150 %), не рябят ли
        int savedScale = works.repka.ryscore.ui.UiScale.setting();
        record PixelCase(String tag, int w, int h, int setting) {}
        for (PixelCase pc : List.of(new PixelCase("px2", 1280, 800, works.repka.ryscore.ui.UiScale.AUTO),
                new PixelCase("px25", 1920, 1080, works.repka.ryscore.ui.UiScale.AUTO), new PixelCase("px3", 2560, 1440, 150))) {
            step("strip: " + pc.tag(), () -> {
                moveMouse(2, 2);
                mc().setScreen(null);
                works.repka.ryscore.ui.UiScale.set(pc.setting());
                stripWindow(pc.w(), pc.h());
            }, 10);
            step("strip: " + pc.tag() + " gunpowder", AutoTest::stripStations, 25);
            step("strip: " + pc.tag() + " log", () -> {
                stripLog("пикселей в единице " + works.repka.ryscore.ui.UiScale.pixels() + ", порох");
            }, 1);
            shot("strip_" + pc.tag() + "_stations");
        }
        step("strip: restore", () -> {
            mc().setScreen(null);
            works.repka.ryscore.ui.UiScale.set(savedScale);
            stripWindow(1280, 800);
        }, 5);
        step("quit", () -> mc().stop(), 0);
    }

    // --- сценарий help: вкладка «Справка» (наши страницы и сведения JEI нашим видом, серая без них) ---

    private static void helpOpen(net.minecraft.world.item.Item item, boolean toHelp) {
        mc().setScreen(new InventoryScreen(mc().player));
        RecipeScreen.clearTabs();
        RecipeScreen.open(new ItemRes(new ItemStack(item)), Lookup.Mode.RECIPES);
        if (toHelp && mc().screen instanceof RecipeScreen rs) rs.switchSectionForTest("help");
    }

    /**
     * Строки {@code [help]} о карточке: раздел, счётчики, страницы справки по порядку и их текст.
     * @param expect "info" — сведения JEI обязательны, "own" — первой идёт наша страница, "none" — справки нет
     */
    private static void helpLog(String tag, String expect) {
        if (!(mc().screen instanceof RecipeScreen rs)) {
            RYScope.LOG.info("[help] FAIL {}: карточка не открыта ({})", tag, mc().screen);
            return;
        }
        RecipeScreen.SectionsForTest s = rs.sectionsForTest();
        List<String> pages = new ArrayList<>();
        int info = 0, own = 0;
        for (RecipeView r : s.help()) {
            String text = r instanceof works.repka.ryscope.jei.JeiInfoView v ? v.plainText()
                : r instanceof works.repka.ryscope.content.HelpView v ? v.plainText() : "?";
            if (r instanceof works.repka.ryscope.jei.JeiInfoView) info++;
            if (r instanceof works.repka.ryscope.content.HelpView) own++;
            pages.add(r.getClass().getSimpleName() + " h" + r.height() + " «" + text.replace("\n", " / ") + "»");
        }
        int shown = 0;
        for (RecipeScreen.Placed p : rs.placed()) {
            if (p.recipe() instanceof works.repka.ryscope.jei.JeiInfoView || p.recipe() instanceof works.repka.ryscope.content.HelpView) shown++;
        }
        int[] scroll = rs.scrollForTest();
        RYScope.LOG.info("[help] {}: раздел {}, счётчики {}, страниц справки {} (наших {}, сведений JEI {}), на экране {}, прокрутка {}/{}",
            tag, s.current(), s.counts(), s.help().size(), own, info, shown, scroll[0], scroll[1]);
        for (String page : pages) RYScope.LOG.info("[help] {}: {}", tag, page);
        if (s.infoOutsideHelp()) RYScope.LOG.info("[help] FAIL {}: сведения JEI остались среди рецептов или использований", tag);
        switch (expect) {
            case "info" -> {
                if (info == 0) RYScope.LOG.info("[help] FAIL {}: сведений JEI нет", tag);
                else if (!"help".equals(s.current())) RYScope.LOG.info("[help] FAIL {}: открыта не «Справка», а {}", tag, s.current());
                else if (shown == 0) RYScope.LOG.info("[help] FAIL {}: страница справки не нарисована", tag);
                else RYScope.LOG.info("[help] PASS {}", tag);
            }
            case "own" -> {
                if (s.help().isEmpty() || !(s.help().get(0) instanceof works.repka.ryscope.content.HelpView)) {
                    RYScope.LOG.info("[help] FAIL {}: наша страница не первая", tag);
                } else if (shown == 0) {
                    RYScope.LOG.info("[help] FAIL {}: страница справки не нарисована", tag);
                } else {
                    RYScope.LOG.info("[help] PASS {}", tag);
                }
            }
            default -> {
                if (!s.help().isEmpty()) RYScope.LOG.info("[help] FAIL {}: справка есть, вкладка не серая", tag);
                else RYScope.LOG.info("[help] PASS {}: вкладка «Справка» серая", tag);
            }
        }
    }

    /** Предмет без справки и сведений: первый из нескольких простых, у которого их нет. */
    private static net.minecraft.world.item.Item helpNone() {
        for (net.minecraft.world.item.Item item : List.of(Items.OAK_BUTTON, Items.STONE_BUTTON, Items.OAK_FENCE_GATE, Items.COBBLESTONE_WALL, Items.STICK)) {
            boolean any = false;
            for (Lookup.Group g : Lookup.find(new ItemRes(new ItemStack(item)), Lookup.Mode.RECIPES)) {
                any |= works.repka.ryscope.content.HelpContent.isHelp(g.category());
            }
            if (!any) return item;
        }
        return Items.STICK;
    }

    private static void buildHelp() {
        step("help: setup", () -> {
            Overlay.setSearch("");
            ThemeManager.select("ryscore:slate");
            stripWindow(1280, 800);
        }, 10);
        // яйцо призыва тихони: рецептов нет, карточка открывается на «Справке» со сведениями JEI
        step("help: egg", () -> helpOpen(Items.CREEPER_SPAWN_EGG, false), 20);
        step("help: egg log", () -> helpLog("яйцо тихони", "info"), 1);
        shot("help_egg");
        step("help: egg glass", () -> ThemeManager.select("ryscore:glass"), 8);
        shot("help_egg_glass");
        step("help: egg vanilla", () -> ThemeManager.select("ryscore:vanilla"), 8);
        shot("help_egg_vanilla");
        step("help: slate", () -> ThemeManager.select("ryscore:slate"), 6);
        // длинный текст: колесо листает страницу, как остальные рецепты
        for (int i = 0; i < 4; i++) {
            step("help: egg scroll", () -> {
                if (!(mc().screen instanceof RecipeScreen rs)) return;
                int[] b = rs.cardBounds();
                rs.mouseScrolled(works.repka.ryscore.ui.UiScale.toGui(b[0] + b[2] - 8), works.repka.ryscore.ui.UiScale.toGui(b[1] + b[3] / 2.0), 0, -1);
            }, 3);
        }
        step("help: egg scroll log", () -> helpLog("яйцо тихони после колеса", "info"), 1);
        shot("help_egg_scrolled");
        // предмет страницы в слоте слева сверху ведёт себя как предмет: подсказка при наведении
        step("help: egg hover icon", () -> {
            if (!(mc().screen instanceof RecipeScreen rs) || rs.placed().isEmpty()) return;
            RecipeScreen.Placed p = rs.placed().get(0);
            moveMouse(works.repka.ryscore.ui.UiScale.toGui(p.x() + 9), works.repka.ryscore.ui.UiScale.toGui(p.y() + 9));
        }, 6);
        shot("help_egg_tip");
        // поршень: своя страница справки из config/ryscope/help (пример автотеста), сведения JEI следом, если есть
        step("help: piston", () -> {
            moveMouse(2, 2);
            helpOpen(Items.PISTON, true);
        }, 20);
        step("help: piston log", () -> helpLog("поршень", "own"), 1);
        shot("help_piston");
        // предмет без справки и сведений: вкладка серая
        step("help: none", () -> helpOpen(helpNone(), false), 20);
        step("help: none log", () -> helpLog("без справки", "none"), 1);
        shot("help_none");
        // узкое окно: сжатая база, текст переносится так же
        step("help: narrow", () -> {
            mc().setScreen(null);
            stripWindow(960, 540);
        }, 10);
        step("help: narrow egg", () -> helpOpen(Items.CREEPER_SPAWN_EGG, false), 20);
        step("help: narrow egg log", () -> helpLog("узкое окно, яйцо тихони", "info"), 1);
        shot("help_narrow_egg");
        step("help: restore", () -> {
            mc().setScreen(null);
            stripWindow(1280, 800);
        }, 5);
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

    // --- сценарий feedback: замечания игроков сборки RepYS ---

    private static final net.minecraft.resources.ResourceLocation CONFIGURABLE_EGG = net.minecraft.resources.ResourceLocation.parse("productivebees:spawn_egg_configurable_bee");
    private static works.repka.ryscope.runtime.Snapshot snapBefore;
    private static Stack chestFromList;

    private static int recipeCount(Stack stack) {
        int n = 0;
        for (Lookup.Group g : Lookup.find(stack, Lookup.Mode.RECIPES)) n += g.recipes().size();
        return n;
    }

    private static String recipeIds(Stack stack) {
        StringBuilder sb = new StringBuilder();
        for (Lookup.Group g : Lookup.find(stack, Lookup.Mode.RECIPES)) {
            sb.append(g.category().id).append(' ').append(g.recipes().stream().map(r -> String.valueOf(r.id())).toList()).append("; ");
        }
        return sb.isEmpty() ? "ничего" : sb.toString();
    }

    /** Яйцо пчелы из списка (так его берёт игрок): яйцо настраиваемой пчелы с этим видом в данных. */
    private static Stack beeEgg(String bee) {
        for (Stack s : Indexer.snapshot().stacks) {
            if (!(s instanceof ItemRes item) || !s.id().equals(CONFIGURABLE_EGG)) continue;
            var data = item.stack.get(net.minecraft.core.component.DataComponents.ENTITY_DATA);
            if (data != null && bee.equals(data.getUnsafe().getString("type"))) return s;
        }
        return null;
    }

    /** Сама пчела: ресурс JEI Productive Bees. */
    private static Stack bee(String bee) {
        for (Stack s : Indexer.snapshot().stacks) {
            if (!(s instanceof ItemRes) && bee.equals(String.valueOf(s.id()))) return s;
        }
        return null;
    }

    private static void logBee(String phase) {
        Stack egg = beeEgg("productivebees:brass"), bee = bee("productivebees:brass");
        RYScope.LOG.info("[bees] {}: яйцо латунной пчелы в списке {}, данные {}", phase, egg != null,
            egg == null ? null : ((ItemRes) egg).stack.getComponentsPatch());
        if (egg != null) {
            RYScope.LOG.info("[bees] {}: подтип яйца {}", phase, Foreign.get() == null ? null : Foreign.get().subtypeUid(egg));
            RYScope.LOG.info("[bees] {}: RYScope по R на яйце: {}", phase, recipeIds(egg));
            RYScope.LOG.info("[bees] {}: JEI по R на яйце: {}", phase, AutoTestJei.byOutput(((ItemRes) egg).stack));
        }
        Stack steel = beeEgg("productivebees:steel");
        if (steel != null) RYScope.LOG.info("[bees] {}: RYScope по R на яйце стальной: {}", phase, recipeIds(steel));
        RYScope.LOG.info("[bees] {}: пчела в списке {}", phase, bee == null ? "нет" : bee.getClass().getSimpleName());
        if (bee != null) {
            RYScope.LOG.info("[bees] {}: RYScope по R на пчеле: {}", phase, recipeIds(bee));
            RYScope.LOG.info("[bees] {}: JEI по R на пчеле: {}", phase, AutoTestJei.byOutput(bee));
        }
        var server = mc().getSingleplayerServer();
        if (server != null) {
            long casting = 0, eggs = 0;
            List<String> pbees = new ArrayList<>();
            for (var h : server.getRecipeManager().getRecipes()) {
                ItemStack out;
                try {
                    out = h.value().getResultItem(server.registryAccess());
                } catch (Throwable t) {
                    continue;
                }
                if (!itemKey(out).equals(CONFIGURABLE_EGG)) continue;
                eggs++;
                if (h.id().getPath().startsWith("casting/pbees/")) {
                    casting++;
                    pbees.add(h.id().getPath());
                }
            }
            RYScope.LOG.info("[bees] {}: на сервере рецептов с яйцом настраиваемой пчелы на выходе {}, из них литьё пчёл Metalworks {} {}", phase, eggs, casting, pbees);
        }
    }

    private static net.minecraft.resources.ResourceLocation itemKey(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    /**
     * Возвращает на сервер рецепты с яйцом настраиваемой пчелы на выходе, которые есть в данных модов, проходят свои
     * условия, но в игре отсутствуют (их убрал скрипт сборки). Так исправленный скрипт сборки и выглядел бы для игры:
     * проверка исправления без правки файлов. Потом сервер рассылает рецепты, как после /reload.
     */
    private static void restoreEggRecipes() {
        var server = mc().getSingleplayerServer();
        server.execute(() -> {
            try {
                var rm = server.getRecipeManager();
                var ops = new net.neoforged.neoforge.common.conditions.ConditionalOps<>(
                    server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),
                    tagContext(server));
                var codec = net.neoforged.neoforge.common.conditions.ConditionalOps.createConditionalCodec(net.minecraft.world.item.crafting.Recipe.CODEC);
                java.util.Set<net.minecraft.resources.ResourceLocation> have = new java.util.HashSet<>();
                for (var h : rm.getRecipes()) have.add(h.id());
                List<net.minecraft.world.item.crafting.RecipeHolder<?>> add = new ArrayList<>();
                int files = 0, failed = 0, conditions = 0;
                for (var e : server.getResourceManager().listResources("recipe", p -> p.getPath().endsWith(".json")).entrySet()) {
                    files++;
                    String text;
                    try (var reader = e.getValue().openAsReader()) {
                        text = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                    }
                    if (!text.contains("spawn_egg_configurable_bee")) continue;
                    String path = e.getKey().getPath();
                    var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(e.getKey().getNamespace(), path.substring("recipe/".length(), path.length() - ".json".length()));
                    if (have.contains(id)) continue;
                    var parsed = codec.parse(ops, com.google.gson.JsonParser.parseString(text));
                    if (parsed.error().isPresent()) {
                        failed++;
                        continue;
                    }
                    var recipe = parsed.getOrThrow();
                    if (recipe.isEmpty()) {
                        conditions++;
                        continue;
                    }
                    ItemStack out;
                    try {
                        out = recipe.get().getResultItem(server.registryAccess());
                    } catch (Throwable t) {
                        continue;
                    }
                    if (!itemKey(out).equals(CONFIGURABLE_EGG)) continue;
                    add.add(new net.minecraft.world.item.crafting.RecipeHolder<>(id, recipe.get()));
                }
                RYScope.LOG.info("[bees] файлов рецептов {}, с яйцом не разобрано {}, не прошли условия {}, отсутствуют в игре и возвращены {}: {}",
                    files, failed, conditions, add.size(), add.stream().map(h -> h.id().toString()).sorted().toList());
                List<net.minecraft.world.item.crafting.RecipeHolder<?>> all = new ArrayList<>(rm.getRecipes());
                all.addAll(add);
                rm.replaceRecipes(all);
                server.getPlayerList().reloadResources();
            } catch (Throwable t) {
                RYScope.LOG.error("[bees] возврат рецептов", t);
            }
        });
    }

    /** Условия рецептов с тегами (вид пчелы есть, только если тег блоков латуни не пуст) сверяются по тегам мира. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.neoforged.neoforge.common.conditions.ICondition.IContext tagContext(net.minecraft.server.MinecraftServer server) {
        return new net.neoforged.neoforge.common.conditions.ICondition.IContext() {
            @Override
            public <T> java.util.Map<net.minecraft.resources.ResourceLocation, java.util.Collection<net.minecraft.core.Holder<T>>> getAllTags(
                    net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> key) {
                java.util.Map<net.minecraft.resources.ResourceLocation, java.util.Collection<net.minecraft.core.Holder<T>>> out = new java.util.HashMap<>();
                server.registryAccess().registry((net.minecraft.resources.ResourceKey) key).ifPresent(reg ->
                    ((net.minecraft.core.Registry<T>) reg).getTags().forEach(p -> out.put(p.getFirst().location(), p.getSecond().stream().toList())));
                return out;
            }
        };
    }

    /** Листает вкладки окна рецептов вниз, пока первый рецепт вкладки не будет с этим куском id (не больше десяти шагов). */
    private static void tabTo(String category) {
        for (int i = 0; i < 10; i++) {
            step("tab to " + category, () -> {
                if (!(mc().screen instanceof RecipeScreen rs) || rs.placed().isEmpty()) return;
                if (String.valueOf(rs.placed().get(0).recipe().id()).contains(category)) return;
                rs.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
            }, 15);
        }
    }

    private static void buildFeedback() {
        // список с ресурсами JEI (пчёлы Productive Bees, варианты сундуков из вкладок): индекс после подключения JEI
        step("feedback: jei index", () -> (Foreign.get() != null && Indexer.state() == Indexer.State.READY
            && Indexer.snapshot().stacks.stream().anyMatch(s -> s instanceof works.repka.ryscope.jei.JeiRes<?>)) || stuck > 12000, () ->
            RYScope.LOG.info("[feedback] индекс: ресурсов {}, ждали тиков {}", Indexer.snapshot().stacks.size(), stuck), 100);
        // 1. алмазные сундуки Sophisticated Storage: клик по варианту в списке
        step("chests: inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 20);
        step("chests: search", () -> Overlay.setSearch("алмазный сундук"), 40);
        step("chests: state", () -> {
            List<Stack> list = SearchService.results().stacks;
            RYScope.LOG.info("[chests] «алмазный сундук»: в списке {}", list.size());
            Foreign.Source foreign = Foreign.get();
            for (Stack s : list) {
                if (!(s instanceof ItemRes item)) continue;
                Lookup.legacyForeignDedup = true;
                int before = recipeCount(s);
                Lookup.legacyForeignDedup = false;
                int after = recipeCount(s);
                RYScope.LOG.info("[chests] {} {}: данные {}, подтип {}, рецептов было {}, стало {}", s.id(), s.name().getString(),
                    item.stack.getComponentsPatch(), foreign == null ? null : foreign.subtypeUid(s), before, after);
                if (chestFromList == null && s.id().getPath().equals("diamond_chest") && String.valueOf(item.stack.getComponentsPatch()).contains("spruce")) chestFromList = s;
            }
            if (chestFromList == null && !list.isEmpty()) chestFromList = list.get(Math.min(1, list.size() - 1));
            if (chestFromList != null) {
                RYScope.LOG.info("[chests] выбран {}: RYScope {}", chestFromList.name().getString(), recipeIds(chestFromList));
                RYScope.LOG.info("[chests] JEI по R: {}", AutoTestJei.byOutput(((ItemRes) chestFromList).stack));
                Stack plain = new ItemRes(new ItemStack(((ItemRes) chestFromList).stack.getItem()));
                RYScope.LOG.info("[chests] сундук без данных (как в рецепте): подтип {}, RYScope {}", foreign == null ? null : foreign.subtypeUid(plain), recipeIds(plain));
            }
            parkMouse();
        }, 5);
        shot("chests_list");
        step("chests: before", () -> {
            Lookup.legacyForeignDedup = true;
            if (chestFromList != null) RecipeScreen.open(chestFromList, Lookup.Mode.RECIPES);
        }, 40);
        shot("chests_before");
        step("chests: back", () -> mc().setScreen(new InventoryScreen(mc().player)), 10);
        step("chests: after", () -> {
            Lookup.legacyForeignDedup = false;
            if (chestFromList != null) RecipeScreen.open(chestFromList, Lookup.Mode.RECIPES);
        }, 40);
        shot("chests_after");
        step("chests: clear", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            Overlay.setSearch("");
        }, 10);

        // 2. пчёлы Productive Bees из литья Productive Metalworks
        step("bees: before", () -> logBee("сейчас"), 5);
        step("bees: egg before", () -> {
            Stack egg = beeEgg("productivebees:brass");
            if (egg != null) RecipeScreen.open(egg, Lookup.Mode.RECIPES);
        }, 40);
        shot("bee_egg_before");
        step("bees: bee before", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            Stack bee = bee("productivebees:brass");
            if (bee != null) RecipeScreen.open(bee, Lookup.Mode.RECIPES);
        }, 40);
        shot("bee_before");
        step("bees: restore", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            snapBefore = Indexer.snapshot();
            restoreEggRecipes();
        }, 20);
        step("bees: reindex", () -> Indexer.snapshot() != snapBefore && Indexer.state() == Indexer.State.READY && Foreign.get() != null, () -> {}, 200);
        step("bees: after", () -> logBee("с возвращёнными рецептами"), 5);
        step("bees: egg after", () -> {
            Stack egg = beeEgg("productivebees:brass");
            if (egg != null) RecipeScreen.open(egg, Lookup.Mode.RECIPES);
        }, 40);
        shot("bee_egg_after");
        tabTo("casting/pbees/");
        shot("bee_egg_after_casting");
        step("bees: bee after", () -> {
            mc().setScreen(new InventoryScreen(mc().player));
            Stack bee = bee("productivebees:brass");
            if (bee != null) RecipeScreen.open(bee, Lookup.Mode.RECIPES);
        }, 40);
        shot("bee_after");
        tabTo("casting/pbees/");
        shot("bee_after_casting");
        step("quit", () -> mc().stop(), 0);
    }

    // --- сценарий dock: панель кнопок FTB ---

    private static boolean ftbWidgetOnScreen() {
        return mc().screen.children().stream().anyMatch(works.repka.ryscore.dock.Dock::isFtbWidget);
    }

    private static void setOverlay(boolean on) {
        Config.get().overlayEnabled = on;
        mc().screen.resize(mc(), mc().screen.width, mc().screen.height);
    }

    private static void buildDock() {
        step("dock: open", () -> mc().setScreen(new InventoryScreen(mc().player)), 30);
        step("dock: state", () -> RYScope.LOG.info("[dock] панель видна: {}, виджет FTB на экране: {}, кнопки: {}",
            works.repka.ryscore.dock.Dock.visible(), ftbWidgetOnScreen(), works.repka.ryscore.dock.Dock.keys()), 2);
        shot("dock_inventory");
        step("dock: hover", () -> {
            int[] p = works.repka.ryscore.dock.Dock.center("ftbquests_quests");
            RYScope.LOG.info("[dock] кнопка квестов: {}", p == null ? "НЕТ" : p[0] + ", " + p[1]);
            // центр в единицах интерфейса, курсор в единицах GUI игры
            if (p != null) moveMouse(works.repka.ryscore.ui.UiScale.toGui(p[0]), works.repka.ryscore.ui.UiScale.toGui(p[1]));
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
            works.repka.ryscore.dock.Dock.visible(), ftbWidgetOnScreen()), 2);
        shot("dock_crafting");
        step("dock: inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 15);
        step("dock: overlay off", () -> setOverlay(false), 15);
        step("dock: off state", () -> RYScope.LOG.info("[dock] наши панели выключены: виджет FTB вернулся: {}", ftbWidgetOnScreen()), 2);
        shot("dock_overlay_off");
        step("dock: overlay on", () -> setOverlay(true), 15);
        step("dock: on state", () -> RYScope.LOG.info("[dock] наши панели включены: панель видна {}, виджет FTB {}",
            works.repka.ryscore.dock.Dock.visible(), ftbWidgetOnScreen()), 2);
        step("quit", () -> mc().stop(), 0);
    }

    // --- сценарий corekit: новое в коре 1.9.0 (линии, значки, виджеты, база масштаба, реестр кнопок) ---

    /** Снимок без очистки тостов: общие уведомления семьи сами тосты игры. */
    private static void shotWithToasts(String name) {
        step("shot:" + name, () -> {
            String file = String.format("ryscope_%02d_%s.png", shotCounter++, name);
            Screenshot.grab(mc().gameDirectory, file, mc().getMainRenderTarget(), msg -> {});
        }, 3);
    }

    private static int[] noticeBefore;
    private static boolean noticeClicked;

    /** Три тоста игры, пришедшие разом: «открыт рецепт», FTB Quests и любой другой стоят в одной колонке справа вверху. */
    private static void gameToasts() {
        for (String title : new String[] {"Новые рецепты!", "Квест выполнен!", "Достижение получено!"}) {
            net.minecraft.client.gui.components.toasts.SystemToast.add(mc().getToasts(),
                net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                net.minecraft.network.chat.Component.literal(title), net.minecraft.network.chat.Component.literal("Медная катушка"));
        }
    }

    /**
     * Уведомления семьи (ядро 1.9.2) поверх окна RYScope и сдвиг содержимого: раньше область тостов, которую JEI отдаёт как
     * занятую ({@code ToastGuiHandler}), опускала панель справа; теперь тосты лежат поверх, панель стоит на месте.
     */
    private static void buildNotices() {
        step("notices: window", () -> {
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 1280, 800);
            mc().options.guiScale().set(2);
            mc().resizeDisplay();
            mc().getToasts().clear();
            moveMouse(1, 1);
        }, 30);
        step("notices: inventory", () -> mc().setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc().player)), 30);
        step("notices: panel without toasts", () -> {
            noticeBefore = works.repka.ryscope.ui.Overlay.searchRectForTest();
            RYScope.LOG.info("[autotest] поле поиска без тостов: {}", java.util.Arrays.toString(noticeBefore));
        }, 2);
        step("notices: before the fix", () -> {
            works.repka.ryscope.jei.JeiBridge.KEEP_TOAST_AREA = true;
            gameToasts();
        }, 40);
        step("notices: moved before", () -> {
            int[] now = works.repka.ryscope.ui.Overlay.searchRectForTest();
            Foreign.Source foreign = Foreign.get();
            RYScope.LOG.info("[autotest] до исправления: поле поиска {} (было {}), области JEI {}", java.util.Arrays.toString(now),
                java.util.Arrays.toString(noticeBefore), foreign == null ? "нет JEI" : foreign.exclusionAreas(mc().screen).stream().map(java.util.Arrays::toString).toList());
        }, 2);
        shotWithToasts("notices_shift_before");
        step("notices: after the fix", () -> {
            mc().getToasts().clear();
            works.repka.ryscope.jei.JeiBridge.KEEP_TOAST_AREA = false;
        }, 10);
        step("notices: toasts and family notices", () -> {
            gameToasts();
            works.repka.ryscore.ui.Notice.card(works.repka.ryscore.ui.Notice.QUESTS, "autotest").item(new ItemStack(Items.COPPER_INGOT)).check()
                .title("Медная катушка").lines("Награда в книге: 25 опыта и 1 монета").key(() -> "K").action(() -> noticeClicked = true)
                .millis(30_000).show();
        }, 40);
        step("notices: did not move", () -> {
            int[] now = works.repka.ryscope.ui.Overlay.searchRectForTest();
            boolean same = java.util.Arrays.equals(now, noticeBefore);
            RYScope.LOG.info("[autotest] {} после исправления: поле поиска {} (было {})", same ? "OK" : "FAIL", java.util.Arrays.toString(now), java.util.Arrays.toString(noticeBefore));
        }, 2);
        shotWithToasts("notices_shift_after");
        step("notices: a line of RYScope", () -> works.repka.ryscope.ui.Overlay.toast(net.minecraft.network.chat.Component.translatable("ryscope.bookmark.added").getString()), 30);
        shotWithToasts("notices_scope_row");
        step("notices: mouse on the card", () -> {
            int[] b = works.repka.ryscore.ui.Notices.boundsForTest(works.repka.ryscore.ui.Notice.QUESTS, "autotest");
            RYScope.LOG.info("[autotest] карточка {}", java.util.Arrays.toString(b));
            if (b != null) moveMouse(b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
        }, 10);
        shotWithToasts("notices_hover_over_ryscope");
        step("notices: click the card", () -> click(0), 10);
        step("notices: clicked", () -> RYScope.LOG.info("[autotest] {} клик по карточке: дело выполнено {}, окно {} осталось",
            noticeClicked && mc().screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen ? "OK" : "FAIL", noticeClicked,
            mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName()), 2);
        step("notices: close", () -> {
            mc().setScreen(null);
            mc().getToasts().clear();
        }, 5);
        step("quit", () -> mc().stop(), 0);
    }

    private static void buildCoreKit() {
        step("corekit: setup", () -> {
            mc().options.enableVsync().set(false);
            mc().options.framerateLimit().set(260);
            mc().getWindow().updateVsync(false);
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 1280, 800);
        }, 40);
        for (String theme : new String[] {"ryscore:slate", "ryscore:glass", "ryscore:vanilla"}) {
            step("corekit: kit " + theme, () -> {
                ThemeManager.select(theme);
                CoreKitBench.mode = CoreKitBench.Mode.KIT;
                mc().setScreen(new CoreKitBench());
            }, 30);
            step("corekit: hover " + theme, () -> moveMouse(works.repka.ryscore.ui.UiScale.toGui(560), works.repka.ryscore.ui.UiScale.toGui(272)), 6);
            shot("corekit_" + theme.substring(8));
        }
        step("corekit: dialog", () -> {
            ThemeManager.select("ryscore:slate");
            CoreKitBench.mode = CoreKitBench.Mode.DIALOG;
            mc().setScreen(new CoreKitBench());
        }, 30);
        shot("corekit_dialog");
        step("corekit: esc", () -> {
            CoreKitBench.clicked = "";
            mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            RYScope.LOG.info("[corekit] Esc в окне-вопросе ответил: {}, экран открыт: {}", CoreKitBench.clicked, mc().screen instanceof CoreKitBench);
        }, 4);
        step("corekit: lines", () -> {
            CoreKitBench.mode = CoreKitBench.Mode.LINES;
            mc().setScreen(new CoreKitBench());
        }, 20);
        step("corekit: lines reset", () -> {
            CoreKitBench.reset();
            framesReset();
        }, 200);
        step("corekit: lines report", () -> RYScope.LOG.info("[corekit] 3200 наклонных дорог и 4900 узлов: {}; {}", CoreKitBench.report(), framesReport()), 2);
        shot("corekit_lines");
        step("corekit: compact window", () -> org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 1366, 768), 30);
        step("corekit: compact", () -> {
            CoreKitBench.mode = CoreKitBench.Mode.COMPACT;
            mc().setScreen(new CoreKitBench());
        }, 20);
        step("corekit: compact report", () -> {
            CoreKitBench s = (CoreKitBench) mc().screen;
            RYScope.LOG.info("[corekit] окно {}×{}: сжатая база нужна {}, экран {}×{} ед. при {} px/ед., база экрана {}×{}; вне экрана (HUD) {} px/ед., fit(640,400) {}, fit(480,270) {}",
                mc().getWindow().getWidth(), mc().getWindow().getHeight(), works.repka.ryscore.ui.UiScale.compactNeeded(), s.width, s.height, s.pixels(),
                s.baseWidth(), s.baseHeight(), works.repka.ryscore.ui.UiScale.pixels(), works.repka.ryscore.ui.UiScale.fit(640, 400), works.repka.ryscore.ui.UiScale.fit(480, 270));
        }, 2);
        shot("corekit_compact");
        step("corekit: window back", () -> org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 1280, 800), 30);
        step("corekit: dock button", () -> works.repka.ryscore.dock.DockButton.builder("ryscope_autotest")
            .label(net.minecraft.network.chat.Component.literal("Квесты"))
            .description(() -> net.minecraft.network.chat.Component.literal("Квестов пока нет. Откройте, чтобы собрать книгу"))
            .tile(0xFFE9A83A).icon(works.repka.ryscore.ui.Icons.Shape.BOOK).key(works.repka.ryscope.client.Keys.LENS).order(0)
            .action(() -> RYScope.LOG.info("[corekit] нажата кнопка семьи на панели")).register(), 2);
        step("corekit: inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 30);
        step("corekit: dock state", () -> RYScope.LOG.info("[corekit] панель: видна {}, хозяин {}, заменяет FTB {}, сама {}, кнопки {}",
            works.repka.ryscore.dock.Dock.visible(), works.repka.ryscore.dock.Dock.hosted(mc().screen), works.repka.ryscore.dock.Dock.replaces(mc().screen),
            works.repka.ryscore.dock.Dock.standalone(mc().screen), works.repka.ryscore.dock.Dock.keys()), 2);
        step("corekit: dock hover", () -> {
            int[] p = works.repka.ryscore.dock.Dock.center("ryscope_autotest");
            RYScope.LOG.info("[corekit] кнопка семьи: {}", p == null ? "НЕТ" : p[0] + ", " + p[1]);
            if (p != null) moveMouse(works.repka.ryscore.ui.UiScale.toGui(p[0]), works.repka.ryscore.ui.UiScale.toGui(p[1]));
        }, 8);
        shot("corekit_dock_hover");
        step("corekit: dock click", () -> click(0), 10);
        step("corekit: overlay off", () -> setOverlay(false), 15);
        step("corekit: off state", () -> RYScope.LOG.info("[corekit] панели RYScope выключены: панель видна {}, сама {}, FTB показывает свою {}, виджет FTB {}",
            works.repka.ryscore.dock.Dock.visible(), works.repka.ryscore.dock.Dock.standalone(mc().screen), works.repka.ryscore.dock.Dock.ftbVisible(mc().screen), ftbWidgetOnScreen()), 2);
        step("corekit: off hover", () -> {
            int[] p = works.repka.ryscore.dock.Dock.center("ryscope_autotest");
            if (p != null) moveMouse(works.repka.ryscore.ui.UiScale.toGui(p[0]), works.repka.ryscore.ui.UiScale.toGui(p[1]));
        }, 8);
        shot("corekit_dock_overlay_off");
        // без панели FTB: с выключенными панелями RYScope панель рисует сам кор, с включёнными её ставит RYScope
        step("corekit: ftb sidebar off", () -> {
            ftbSidebar(false);
            mc().setScreen(new InventoryScreen(mc().player));
        }, 20);
        step("corekit: standalone state", () -> RYScope.LOG.info("[corekit] без FTB и без панелей RYScope: кор рисует сам {}, панель видна {}, кнопки {}",
            works.repka.ryscore.dock.Dock.standalone(mc().screen), works.repka.ryscore.dock.Dock.visible(), works.repka.ryscore.dock.Dock.keys()), 2);
        step("corekit: standalone hover", () -> {
            int[] p = works.repka.ryscore.dock.Dock.center("ryscope_autotest");
            RYScope.LOG.info("[corekit] кнопка семьи у инвентаря: {}", p == null ? "НЕТ" : p[0] + ", " + p[1]);
            if (p != null) moveMouse(works.repka.ryscore.ui.UiScale.toGui(p[0]), works.repka.ryscore.ui.UiScale.toGui(p[1]));
        }, 8);
        shot("corekit_dock_standalone");
        step("corekit: standalone click", () -> click(0), 10);
        step("corekit: hosted no ftb", () -> setOverlay(true), 20);
        step("corekit: hosted state", () -> RYScope.LOG.info("[corekit] без FTB, панели RYScope включены: хозяин {}, панель видна {}, кнопки {}",
            works.repka.ryscore.dock.Dock.hosted(mc().screen), works.repka.ryscore.dock.Dock.visible(), works.repka.ryscore.dock.Dock.keys()), 2);
        shot("corekit_dock_hosted_noftb");
        step("corekit: ftb sidebar on", () -> {
            ftbSidebar(true);
            mc().setScreen(new InventoryScreen(mc().player));
        }, 15);
        step("quit", () -> mc().stop(), 0);
    }

    /** Включает и выключает панель FTB Library её же настройкой (через отражение: FTB не зависимость). */
    private static void ftbSidebar(boolean on) {
        try {
            Object value = Class.forName("dev.ftb.mods.ftblibrary.config.FTBLibraryClientConfig").getField("SIDEBAR_ENABLED").get(null);
            value.getClass().getMethod("set", Object.class).invoke(value, on);
        } catch (Throwable t) {
            RYScope.LOG.warn("[corekit] настройка панели FTB недоступна: {}", t.toString());
        }
    }

    private static void search(String text) {
        step("search:" + text, () -> Overlay.setSearch(text), 8);
    }

    private static void tick() {
        if (index >= STEPS.size()) return;
        if (total == 0) {
            // автотесты идут без звука (просьба автора): игра могла записать громкость обратно в options.txt
            mc().options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
            mc().options.save();
        }
        if (++total > tickLimit) {
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
