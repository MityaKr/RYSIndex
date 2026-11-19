package works.repka.rysindex.neoforge.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysindex.neoforge.ae2.AlternativesHolder;

/** Вход шаблона обработки: с вариантами замещения он перечисляет и принимает любой из них. */
@Pseudo
@Mixin(targets = "appeng.crafting.pattern.AEProcessingPattern$Input")
public abstract class ProcessingPatternInputMixin implements AlternativesHolder {
    @Unique
    private GenericStack[] rysindex$options;

    @Override
    public void rysindex$setAlternatives(Object[] options) {
        GenericStack[] stacks = new GenericStack[options.length];
        for (int i = 0; i < options.length; i++) stacks[i] = (GenericStack) options[i];
        rysindex$options = stacks;
    }

    @Inject(method = "getPossibleInputs", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void rysindex$possible(CallbackInfoReturnable<GenericStack[]> cir) {
        if (rysindex$options != null) cir.setReturnValue(rysindex$options);
    }

    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void rysindex$valid(AEKey input, Level level, CallbackInfoReturnable<Boolean> cir) {
        if (rysindex$options == null) return;
        for (GenericStack option : rysindex$options) {
            if (input.matches(option)) {
                cir.setReturnValue(true);
                return;
            }
        }
        cir.setReturnValue(false);
    }
}
