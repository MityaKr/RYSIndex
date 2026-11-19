package works.repka.rysindex.neoforge.mixin;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysindex.neoforge.ServerStages;

/**
 * Камнерез. Две задачи:
 * <ul>
 *   <li>рецепты скрытых стадий убираются из серверного списка. Клиент о них не знает, а рецепт выбирается
 *       по номеру в списке, так что без этого номера у клиента и сервера разошлись бы;</li>
 *   <li>результат рецепта закрытой стадии не выдаётся.</li>
 * </ul>
 */
@Mixin(StonecutterMenu.class)
public abstract class StonecutterMenuMixin {
    @Shadow
    private List<RecipeHolder<StonecutterRecipe>> recipes;
    @Shadow
    @Final
    Slot resultSlot;
    @Shadow
    @Final
    ResultContainer resultContainer;

    @Unique
    private Player rysindex$player;

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V", at = @At("RETURN"))
    private void rysindex$rememberPlayer(int containerId, Inventory playerInventory, ContainerLevelAccess access, CallbackInfo ci) {
        this.rysindex$player = playerInventory.player;
    }

    @Inject(method = "setupRecipeList", at = @At("TAIL"))
    private void rysindex$hideLockedRecipes(CallbackInfo ci) {
        if (this.rysindex$player instanceof ServerPlayer serverPlayer && !this.recipes.isEmpty()) {
            this.recipes = ServerStages.filterHidden(serverPlayer, this.recipes);
        }
    }

    @Inject(method = "setupResultSlot", at = @At("TAIL"))
    private void rysindex$stageLock(CallbackInfo ci) {
        if (!(this.rysindex$player instanceof ServerPlayer serverPlayer) || !this.resultSlot.hasItem()) return;
        RecipeHolder<?> used = this.resultContainer.getRecipeUsed();
        if (used != null && ServerStages.isCraftLocked(serverPlayer, used)) {
            this.resultSlot.set(ItemStack.EMPTY);
            ((AbstractContainerMenu) (Object) this).broadcastChanges();
        }
    }
}
