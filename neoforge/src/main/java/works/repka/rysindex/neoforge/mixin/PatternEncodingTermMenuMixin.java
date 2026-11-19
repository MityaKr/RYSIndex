package works.repka.rysindex.neoforge.mixin;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.util.ConfigInventory;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysindex.neoforge.ae2.ProcessingSubstitution;

/** Шаблон обработки с включённым замещением получает варианты входов из разложенного рецепта. */
@Pseudo
@Mixin(targets = "appeng.menu.me.items.PatternEncodingTermMenu")
public abstract class PatternEncodingTermMenuMixin {
    @Shadow(remap = false)
    @Final
    private ConfigInventory encodedInputsInv;

    @Inject(method = "encodeProcessingPattern", at = @At("RETURN"), remap = false, require = 0)
    private void rysindex$alternatives(CallbackInfoReturnable<ItemStack> cir) {
        ItemStack pattern = cir.getReturnValue();
        PatternEncodingTermMenu menu = (PatternEncodingTermMenu) (Object) this;
        if (pattern == null || pattern.isEmpty() || !menu.isSubstitute()) return;
        List<Item> slots = new ArrayList<>(encodedInputsInv.size());
        for (int i = 0; i < encodedInputsInv.size(); i++) {
            GenericStack stack = encodedInputsInv.getStack(i);
            slots.add(stack != null && stack.what() instanceof AEItemKey key ? key.getItem() : null);
        }
        List<List<Item>> alternatives = ProcessingSubstitution.alternativesFor((AbstractContainerMenu) (Object) this, slots);
        if (alternatives != null) pattern.set(ProcessingSubstitution.ALTERNATIVES.get(), alternatives);
    }
}
