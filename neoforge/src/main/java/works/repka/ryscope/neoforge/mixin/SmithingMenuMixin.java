package works.repka.ryscope.neoforge.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmithingRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.ryscope.neoforge.ServerStages;

/** Кузнечный стол: результат рецепта закрытой стадии не выдаётся. */
@Mixin(SmithingMenu.class)
public abstract class SmithingMenuMixin extends ItemCombinerMenu {
    @Shadow
    private RecipeHolder<SmithingRecipe> selectedRecipe;

    protected SmithingMenuMixin(MenuType<?> type, int containerId, Inventory playerInventory, ContainerLevelAccess access) {
        super(type, containerId, playerInventory, access);
    }

    @Inject(method = "createResult", at = @At("TAIL"))
    private void ryscope$stageLock(CallbackInfo ci) {
        if (this.player instanceof ServerPlayer serverPlayer && this.selectedRecipe != null
            && !this.resultSlots.getItem(0).isEmpty() && ServerStages.isCraftLocked(serverPlayer, this.selectedRecipe)) {
            this.resultSlots.setItem(0, ItemStack.EMPTY);
        }
    }
}
