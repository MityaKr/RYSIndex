package works.repka.ryscope.neoforge.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.ryscope.runtime.Ae2Terminal;

/**
 * Клиентский список сети AE2 обновился пакетом сервера: отметки рецептов и «+» пересчитываются сразу,
 * а не по таймеру, и содержимое сети не перечитывается, пока оно не менялось.
 */
@Pseudo
@Mixin(targets = "appeng.client.gui.me.common.Repo", remap = false)
public abstract class Ae2RepoMixin {
    @Inject(method = "handleUpdate(ZLjava/util/List;)V", at = @At("TAIL"), require = 0, remap = false)
    private void ryscope$updated(boolean fullUpdate, List<?> entries, CallbackInfo ci) {
        Ae2Terminal.onRepoUpdate();
    }
}
