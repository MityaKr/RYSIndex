package works.repka.ryscope.ui;

import works.repka.ryscore.ui.Draw;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import com.mojang.blaze3d.systems.RenderSystem;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.platform.Platform;
import works.repka.ryscope.runtime.Config;
import works.repka.ryscore.theme.Theme;

/**
 * Панель кнопок FTB в стиле мода: тот же реестр кнопок FTB Library, те же действия, значки и подсказки,
 * но нарисовано нами и встроено в левую колонку. Зависимости от FTB нет: всё через отражение,
 * а если FTB Library нет или её устройство изменилось, остаётся её собственная панель.
 */
public final class Dock {
    private static final int BUTTON = 18;
    private static final int GAP = 2;
    private static final int PAD = 5;
    private static final int ICON = 12;
    private static final int ICON_TEXTURE = 42;
    private static final long REFRESH_MS = 500;

    /** Порядок на панели: сначала прогресс и карта, потом частые действия, настройки последними. */
    private static final List<String> ORDER = List.of(
        "rysquests_book", "rysteams_team", "ftbquests_quests", "ftbteams_my_team", "ftbteams_team_lives", "ftbchunks_chunks", "ftbchunks_claim_chunks",
        "ftbessentials_trash_can", "ftblibrary_toggle_day", "ftblibrary_toggle_night", "ftblibrary_toggle_rain",
        "ftblibrary_toggle_gamemode", "ftbultimine_config", "ftbbackups3_config", "ftblibrary_config");
    /** Кнопки, для которых нарисованы свои значки; у остальных остаётся значок автора. */
    private static final Set<String> OWN_ICONS = Set.copyOf(ORDER);

    /** Цвет плитки у каждой кнопки свой: так кнопки различаются с первого взгляда. */
    private static final Map<String, Integer> TILES = Map.ofEntries(
        Map.entry("rysquests_book", 0xFFE9A83A), Map.entry("rysteams_team", 0xFF4C8DF5),
        Map.entry("ftbquests_quests", 0xFFE9A83A), Map.entry("ftbteams_my_team", 0xFF4C8DF5), Map.entry("ftbteams_team_lives", 0xFFE5484D),
        Map.entry("ftbchunks_chunks", 0xFF3FB56B), Map.entry("ftbchunks_claim_chunks", 0xFF159E94), Map.entry("ftbessentials_trash_can", 0xFF8A93A6),
        Map.entry("ftblibrary_toggle_day", 0xFFF5C542), Map.entry("ftblibrary_toggle_night", 0xFF5B5BD6), Map.entry("ftblibrary_toggle_rain", 0xFF38A3E0),
        Map.entry("ftblibrary_toggle_gamemode", 0xFFD9622B), Map.entry("ftbultimine_config", 0xFFA9753F), Map.entry("ftbbackups3_config", 0xFF8E5BD0),
        Map.entry("ftblibrary_config", 0xFF6B7488));

    private record Entry(Object button, String key, ResourceLocation texture, Object icon) {}

    private static List<Entry> entries = List.of();
    private static long refreshedAt;
    private static boolean visible;
    private static boolean broken;
    private static int x, y, w, h, cols, gridX, gridY;

    private Dock() {}

    /** Отражение в FTB Library. Отдельный класс: без FTB он не загружается вовсе. */
    private static final class Ftb {
        static final boolean OK;
        static Object manager;
        static Class<?> widget;
        static Field lastArea;
        static Method visible, enabled, sidebarButton, id, data, icon, tooltip, click, overlays, drawIcon, renderOverlay;

        static {
            boolean ok = false;
            try {
                if (Platform.get().isModLoaded("ftblibrary")) {
                    Class<?> managerClass = Class.forName("dev.ftb.mods.ftblibrary.sidebar.SidebarButtonManager");
                    manager = managerClass.getField("INSTANCE").get(null);
                    enabled = managerClass.getMethod("getEnabledButtonList", boolean.class);
                    visible = Class.forName("dev.ftb.mods.ftblibrary.FTBLibraryClient").getMethod("areButtonsVisible", Screen.class);
                    widget = Class.forName("dev.ftb.mods.ftblibrary.sidebar.SidebarGroupGuiButton");
                    lastArea = widget.getField("lastDrawnArea");
                    sidebarButton = Class.forName("dev.ftb.mods.ftblibrary.sidebar.SidebarGuiButton").getMethod("getSidebarButton");
                    Class<?> registered = Class.forName("dev.ftb.mods.ftblibrary.sidebar.RegisteredSidebarButton");
                    id = registered.getMethod("getId");
                    data = registered.getMethod("getData");
                    tooltip = registered.getMethod("getTooltip", boolean.class);
                    click = registered.getMethod("clickButton", boolean.class);
                    overlays = registered.getMethod("getExtraRenderers");
                    icon = Class.forName("dev.ftb.mods.ftblibrary.sidebar.SidebarButtonData").getMethod("icon");
                    drawIcon = Class.forName("dev.ftb.mods.ftblibrary.icon.Icon")
                        .getMethod("draw", GuiGraphics.class, int.class, int.class, int.class, int.class);
                    renderOverlay = Class.forName("dev.ftb.mods.ftblibrary.api.sidebar.ButtonOverlayRender")
                        .getMethod("render", GuiGraphics.class, net.minecraft.client.gui.Font.class, int.class);
                    ok = true;
                }
            } catch (Throwable t) {
                RYScope.LOG.warn("Кнопки FTB Library недоступны, остаётся её собственная панель: {}", t.toString());
            }
            OK = ok;
        }
    }

    /** Установлена ли FTB Library такой версии, с которой панель умеет работать. */
    public static boolean available() {
        return !broken && Ftb.OK;
    }

    /** Заменяет ли наша панель кнопки FTB на этом экране. */
    public static boolean replaces(Screen screen) {
        if (!Config.get().replaceFtbSidebar || !Config.get().overlayEnabled) return false;
        if (!(screen instanceof AbstractContainerScreen<?>) || !available()) return false;
        try {
            return (boolean) Ftb.visible.invoke(null, screen);
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    public static boolean isFtbWidget(Object widget) {
        return available() && Ftb.widget.isInstance(widget);
    }

    /**
     * Виджет FTB убран с экрана. FTB продолжает сообщать JEI последнюю занятую им область,
     * а панели обходят чужие области, поэтому её нужно обнулить.
     */
    public static void widgetRemoved() {
        try {
            Ftb.lastArea.set(null, new Rect2i(0, 0, 0, 0));
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Область, которую FTB сообщает JEI для своей панели: когда панель наша, обходить её незачем. */
    public static boolean isFtbArea(Screen screen, int[] area) {
        if (!replaces(screen)) return false;
        try {
            Rect2i own = (Rect2i) Ftb.lastArea.get(null);
            return own != null && own.getX() == area[0] && own.getY() == area[1] && own.getWidth() == area[2] && own.getHeight() == area[3];
        } catch (Throwable t) {
            return false;
        }
    }

    private static void fail(Throwable t) {
        broken = true;
        visible = false;
        RYScope.LOG.warn("Панель кнопок FTB отключена: {}", t.toString());
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - refreshedAt < REFRESH_MS && !entries.isEmpty()) return;
        refreshedAt = now;
        try {
            List<Entry> out = new ArrayList<>();
            for (Object guiButton : (List<?>) Ftb.enabled.invoke(Ftb.manager, false)) {
                Object button = Ftb.sidebarButton.invoke(guiButton);
                ResourceLocation id = (ResourceLocation) Ftb.id.invoke(button);
                String key = id.getNamespace() + "_" + id.getPath().replace('/', '_');
                if (replacedByOurs(key)) continue;
                ResourceLocation texture = OWN_ICONS.contains(key)
                    ? ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "textures/gui/dock/" + key + ".png") : null;
                out.add(new Entry(button, key, texture, Ftb.icon.invoke(Ftb.data.invoke(button))));
            }
            // сортировка устойчивая: кнопки других модов идут перед настройками в порядке FTB
            out.sort(Comparator.comparingInt(e -> rank(e.key())));
            entries = out;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Кнопки FTB, которые заменены нашими модами: при установленном моде семьи кнопка FTB не показывается. */
    private static boolean replacedByOurs(String key) {
        return (key.equals("ftbquests_quests") && Platform.get().isModLoaded("rysquests"))
            || (key.equals("ftbteams_my_team") && Platform.get().isModLoaded("rysteams"));
    }

    private static int rank(String key) {
        int index = ORDER.indexOf(key);
        if (index >= 0) return index;
        return ORDER.indexOf("ftblibrary_toggle_day");
    }

    /**
     * Раскладка в верхней части левой колонки, закладки и недавние идут под ней.
     * Если места нет (например, открыта книга рецептов на узком экране), панель скрывается, как и остальные наши панели.
     *
     * @return верхняя граница, оставшаяся для других панелей
     */
    public static int layout(Screen screen, int left, int width, int top, int bottom) {
        visible = false;
        if (!replaces(screen)) return top;
        refresh();
        if (!available() || entries.isEmpty()) return top;
        int min = BUTTON + PAD * 2;
        if (width < min) return top;
        cols = Math.max(1, (width - PAD * 2 + GAP) / (BUTTON + GAP));
        int rows = (entries.size() + cols - 1) / cols;
        h = rows * BUTTON + (rows - 1) * GAP + PAD * 2;
        if (h > bottom - top) return top;
        x = left;
        w = width;
        y = top;
        gridX = x + (w - (cols * BUTTON + (cols - 1) * GAP)) / 2;
        gridY = y + PAD;
        visible = true;
        return y + h + 4;
    }

    private static int buttonX(int index) {
        return gridX + (index % cols) * (BUTTON + GAP);
    }

    private static int buttonY(int index) {
        return gridY + (index / cols) * (BUTTON + GAP);
    }

    public static boolean contains(double mx, double my) {
        return visible && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int indexAt(double mx, double my) {
        if (!contains(mx, my)) return -1;
        for (int i = 0; i < entries.size(); i++) {
            int bx = buttonX(i), by = buttonY(i);
            if (mx >= bx && mx < bx + BUTTON && my >= by && my < by + BUTTON) return i;
        }
        return -1;
    }

    public static void render(GuiGraphics g, Theme theme, int mx, int my) {
        if (!visible) return;
        theme.panel(g, x, y, w, h);
        int hover = indexAt(mx, my);
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int bx = buttonX(i), by = buttonY(i);
            boolean hot = i == hover;
            try {
                if (entry.texture() != null) {
                    int tile = TILES.getOrDefault(entry.key(), 0xFF6B7488);
                    float radius = theme.beveled() ? 1f : 5f;
                    Draw.round(g, bx, by, BUTTON, BUTTON, radius, hot ? Draw.mix(tile, 0xFFFFFFFF, 0.22f) : tile);
                    drawTinted(g, entry.texture(), bx + (BUTTON - ICON) / 2, by + (BUTTON - ICON) / 2, glyphOn(tile));
                } else {
                    // кнопка чужого мода: значок автора на нейтральной плитке
                    Draw.round(g, bx, by, BUTTON, BUTTON, theme.beveled() ? 1f : 5f, hot ? theme.c("slot.hover") : theme.c("slot.bg"));
                    RenderSystem.enableBlend();
                    Ftb.drawIcon.invoke(entry.icon(), g, bx + 1, by + 1, 16, 16);
                }
                // значки поверх кнопки: например, счётчик непрочитанных квестов
                Collection<?> overlays = (Collection<?>) Ftb.overlays.invoke(entry.button());
                if (!overlays.isEmpty()) {
                    g.pose().pushPose();
                    g.pose().translate(bx + 1, by + 1, 10);
                    for (Object overlay : overlays) Ftb.renderOverlay.invoke(overlay, g, mc.font, 16);
                    g.pose().popPose();
                }
            } catch (Throwable t) {
                fail(t);
                return;
            }
        }
    }

    /** На светлых плитках значок тёмный, на тёмных белый. */
    private static int glyphOn(int tile) {
        double luminance = (0.299 * ((tile >> 16) & 255) + 0.587 * ((tile >> 8) & 255) + 0.114 * (tile & 255)) / 255;
        return luminance > 0.62 ? 0xFF1C1F27 : 0xFFFFFFFF;
    }

    private static void drawTinted(GuiGraphics g, ResourceLocation texture, int ix, int iy, int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f, 1f);
        g.blit(texture, ix, iy, ICON, ICON, 0f, 0f, ICON_TEXTURE, ICON_TEXTURE, ICON_TEXTURE, ICON_TEXTURE);
        g.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    @SuppressWarnings("unchecked")
    public static void renderTooltip(GuiGraphics g, int mx, int my) {
        int hover = indexAt(mx, my);
        if (hover < 0) return;
        try {
            List<Component> lines = (List<Component>) Ftb.tooltip.invoke(entries.get(hover).button(), Screen.hasShiftDown());
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.renderTooltip(Minecraft.getInstance().font, lines, Optional.empty(), mx, my);
            g.pose().popPose();
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** @return true, если клик пришёлся по кнопке и она сработала. */
    public static boolean click(double mx, double my, int button) {
        int index = indexAt(mx, my);
        if (index < 0 || button != 0) return false;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
        try {
            Ftb.click.invoke(entries.get(index).button(), Screen.hasShiftDown());
        } catch (Throwable t) {
            RYScope.LOG.warn("Кнопка FTB {} не сработала: {}", entries.get(index).key(), t.toString());
        }
        return true;
    }

    // --- для автотеста ---

    public static boolean visible() {
        return visible;
    }

    public static List<String> keys() {
        return entries.stream().map(Entry::key).toList();
    }

    /** Центр кнопки или null, если её нет на панели. */
    public static int[] center(String key) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key().equals(key)) return new int[] {buttonX(i) + BUTTON / 2, buttonY(i) + BUTTON / 2};
        }
        return null;
    }
}
