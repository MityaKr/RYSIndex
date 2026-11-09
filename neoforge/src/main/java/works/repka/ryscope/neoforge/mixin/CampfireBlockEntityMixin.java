package works.repka.ryscope.neoforge.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.ryscope.neoforge.ServerStages;

/**
 * Костёр. Еду на него кладёт игрок, поэтому решают его стадии: продукт закрытого рецепта просто не кладётся.
 */
@Mixin(CampfireBlockEntity.class)
public abstract class CampfireBlockEntityMixin {
    @Inject(method = "placeFood", at = @At("HEAD"), cancellable = true)
    private void ryscope$stageLock(LivingEntity entity, ItemStack food, int cookTime, CallbackInfoReturnable<Boolean> cir) {
        if (ServerStages.isCampfireLocked(entity, (CampfireBlockEntity) (Object) this, food)) cir.setReturnValue(false);
    }
}
