package works.repka.ryscope.neoforge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.ryscope.runtime.JeiAsync;

/**
 * Часть методов JEI проверяет, что их вызвали из главного потока, и бросает исключение, если нет.
 * Плагины вызывают их при старте JEI (скрыть предмет, добавить рецепт), и при фоновой загрузке падают:
 * на сборке RepYS так ломались 14 плагинов из 150.
 *
 * Пока JEI загружается в нашем потоке, этот поток и есть единственный, кто работает с JEI: интерфейс JEI отключён,
 * а наш мост получает данные только после конца загрузки. Поэтому для потока загрузки проверка пропускается.
 * Для всех остальных потоков и после загрузки она работает как раньше.
 */
@Pseudo
@Mixin(targets = "mezz.jei.common.util.ErrorUtil", remap = false)
public abstract class JeiErrorUtilMixin {
    @Inject(method = "assertMainThread", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ryscope$allowLoaderThread(CallbackInfo ci) {
        if (JeiAsync.onLoaderThread()) ci.cancel();
    }
}
