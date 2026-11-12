package works.repka.ryscope.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.server.packs.resources.ResourceManager;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.runtime.Bookmarks;
import works.repka.ryscope.runtime.Config;
import works.repka.ryscope.runtime.Indexer;
import works.repka.ryscope.runtime.InventoryView;
import works.repka.ryscope.ui.Lens;
import works.repka.ryscope.ui.Overlay;
import works.repka.ryscope.ui.RecipeScreen;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.UiScale;

/** Точка входа клиента. Модуль загрузчика переводит свои события в вызовы этих методов. */
public final class RyscopeClient {
    private static boolean recipesSeen, tagsSeen;
    private static int indexDelay = -1;

    private RyscopeClient() {}

    public static void init() {
        Config.get();
        works.repka.ryscore.team.TeamCards.register(RyscopeClient::goalsCard);
        RYScope.LOG.info("{} client ready", RYScope.NAME);
    }

    /** Карточка на экране команды: что игрок сейчас собирает. */
    private static works.repka.ryscore.team.TeamCards.Card goalsCard() {
        java.util.List<works.repka.ryscope.runtime.Board.Goal> goals = works.repka.ryscope.runtime.Board.goals();
        if (goals.isEmpty()) return null;
        java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(3, goals.size()); i++) {
            var goal = goals.get(i);
            long have = Math.min(goal.amount, InventoryView.count(goal.stack));
            lines.add(net.minecraft.network.chat.Component.literal(goal.stack.name().getString() + ": " + have + " / " + goal.amount));
        }
        var first = goals.get(0).stack;
        return new works.repka.ryscore.team.TeamCards.Card(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ryscope", "scope"),
            net.minecraft.network.chat.Component.translatable("ryscope.goals"), lines,
            net.minecraft.network.chat.Component.translatable("ryscope.goals.tree"), () -> works.repka.ryscope.ui.TreeScreen.open(first), false);
    }

    // --- данные ---

    public static void onRecipesUpdated() {
        recipesSeen = true;
        scheduleIndex();
    }

    public static void onTagsUpdated() {
        tagsSeen = true;
        scheduleIndex();
    }

    private static void scheduleIndex() {
        // рецепты и теги приходят отдельными пакетами: ждём оба, но не дольше секунды
        indexDelay = recipesSeen && tagsSeen ? 2 : 20;
    }

    public static void onLogout() {
        recipesSeen = tagsSeen = false;
        indexDelay = -1;
        Indexer.clear();
        Bookmarks.reset();
        works.repka.ryscope.runtime.LootSource.clear();
        works.repka.ryscope.runtime.Board.reset();
        works.repka.ryscope.ui.RecipeScreen.clearTabs();
        works.repka.ryscope.runtime.Stages.reset();
    }

    public static void onResourceReload(ResourceManager manager) {
        works.repka.ryscope.content.JsonLayout.reload(manager);
        works.repka.ryscope.runtime.Groups.reload(manager);
        works.repka.ryscope.runtime.Stages.reload(manager);
        works.repka.ryscope.content.HelpContent.reload(manager);
        Indexer.invalidateLanguages();
        if (Minecraft.getInstance().level != null) indexDelay = 2;
    }

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        InventoryView.tick();
        works.repka.ryscope.runtime.Storages.tick();
        works.repka.ryscope.runtime.Board.tick();
        works.repka.ryscope.runtime.Stages.tick();
        works.repka.ryscope.runtime.SearchService.warmTooltips();
        if (indexDelay >= 0 && --indexDelay < 0) {
            recipesSeen = tagsSeen = false;
            Indexer.request("обновление данных");
        }
        if (Indexer.state() == Indexer.State.IDLE && indexDelay < 0 && mc.player != null) {
            // на случай, если события пришли до входа в мир
            Indexer.request("вход в мир");
        }
    }

    // --- экран ---

    private static int uiMouseX() {
        return (int) Math.floor(UiScale.mouseX());
    }

    private static int uiMouseY() {
        return (int) Math.floor(UiScale.mouseY());
    }

    private static boolean container(Screen screen) {
        return screen instanceof AbstractContainerScreen<?>;
    }

    /** Панели в этом кадре уже нарисованы на событии фона контейнера. */
    private static boolean panelsDrawn;

    public static void renderContainerBackground(Screen screen, GuiGraphics g, int mx, int my, float partialTick) {
        if (!container(screen)) return;
        panelsDrawn = true;
        // чужое окно может оставить включённую обрезку или прозрачный цвет (контроллер Modular Machinery
        // рисует всё окно внутри renderBackground): тогда панели не видны, хотя мышь по ним работает
        boolean scissor = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        int[] box = new int[4];
        if (scissor) org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_SCISSOR_BOX, box);
        float[] color = com.mojang.blaze3d.systems.RenderSystem.getShaderColor().clone();
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.disableScissor();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        try {
            UiScale.render(g, scaled -> Overlay.renderBackground(screen, scaled, uiMouseX(), uiMouseY(), partialTick));
        } finally {
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            if (scissor) com.mojang.blaze3d.systems.RenderSystem.enableScissor(box[0], box[1], box[2], box[3]);
        }
    }

    /** Экран чужого мода: не инвентарь и не наш собственный (квесты, справочники). */
    private static boolean foreign(Screen screen) {
        return screen != null && !container(screen) && !screen.getClass().getName().startsWith("works.repka.ryscope.");
    }

    public static void renderScreenPost(Screen screen, GuiGraphics g, int mx, int my, float partialTick) {
        boolean drawn = panelsDrawn;
        panelsDrawn = false;
        // окно, которое не шлёт событие фона (контроллер Modular Machinery переопределяет render целиком):
        // панели рисуются в конце кадра, иначе они невидимы, а мышь натыкается на прошлую раскладку
        if (container(screen) && !drawn) renderContainerBackground(screen, g, mx, my, partialTick);
        panelsDrawn = false;
        if (container(screen)) {
            Overlay.renderContainerMarks(screen, g);
            UiScale.render(g, scaled -> {
                Overlay.renderTop(screen, scaled, uiMouseX(), uiMouseY(), partialTick);
                works.repka.ryscope.ui.ClickAreas.renderTooltip(screen, scaled, uiMouseX(), uiMouseY());
            });
        } else if (foreign(screen)) {
            UiScale.render(g, scaled -> Overlay.renderForeign(screen, scaled, uiMouseX(), uiMouseY()));
        }
    }

    public static boolean mouseClicked(Screen screen, double mx, double my, int button) {
        if (!container(screen)) return false;
        if (Overlay.mouseClicked(screen, UiScale.fromGui(mx), UiScale.fromGui(my), button)) return true;
        // стрелка печи или машины открывает её рецепты, как в JEI
        return works.repka.ryscope.ui.ClickAreas.click(screen, mx, my, button);
    }

    public static boolean mouseReleased(Screen screen, double mx, double my, int button) {
        return container(screen) && Overlay.mouseReleased(screen, UiScale.fromGui(mx), UiScale.fromGui(my), button);
    }

    public static boolean mouseDragged(Screen screen, double mx, double my, int button) {
        return container(screen) && Overlay.mouseDragged(screen, UiScale.fromGui(mx), UiScale.fromGui(my), button);
    }

    public static boolean mouseScrolled(Screen screen, double mx, double my, double delta) {
        return container(screen) && Overlay.mouseScrolled(screen, UiScale.fromGui(mx), UiScale.fromGui(my), delta);
    }

    public static boolean keyPressed(Screen screen, int key, int scan, int mods) {
        if (container(screen)) return Overlay.keyPressed(screen, key, scan, mods);
        return foreign(screen) && Overlay.foreignKey(screen, key, scan);
    }

    public static boolean charTyped(Screen screen, char c, int mods) {
        return container(screen) && Overlay.charTyped(screen, c, mods);
    }

    public static void onScreenClosing(Screen screen) {
        if (container(screen) && !(Minecraft.getInstance().screen instanceof RecipeScreen)) Overlay.onScreenClosed();
    }

    public static boolean suppressTooltip() {
        return Lens.suppressTooltip();
    }

    /** Сжать ли боковую колонку эффектов зелий, чтобы она не спорила с панелью списка. */
    public static boolean compactEffects(Screen screen) {
        return Overlay.active(screen);
    }

    // --- мир ---

    public static void renderHud(GuiGraphics g, float partialTick) {
        UiScale.render(g, scaled -> {
            works.repka.ryscope.runtime.Board.renderHud(scaled);
            Lens.renderHud(scaled, partialTick);
        });
    }

    public static boolean onWorldKey(int key, int scan) {
        return Lens.onWorldKey(key, scan);
    }
}
