package works.repka.ryscope.neoforge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;
import works.repka.ryscope.platform.Platform;

public final class NeoPlatform implements Platform {
    private final Map<String, String> names = new ConcurrentHashMap<>();

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public String modName(String namespace) {
        return names.computeIfAbsent(namespace, ns -> ModList.get().getModContainerById(ns)
            .map(c -> c.getModInfo().getDisplayName())
            .orElse(ns.equals("minecraft") ? "Minecraft" : ns));
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public int[] screenBounds(AbstractContainerScreen<?> screen) {
        return new int[] {screen.getGuiLeft(), screen.getGuiTop(), screen.getXSize(), screen.getYSize()};
    }

    @Override
    public Slot hoveredSlot(AbstractContainerScreen<?> screen) {
        return screen.getSlotUnderMouse();
    }

    @Override
    public List<String> modIds() {
        List<String> out = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) out.add(info.getModId());
        return out;
    }

    @Override
    public boolean isDevEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public java.util.List<String> mixinsOn(String className) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            var info = org.spongepowered.asm.mixin.transformer.ClassInfo.forName(className.replace('.', '/'));
            if (info != null) {
                for (var mixin : info.getAppliedMixins()) {
                    String config = mixin.getConfig().getName();
                    if (!out.contains(config)) out.add(config);
                }
            }
        } catch (Throwable t) {
            // диагностика не должна ничего ломать
        }
        return out;
    }

    @Override
    public void renderFluid(net.minecraft.client.gui.GuiGraphics g, net.minecraft.world.level.material.Fluid fluid, int x, int y) {
        var ext = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid);
        net.minecraft.resources.ResourceLocation still = ext.getStillTexture();
        if (still == null) return;
        var sprite = net.minecraft.client.Minecraft.getInstance()
            .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(still);
        int tint = ext.getTintColor();
        float a = (tint >>> 24) / 255f;
        if (a <= 0f) a = 1f;
        g.blit(x, y, 0, 16, 16, sprite, (tint >> 16 & 255) / 255f, (tint >> 8 & 255) / 255f, (tint & 255) / 255f, a);
    }

    @Override
    public net.minecraft.network.chat.Component fluidName(net.minecraft.world.level.material.Fluid fluid) {
        return fluid.getFluidType().getDescription();
    }

    @Override
    public boolean plainFluid(Object fluidStack) {
        return fluidStack instanceof net.neoforged.neoforge.fluids.FluidStack stack && stack.getComponentsPatch().isEmpty();
    }

    @Override
    public long fluidAmount(Object fluidStack) {
        return fluidStack instanceof net.neoforged.neoforge.fluids.FluidStack stack && stack.getAmount() > 0 ? stack.getAmount() : 1000;
    }

    @Override
    public void renderTooltip(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.gui.Font font,
                              List<net.minecraft.network.chat.Component> lines,
                              java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image,
                              net.minecraft.world.item.ItemStack stack, int x, int y) {
        g.renderTooltip(font, lines, image, stack, x, y);
    }
}
