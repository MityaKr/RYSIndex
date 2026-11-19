package works.repka.rysindex.neoforge.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import works.repka.rysindex.neoforge.ServerStages;

/**
 * Сетка крафта (верстак и инвентарь) перед выдачей результата спрашивает, можно ли игроку этот рецепт:
 * игра использует это для правила doLimitedCrafting. Добавляем сюда проверку стадии.
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {
    @Redirect(
        method = "slotChangedCraftingGrid",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/ResultContainer;setRecipeUsed(Lnet/minecraft/world/level/Level;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/crafting/RecipeHolder;)Z"))
    private static boolean rysindex$stageLock(ResultContainer result, Level level, ServerPlayer player, RecipeHolder<?> recipe) {
        if (ServerStages.isCraftLocked(player, recipe)) return false;
        return result.setRecipeUsed(level, player, recipe);
    }
}
