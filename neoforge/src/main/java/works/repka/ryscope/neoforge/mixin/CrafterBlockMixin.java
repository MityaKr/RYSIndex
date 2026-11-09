package works.repka.ryscope.neoforge.mixin;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import works.repka.ryscope.neoforge.ServerStages;

/**
 * Автоверстак. Работает сам, без игрока, поэтому рецепт закрытой стадии не собирается, если стадия закрыта
 * у того, кто открывал этот автоверстак последним. Для игры это выглядит как «рецепт не найден».
 */
@Mixin(CrafterBlock.class)
public abstract class CrafterBlockMixin {
    @ModifyVariable(method = "dispenseFrom", at = @At("STORE"), ordinal = 0)
    private Optional<RecipeHolder<CraftingRecipe>> ryscope$stageLock(Optional<RecipeHolder<CraftingRecipe>> found,
                                                                    BlockState state, ServerLevel level, BlockPos pos) {
        if (found.isPresent() && level.getBlockEntity(pos) instanceof CrafterBlockEntity crafter
            && ServerStages.isCrafterLocked(crafter, found.get())) {
            return Optional.empty();
        }
        return found;
    }
}
