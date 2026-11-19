package works.repka.rysindex.neoforge.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysindex.neoforge.ServerStages;

/**
 * Второй рубеж замка крафта: результат закрытого рецепта нельзя забрать из слота результата.
 *
 * Первый рубеж (CraftingMenuMixin) не даёт результату появиться, но работает только там, где результат считает
 * сама игра. Моды вроде FastWorkbench считают его своим кодом, и там первый рубеж молчит (найдено на сборке RepYS).
 * Слот результата у них при этом обычный, поэтому запрет на выдачу из слота действует везде.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow
    @Final
    public Container container;

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void rysindex$stageLock(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ResultSlot) || !(player instanceof ServerPlayer serverPlayer)) return;
        if (!(this.container instanceof ResultContainer result)) return;
        RecipeHolder<?> recipe = result.getRecipeUsed();
        if (recipe != null && ServerStages.isCraftLocked(serverPlayer, recipe)) cir.setReturnValue(false);
    }
}
