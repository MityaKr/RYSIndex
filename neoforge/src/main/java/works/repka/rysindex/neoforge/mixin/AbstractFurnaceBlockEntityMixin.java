package works.repka.rysindex.neoforge.mixin;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysindex.neoforge.ServerStages;

/**
 * Печь, плавильня и коптильня. Печь работает сама, без игрока, поэтому рецепт закрытой стадии
 * не плавится, если стадия закрыта у того, кто открывал эту печь последним.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin {
    @Inject(method = "canBurn", at = @At("HEAD"), cancellable = true)
    private static void rysindex$stageLock(RegistryAccess registryAccess, RecipeHolder<?> recipe, NonNullList<ItemStack> inventory,
                                          int maxStackSize, AbstractFurnaceBlockEntity furnace, CallbackInfoReturnable<Boolean> cir) {
        if (recipe != null && ServerStages.isSmeltLocked(furnace, recipe)) cir.setReturnValue(false);
    }
}
