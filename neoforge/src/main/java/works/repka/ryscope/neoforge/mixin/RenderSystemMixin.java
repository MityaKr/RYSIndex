package works.repka.ryscope.neoforge.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.function.Supplier;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import works.repka.ryscope.ui.RecipeRestyle;

/** Перекраска фонов, которые мод рисует сам, минуя {@code blit}. Работает только на время рисования рецепта. */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @ModifyVariable(method = "setShader(Ljava/util/function/Supplier;)V", at = @At("HEAD"), argsOnly = true, require = 0)
    private static Supplier<ShaderInstance> ryscope$direct(Supplier<ShaderInstance> shader) {
        return RecipeRestyle.direct(shader);
    }
}
