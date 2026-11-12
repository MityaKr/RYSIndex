package works.repka.ryscope.neoforge.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.ryscope.client.Scissor;

/** Запоминает состояние обрезки, чтобы не спрашивать его у драйвера в каждом кадре (см. {@link Scissor}). */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerMixin {
    @Inject(method = "_enableScissorTest()V", at = @At("HEAD"), require = 0)
    private static void ryscope$enable(CallbackInfo ci) {
        Scissor.onEnable();
    }

    @Inject(method = "_disableScissorTest()V", at = @At("HEAD"), require = 0)
    private static void ryscope$disable(CallbackInfo ci) {
        Scissor.onDisable();
    }

    @Inject(method = "_scissorBox(IIII)V", at = @At("HEAD"), require = 0)
    private static void ryscope$box(int x, int y, int width, int height, CallbackInfo ci) {
        Scissor.onBox(x, y, width, height);
    }
}
