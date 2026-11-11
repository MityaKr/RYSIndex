package works.repka.ryscope.neoforge;

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
import works.repka.ryscope.RYScope;
import works.repka.ryscope.client.Keys;
import works.repka.ryscope.client.RyscopeClient;
import works.repka.ryscope.platform.Platform;

/** Клиентская часть: переводит события NeoForge в вызовы общего кода. */
@Mod(value = RYScope.MOD_ID, dist = Dist.CLIENT)
public final class RyscopeNeoForgeClient {
    public RyscopeNeoForgeClient(IEventBus modBus) {
        Platform.Holder.set(new NeoPlatform());
        RyscopeClient.init();

        modBus.addListener(this::registerKeys);
        modBus.addListener(this::registerLayers);
        modBus.addListener(this::registerReload);
        modBus.addListener(this::registerShaders);

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((RecipesUpdatedEvent e) -> RyscopeClient.onRecipesUpdated());
        bus.addListener((TagsUpdatedEvent e) -> {
            if (e.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) RyscopeClient.onTagsUpdated();
        });
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> RyscopeClient.onLogout());
        bus.addListener((ClientTickEvent.Post e) -> RyscopeClient.onClientTick());

        bus.addListener((ContainerScreenEvent.Render.Background e) ->
            RyscopeClient.renderContainerBackground(e.getContainerScreen(), e.getGuiGraphics(), e.getMouseX(), e.getMouseY(),
                Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)));
        bus.addListener((ScreenEvent.Render.Post e) ->
            RyscopeClient.renderScreenPost(e.getScreen(), e.getGuiGraphics(), e.getMouseX(), e.getMouseY(), e.getPartialTick()));
        bus.addListener((ScreenEvent.MouseButtonPressed.Pre e) -> {
            if (RyscopeClient.mouseClicked(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getButton())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.MouseButtonReleased.Pre e) -> {
            if (RyscopeClient.mouseReleased(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getButton())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.MouseDragged.Pre e) -> {
            if (RyscopeClient.mouseDragged(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getMouseButton())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.MouseScrolled.Pre e) -> {
            if (RyscopeClient.mouseScrolled(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getScrollDeltaY())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.KeyPressed.Pre e) -> {
            if (RyscopeClient.keyPressed(e.getScreen(), e.getKeyCode(), e.getScanCode(), e.getModifiers())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.CharacterTyped.Pre e) -> {
            if (RyscopeClient.charTyped(e.getScreen(), e.getCodePoint(), e.getModifiers())) e.setCanceled(true);
        });
        bus.addListener((ScreenEvent.Closing e) -> RyscopeClient.onScreenClosing(e.getScreen()));
        // самый низкий приоритет: виджет FTB добавляется в этом же событии и должен уже быть на экране
        bus.addListener(net.neoforged.bus.api.EventPriority.LOWEST, (ScreenEvent.Init.Post e) -> {
            if (!works.repka.ryscope.ui.Dock.replaces(e.getScreen())) return;
            for (var listener : java.util.List.copyOf(e.getListenersList())) {
                if (works.repka.ryscope.ui.Dock.isFtbWidget(listener)) e.removeListener(listener);
            }
            works.repka.ryscope.ui.Dock.widgetRemoved();
        });
        bus.addListener((ScreenEvent.RenderInventoryMobEffects e) -> {
            if (RyscopeClient.compactEffects(e.getScreen())) e.setCompact(true);
        });
        bus.addListener((RenderTooltipEvent.Pre e) -> {
            if (RyscopeClient.suppressTooltip() && !RyscopeTooltipGuard.ours) e.setCanceled(true);
        });
        bus.addListener((InputEvent.Key e) -> {
            if (e.getAction() == GLFW.GLFW_PRESS && Minecraft.getInstance().screen == null) {
                RyscopeClient.onWorldKey(e.getKey(), e.getScanCode());
            }
        });

        if (net.neoforged.fml.ModList.get().isLoaded("ae2")) {
            works.repka.ryscope.neoforge.ae2.Ae2ClientHooks.register(bus);
        }

        if (System.getProperty("ryscope.autotest") != null) {
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
        Keys.TREE = gui("tree", KeyModifier.NONE, GLFW.GLFW_KEY_G);
        Keys.LENS = new KeyMapping("key.ryscope.lens", KeyConflictContext.UNIVERSAL, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, Keys.CATEGORY);
        for (KeyMapping k : new KeyMapping[] {Keys.RECIPES, Keys.USES, Keys.BOOKMARK, Keys.LENS, Keys.SEARCH, Keys.THEME, Keys.TOGGLE, Keys.BACK, Keys.TREE}) {
            event.register(k);
        }
        Keys.matcher = (mapping, keyCode, scanCode) -> mapping.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode));
    }

    private static KeyMapping gui(String name, KeyModifier modifier, int key) {
        return new KeyMapping("key.ryscope." + name, KeyConflictContext.GUI, modifier, InputConstants.Type.KEYSYM, key, Keys.CATEGORY);
    }

    private void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "lens"),
            (graphics, delta) -> RyscopeClient.renderHud(graphics, delta.getGameTimeDeltaPartialTick(false)));
    }

    private void registerShaders(net.neoforged.neoforge.client.event.RegisterShadersEvent event) {
        try {
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "gui_remap"),
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX), works.repka.ryscope.ui.RecipeRestyle::setPlain);
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RYScope.MOD_ID, "gui_remap_color"),
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR), works.repka.ryscope.ui.RecipeRestyle::setColored);
        } catch (java.io.IOException e) {
            RYScope.LOG.warn("Шейдер перекраски не загружен, окна рецептов останутся серыми: {}", e.toString());
        }
    }

    private void registerReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) RyscopeClient::onResourceReload);
    }

    /** Наши собственные подсказки не должны отменяться вместе с подсказками игры, когда активна линза. */
    public static final class RyscopeTooltipGuard {
        public static boolean ours;

        private RyscopeTooltipGuard() {}
    }

    static Screen screen() {
        return Minecraft.getInstance().screen;
    }
}
