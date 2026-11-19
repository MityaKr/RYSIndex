package works.repka.rysindex.neoforge.mixin;

import java.util.List;
import java.util.function.Supplier;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import works.repka.rysindex.ui.RecipeRestyle;
import works.repka.rysindex.ui.TooltipDefer;

/**
 * Перекраска окон рецептов модов: только на время рисования рецепта (см. {@link RecipeRestyle}),
 * в остальное время всё работает как обычно. Если в другой версии игры места изменятся, окна просто останутся серыми.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
    @Shadow
    @Final
    private PoseStack pose;

    @Shadow
    private ItemStack tooltipStack;

    @Shadow
    private void renderTooltipInternal(Font font, List<ClientTooltipComponent> components, int mouseX, int mouseY, ClientTooltipPositioner positioner) {
    }

    /** Подсказка, нарисованная модом внутри рецепта, откладывается до подсказок окна (см. {@link TooltipDefer}). */
    @Inject(method = "renderTooltipInternal", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysindex$deferTooltip(Font font, List<ClientTooltipComponent> components, int mouseX, int mouseY, ClientTooltipPositioner positioner, CallbackInfo ci) {
        if (!TooltipDefer.capturing() || components.isEmpty()) return;
        // место в координатах кадра; при показе переводится обратно под тот сдвиг и масштаб, что будут тогда
        Vector3f at = pose.last().pose().transformPosition(mouseX, mouseY, 0, new Vector3f());
        ItemStack stack = tooltipStack;
        List<ClientTooltipComponent> copy = List.copyOf(components);
        TooltipDefer.add(() -> {
            Vector3f local = new Matrix4f(pose.last().pose()).invert().transformPosition(at.x, at.y, 0, new Vector3f());
            ItemStack before = tooltipStack;
            tooltipStack = stack;
            try {
                renderTooltipInternal(font, copy, Math.round(local.x), Math.round(local.y), positioner);
            } finally {
                tooltipStack = before;
            }
        });
        ci.cancel();
    }

    @ModifyArg(method = "innerBlit(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFF)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V"), require = 0)
    private Supplier<ShaderInstance> rysindex$plain(Supplier<ShaderInstance> shader) {
        return RecipeRestyle.shader(shader, false);
    }

    @ModifyArg(method = "innerBlit(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFFFFFF)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V"), require = 0)
    private Supplier<ShaderInstance> rysindex$colored(Supplier<ShaderInstance> shader) {
        return RecipeRestyle.shader(shader, true);
    }

    @ModifyVariable(method = "fill(Lnet/minecraft/client/renderer/RenderType;IIIIII)V", at = @At("HEAD"), argsOnly = true, ordinal = 5, require = 0)
    private int rysindex$fillColor(int color) {
        return RecipeRestyle.fill(color);
    }

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;FFIZ)I", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private int rysindex$textColor(int color) {
        return RecipeRestyle.text(color);
    }

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;FFIZ)I", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private int rysindex$sequenceColor(int color) {
        return RecipeRestyle.text(color);
    }
}
