package works.repka.ryscope.neoforge.mixin;

import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import works.repka.ryscope.ui.RecipeRestyle;

/**
 * Перекраска окон рецептов модов: только на время рисования рецепта (см. {@link RecipeRestyle}),
 * в остальное время всё работает как обычно. Если в другой версии игры места изменятся, окна просто останутся серыми.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
    @ModifyArg(method = "innerBlit(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFF)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V"), require = 0)
    private Supplier<ShaderInstance> ryscope$plain(Supplier<ShaderInstance> shader) {
        return RecipeRestyle.shader(shader, false);
    }

    @ModifyArg(method = "innerBlit(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFFFFFF)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V"), require = 0)
    private Supplier<ShaderInstance> ryscope$colored(Supplier<ShaderInstance> shader) {
        return RecipeRestyle.shader(shader, true);
    }

    @ModifyVariable(method = "fill(Lnet/minecraft/client/renderer/RenderType;IIIIII)V", at = @At("HEAD"), argsOnly = true, ordinal = 5, require = 0)
    private int ryscope$fillColor(int color) {
        return RecipeRestyle.fill(color);
    }

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;FFIZ)I", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private int ryscope$textColor(int color) {
        return RecipeRestyle.text(color);
    }

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;FFIZ)I", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private int ryscope$sequenceColor(int color) {
        return RecipeRestyle.text(color);
    }
}
