package works.repka.rysindex.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import org.lwjgl.glfw.GLFW;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.client.Keys;
import works.repka.rysindex.client.RysIndexClient;
import works.repka.rysindex.platform.Platform;

/** Клиентская часть: переводит события NeoForge в вызовы общего кода. */
@Mod(value = RYSIndex.MOD_ID, dist = Dist.CLIENT)
public final class RysIndexNeoForgeClient {
    public RysIndexNeoForgeClient(IEventBus modBus) {
        // до первого чтения настроек и до того, как игра перечитает клавиши модов из options.txt
        RysIndexNeoForge.migrateLegacy(true);
        Platform.Holder.set(new NeoPlatform());
        RysIndexClient.init();

        modBus.addListener(this::registerKeys);
        modBus.addListener(this::registerLayers);
        modBus.addListener(this::registerReload);
        modBus.addListener(this::registerShaders);

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((RecipesUpdatedEvent e) -> RysIndexClient.onRecipesUpdated());
        bus.addListener((TagsUpdatedEvent e) -> {
            if (e.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) RysIndexClient.onTagsUpdated();
        });
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> RysIndexClient.onLogout());
        bus.addListener((ClientTickEvent.Post e) -> RysIndexClient.onClientTick());
        bus.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Post e) -> works.repka.rysindex.client.FreezeWatch.frame());

        bus.addListener((ContainerScreenEvent.Render.Background e) ->
            RysIndexClient.renderContainerBackground(e.getContainerScreen(), e.getGuiGraphics(), e.getMouseX(), e.getMouseY(),
                Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)));
        bus.addListener((ScreenEvent.Render.Post e) ->
            RysIndexClient.renderScreenPost(e.getScreen(), e.getGuiGraphics(), e.getMouseX(), e.getMouseY(), e.getPartialTick()));
        bus.addListener((ScreenEvent.MouseButtonPressed.Pre e) -> {
            if (RysIndexClient.mouseClicked(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getButton())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.MouseButtonReleased.Pre e) -> {
            if (RysIndexClient.mouseReleased(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getButton())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.MouseDragged.Pre e) -> {
            if (RysIndexClient.mouseDragged(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getMouseButton())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.MouseScrolled.Pre e) -> {
            if (RysIndexClient.mouseScrolled(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getScrollDeltaY())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.KeyPressed.Pre e) -> {
            if (RysIndexClient.keyPressed(e.getScreen(), e.getKeyCode(), e.getScanCode(), e.getModifiers())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.CharacterTyped.Pre e) -> {
            if (RysIndexClient.charTyped(e.getScreen(), e.getCodePoint(), e.getModifiers())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.Closing e) -> RysIndexClient.onScreenClosing(e.getScreen()));
        // виджет FTB убирает кор (works.repka.ryscore.dock.Dock): RYSIndex хозяин панели семьи, см. RysIndexClient.init
        bus.addListener((ScreenEvent.RenderInventoryMobEffects e) -> {
            if (RysIndexClient.compactEffects(e.getScreen())) e.setCompact(true);
        });
        bus.addListener((RenderTooltipEvent.Pre e) -> {
            if (RysIndexClient.suppressTooltip() && !RysIndexTooltipGuard.ours) e.setCanceled(true);
        });
        bus.addListener((InputEvent.Key e) -> {
            if (e.getAction() == GLFW.GLFW_PRESS && Minecraft.getInstance().screen == null) {
                RysIndexClient.onWorldKey(e.getKey(), e.getScanCode());
            }
        });

        // мод в подсказках наших панелей, как в списке JEI; Jade его уже дописал, тогда второй раз не пишем
        bus.addListener(net.neoforged.bus.api.EventPriority.LOWEST, (net.neoforged.neoforge.event.entity.player.ItemTooltipEvent e) -> {
            if (!works.repka.rysindex.model.ItemRes.ourTooltip || !works.repka.rysindex.runtime.Config.get().showModName || e.getItemStack().isEmpty()) return;
            String mod = works.repka.rysindex.platform.Platform.get().modName(e.getItemStack().getItem().getCreatorModId(e.getItemStack()));
            var lines = e.getToolTip();
            if (!lines.isEmpty() && lines.get(lines.size() - 1).getString().equals(mod)) return;
            lines.add(net.minecraft.network.chat.Component.literal(mod).withStyle(net.minecraft.ChatFormatting.BLUE, net.minecraft.ChatFormatting.ITALIC));
        });
        // выдача в режиме читов идёт через сервер, если там тоже стоит RYSIndex
        works.repka.rysindex.runtime.Give.serverSupports = () -> {
            var connection = Minecraft.getInstance().getConnection();
            return connection != null && connection.hasChannel(works.repka.rysindex.neoforge.net.GivePayload.TYPE);
        };
        works.repka.rysindex.runtime.Give.sender = (stack, mode) -> {
            var connection = Minecraft.getInstance().getConnection();
            if (connection == null || !connection.hasChannel(works.repka.rysindex.neoforge.net.GivePayload.TYPE)) return false;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new works.repka.rysindex.neoforge.net.GivePayload(stack, mode.ordinal()));
            return true;
        };

        if (net.neoforged.fml.ModList.get().isLoaded("ae2")) {
            works.repka.rysindex.neoforge.ae2.Ae2ClientHooks.register(bus);
        }

        if (System.getProperty("rysindex.autotest") != null) {
            AutoTest.install(bus);
        }
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        Keys.RECIPES = gui("recipes", KeyModifier.NONE, GLFW.GLFW_KEY_R);
        Keys.USES = gui("uses", KeyModifier.NONE, GLFW.GLFW_KEY_U);
        Keys.BOOKMARK = gui("bookmark", KeyModifier.NONE, GLFW.GLFW_KEY_A);
        Keys.SEARCH = gui("search", KeyModifier.CONTROL, GLFW.GLFW_KEY_F);
        Keys.THEME = gui("theme", KeyModifier.CONTROL, GLFW.GLFW_KEY_T);
        Keys.TOGGLE = gui("toggle", KeyModifier.CONTROL, GLFW.GLFW_KEY_O);
        Keys.BACK = gui("back", KeyModifier.NONE, GLFW.GLFW_KEY_BACKSPACE);
        Keys.TREE = gui("tree", KeyModifier.NONE, GLFW.GLFW_KEY_UNKNOWN); // G занята книжками AE2 (guideme): по умолчанию без клавиши
        Keys.HIDE = gui("hide", KeyModifier.CONTROL, GLFW.GLFW_KEY_DELETE);
        Keys.HIDE_QUERY = gui("hide_query", KeyModifier.CONTROL, GLFW.GLFW_KEY_H);
        Keys.SAVE_SEARCH = gui("save_search", KeyModifier.CONTROL, GLFW.GLFW_KEY_S);
        Keys.COPY_ID = gui("copy_id", KeyModifier.CONTROL, GLFW.GLFW_KEY_C);
        Keys.NEXT_TAB = gui("next_tab", KeyModifier.CONTROL, GLFW.GLFW_KEY_TAB);
        Keys.CLOSE_TAB = gui("close_tab", KeyModifier.CONTROL, GLFW.GLFW_KEY_W);
        Keys.PREV_PAGE = gui("prev_page", KeyModifier.NONE, GLFW.GLFW_KEY_PAGE_UP);
        Keys.NEXT_PAGE = gui("next_page", KeyModifier.NONE, GLFW.GLFW_KEY_PAGE_DOWN);
        Keys.PREV_CATEGORY = gui("prev_category", KeyModifier.SHIFT, GLFW.GLFW_KEY_PAGE_UP);
        Keys.NEXT_CATEGORY = gui("next_category", KeyModifier.SHIFT, GLFW.GLFW_KEY_PAGE_DOWN);
        Keys.FORWARD = gui("forward", KeyModifier.NONE, GLFW.GLFW_KEY_UNKNOWN);
        Keys.SHARE = gui("share", KeyModifier.NONE, GLFW.GLFW_KEY_UNKNOWN);
        Keys.CRAFT = gui("craft", KeyModifier.NONE, GLFW.GLFW_KEY_C);
        Keys.SHOT = gui("shot", KeyModifier.CONTROL, GLFW.GLFW_KEY_P);
        Keys.LENS = new KeyMapping("key.rysindex.lens", KeyConflictContext.UNIVERSAL, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, Keys.CATEGORY);
        for (KeyMapping k : new KeyMapping[] {Keys.RECIPES, Keys.USES, Keys.BOOKMARK, Keys.LENS, Keys.SEARCH, Keys.THEME, Keys.TOGGLE, Keys.BACK, Keys.TREE,
            Keys.HIDE, Keys.HIDE_QUERY, Keys.SAVE_SEARCH, Keys.COPY_ID, Keys.NEXT_TAB, Keys.CLOSE_TAB, Keys.PREV_PAGE, Keys.NEXT_PAGE,
            Keys.PREV_CATEGORY, Keys.NEXT_CATEGORY, Keys.FORWARD, Keys.SHARE, Keys.CRAFT, Keys.SHOT}) {
            event.register(k);
        }
        Keys.matcher = (mapping, keyCode, scanCode) -> mapping.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode));
        Keys.keyMatcher = KeyMapping::isActiveAndMatches;
    }

    private static KeyMapping gui(String name, KeyModifier modifier, int key) {
        return new KeyMapping("key.rysindex." + name, KeyConflictContext.GUI, modifier, InputConstants.Type.KEYSYM, key, Keys.CATEGORY);
    }

    private void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "lens"),
            (graphics, delta) -> RysIndexClient.renderHud(graphics, delta.getGameTimeDeltaPartialTick(false)));
    }

    private void registerShaders(net.neoforged.neoforge.client.event.RegisterShadersEvent event) {
        try {
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "gui_remap"),
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX), works.repka.rysindex.ui.RecipeRestyle::setPlain);
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RYSIndex.MOD_ID, "gui_remap_color"),
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR), works.repka.rysindex.ui.RecipeRestyle::setColored);
        } catch (java.io.IOException e) {
            RYSIndex.LOG.warn("Шейдер перекраски не загружен, окна рецептов останутся серыми: {}", e.toString());
        }
    }

    private void registerReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) RysIndexClient::onResourceReload);
    }

    /** Наши собственные подсказки не должны отменяться вместе с подсказками игры, когда активна линза. */
    public static final class RysIndexTooltipGuard {
        public static boolean ours;

        private RysIndexTooltipGuard() {}
    }

    static Screen screen() {
        return Minecraft.getInstance().screen;
    }
}
