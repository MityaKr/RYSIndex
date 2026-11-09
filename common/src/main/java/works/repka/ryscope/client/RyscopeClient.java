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

    private static boolean container(Screen screen) {
        return screen instanceof AbstractContainerScreen<?>;
    }

    public static void renderContainerBackground(Screen screen, GuiGraphics g, int mx, int my, float partialTick) {
        if (container(screen)) Overlay.renderBackground(screen, g, mx, my, partialTick);
    }

    /** Экран чужого мода: не инвентарь и не наш собственный (квесты, справочники). */
    private static boolean foreign(Screen screen) {
        return screen != null && !container(screen) && !screen.getClass().getName().startsWith("works.repka.ryscope.");
    }

    public static void renderScreenPost(Screen screen, GuiGraphics g, int mx, int my, float partialTick) {
        if (container(screen)) Overlay.renderTop(screen, g, mx, my, partialTick);
        else if (foreign(screen)) Overlay.renderForeign(screen, g, mx, my);
    }

    public static boolean mouseClicked(Screen screen, double mx, double my, int button) {
        return container(screen) && Overlay.mouseClicked(screen, mx, my, button);
    }

    public static boolean mouseReleased(Screen screen, double mx, double my, int button) {
        return container(screen) && Overlay.mouseReleased(screen, mx, my, button);
    }

    public static boolean mouseDragged(Screen screen, double mx, double my, int button) {
        return container(screen) && Overlay.mouseDragged(screen, mx, my, button);
    }

    public static boolean mouseScrolled(Screen screen, double mx, double my, double delta) {
        return container(screen) && Overlay.mouseScrolled(screen, mx, my, delta);
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
        works.repka.ryscope.runtime.Board.renderHud(g);
        Lens.renderHud(g, partialTick);
    }

    public static boolean onWorldKey(int key, int scan) {
        return Lens.onWorldKey(key, scan);
    }
}
