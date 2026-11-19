package works.repka.rysindex.neoforge.mixin;

import java.util.List;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * JEI держит недопустимые варианты ячейки (жидкость тега, которую он не показывает) пустыми местами списка и перебирает их
 * вместе с остальными: сосуд «Разделения жидкостей» Oritech каждую третью секунду стоял пустым. Перебор идёт только по
 * настоящим вариантам; если пустые все или ни одного, всё как в JEI.
 */
@Pseudo
@Mixin(targets = "mezz.jei.library.gui.ingredients.CycleTicker", remap = false)
public abstract class JeiCycleTickerMixin {
    @Shadow(remap = false)
    private int index;

    @Inject(method = "getCycled", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private <T> void rysindex$skipEmpty(List<T> list, CallbackInfoReturnable<Optional<T>> cir) {
        int shown = 0;
        for (T t : list) if (t != null) shown++;
        if (shown == 0 || shown == list.size()) return;
        int left = Math.floorMod(index, shown);
        for (T t : list) {
            if (t != null && left-- == 0) {
                cir.setReturnValue(Optional.of(t));
                return;
            }
        }
    }
}
