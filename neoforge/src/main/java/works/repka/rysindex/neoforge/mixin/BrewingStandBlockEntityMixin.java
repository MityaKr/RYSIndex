package works.repka.rysindex.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import works.repka.rysindex.neoforge.ServerStages;

/**
 * Варочная стойка. У зелий нет рецептов с id, поэтому замок смотрит на результат: если то, что получится,
 * относится к закрытой стадии того, кто открывал стойку последним, варка не начинается.
 */
@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBlockEntityMixin {
    @ModifyVariable(method = "serverTick", at = @At("STORE"), ordinal = 0)
    private static boolean rysindex$stageLock(boolean brewable, Level level, BlockPos pos, BlockState state, BrewingStandBlockEntity stand) {
        return brewable && !ServerStages.isBrewLocked(stand);
    }
}
