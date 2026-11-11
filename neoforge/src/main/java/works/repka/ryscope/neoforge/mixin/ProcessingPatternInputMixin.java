package works.repka.ryscope.neoforge.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.ryscope.neoforge.ae2.AlternativesHolder;

/** Вход шаблона обработки: с вариантами замещения он перечисляет и принимает любой из них. */
@Pseudo
@Mixin(targets = "appeng.crafting.pattern.AEProcessingPattern$Input")
public abstract class ProcessingPatternInputMixin implements AlternativesHolder {
    @Unique
    private GenericStack[] ryscope$options;

    @Override
    public void ryscope$setAlternatives(Object[] options) {
        GenericStack[] stacks = new GenericStack[options.length];
        for (int i = 0; i < options.length; i++) stacks[i] = (GenericStack) options[i];
        ryscope$options = stacks;
    }

    @Inject(method = "getPossibleInputs", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ryscope$possible(CallbackInfoReturnable<GenericStack[]> cir) {
        if (ryscope$options != null) cir.setReturnValue(ryscope$options);
    }

    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ryscope$valid(AEKey input, Level level, CallbackInfoReturnable<Boolean> cir) {
        if (ryscope$options == null) return;
        for (GenericStack option : ryscope$options) {
            if (input.matches(option)) {
                cir.setReturnValue(true);
                return;
            }
        }
        cir.setReturnValue(false);
    }
}
