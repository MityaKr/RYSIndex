package works.repka.rysindex.neoforge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import works.repka.rysindex.runtime.JeiAsync;

/**
 * Единственное вмешательство во внутренности JEI: наблюдатель событий получает запуск и остановку JEI
 * как два Runnable, и мы оборачиваем их, чтобы запуск шёл в фоновом потоке.
 * Если в другой версии JEI этот класс устроен иначе, вмешательство просто не применяется (require = 0)
 * и JEI запускается как обычно.
 */
@Pseudo
@Mixin(targets = "mezz.jei.neoforge.startup.StartEventObserver", remap = false)
public abstract class StartEventObserverMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false, require = 0)
    private static Runnable rysindex$wrapStart(Runnable start) {
        return JeiAsync.wrapStart(start);
    }

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 1, remap = false, require = 0)
    private static Runnable rysindex$wrapStop(Runnable stop) {
        return JeiAsync.wrapStop(stop);
    }
}
