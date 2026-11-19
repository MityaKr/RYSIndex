package works.repka.rysindex.model;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import works.repka.rysindex.platform.Platform;

/** Жидкость. Количество в миллибакетах. */
public final class FluidRes extends Stack {
    public final Fluid fluid;

    public FluidRes(Fluid fluid, long amount) {
        this.fluid = fluid;
        this.amount = Math.max(1, amount);
    }

    @Override
    public Object key() {
        return fluid;
    }

    @Override
    public String type() {
        return "fluid";
    }

    @Override
    public ResourceLocation id() {
        return BuiltInRegistries.FLUID.getKey(fluid);
    }

    @Override
    public Component name() {
        return Platform.get().fluidName(fluid);
    }

    @Override
    public void render(GuiGraphics g, int x, int y) {
        Platform.get().renderFluid(g, fluid, x, y);
    }

    @Override
    public String formatAmount() {
        if (amount >= 1000 && amount % 1000 == 0) return (amount / 1000) + "B";
        return amount + "mB";
    }

    @Override
    public void renderOverlay(GuiGraphics g, int x, int y) {
        // количество жидкости длинное: рисуем мельче, чем у предметов
        String text = formatAmount();
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        var font = net.minecraft.client.Minecraft.getInstance().font;
        float w = font.width(text) * 0.6f;
        g.pose().translate(x + 17 - w, y + 11, 0);
        g.pose().scale(0.6f, 0.6f, 1f);
        g.drawString(font, text, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
        renderChance(g, x, y);
    }

    @Override
    public List<Component> tooltipLines() {
        List<Component> out = new ArrayList<>();
        out.add(name());
        out.add(Component.literal(amount + " mB").withStyle(ChatFormatting.GRAY));
        out.add(Component.literal(Platform.get().modName(namespace())).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
        return out;
    }

    @Override
    public List<String> tags() {
        List<String> out = new ArrayList<>();
        fluid.builtInRegistryHolder().tags().forEach(t -> out.add(t.location().toString()));
        return out;
    }

    @Override
    public boolean same(Stack other) {
        return other instanceof FluidRes o && o.fluid == fluid;
    }

    @Override
    public Stack copy() {
        FluidRes r = new FluidRes(fluid, amount);
        r.chance = chance;
        return r;
    }
}
