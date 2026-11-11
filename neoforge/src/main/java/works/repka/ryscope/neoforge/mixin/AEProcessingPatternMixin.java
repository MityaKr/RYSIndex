package works.repka.ryscope.neoforge.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.ryscope.neoforge.ae2.AlternativesHolder;
import works.repka.ryscope.neoforge.ae2.ProcessingSubstitution;

/**
 * Шаблон обработки с вариантами: каждый вход перечисляет все допустимые предметы. Автокрафт AE2 сам умеет вход
 * с несколькими вариантами (так работают шаблоны крафта с замещением): процессор берёт любой и отдаёт в машину.
 */
@Pseudo
@Mixin(targets = "appeng.crafting.pattern.AEProcessingPattern")
public abstract class AEProcessingPatternMixin {
    @Shadow(remap = false)
    @Final
    private AEItemKey definition;

    @Shadow(remap = false)
    @Final
    private List<GenericStack> sparseInputs;

    /** Варианты по записанному предмету входа; null — у шаблона замещения нет. */
    @Unique
    private Map<AEKey, List<AEKey>> ryscope$variants;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false, require = 0)
    private void ryscope$alternatives(CallbackInfo ci) {
        List<List<Item>> alternatives = definition.get(ProcessingSubstitution.ALTERNATIVES.get());
        if (alternatives == null) return;
        // одинаковые входы AE2 сливает в один: варианты собираются по предмету
        Map<AEKey, Set<Item>> byKey = new HashMap<>();
        for (int i = 0; i < Math.min(sparseInputs.size(), alternatives.size()); i++) {
            GenericStack stack = sparseInputs.get(i);
            List<Item> variants = alternatives.get(i);
            if (stack == null || variants.size() < 2) continue;
            byKey.computeIfAbsent(stack.what(), k -> new LinkedHashSet<>()).addAll(variants);
        }
        if (byKey.isEmpty()) return;
        ryscope$variants = new HashMap<>();
        for (Map.Entry<AEKey, Set<Item>> e : byKey.entrySet()) {
            List<AEKey> keys = new ArrayList<>();
            for (Item item : e.getValue()) {
                AEItemKey key = AEItemKey.of(item);
                if (key != null && !key.equals(e.getKey())) keys.add(key);
            }
            ryscope$variants.put(e.getKey(), keys);
        }
        for (IPatternDetails.IInput input : ((IPatternDetails) (Object) this).getInputs()) {
            if (!(input instanceof AlternativesHolder holder)) continue;
            GenericStack[] template = input.getPossibleInputs();
            if (template.length == 0) continue;
            Set<Item> variants = byKey.get(template[0].what());
            if (variants == null) continue;
            List<GenericStack> options = new ArrayList<>();
            options.add(template[0]);
            for (Item item : variants) {
                AEItemKey key = AEItemKey.of(item);
                if (key != null && !key.equals(template[0].what())) options.add(new GenericStack(key, 1));
            }
            if (options.size() > 1) holder.ryscope$setAlternatives(options.toArray());
        }
    }

    /**
     * Отправка входов в машину: AE2 раскладывает их строго по записанным предметам и падает, если процессор взял
     * вариант. Здесь записанный предмет добирается вариантами, при нехватке — вперемешку.
     */
    @Inject(method = "pushInputsToExternalInventory", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ryscope$push(appeng.api.stacks.KeyCounter[] inputHolder, IPatternDetails.PatternInputSink sink, CallbackInfo ci) {
        if (ryscope$variants == null) return;
        appeng.api.stacks.KeyCounter all = new appeng.api.stacks.KeyCounter();
        for (appeng.api.stacks.KeyCounter counter : inputHolder) all.addAll(counter);
        for (GenericStack input : sparseInputs) {
            if (input == null) continue;
            long remaining = input.amount();
            List<AEKey> options = new ArrayList<>();
            options.add(input.what());
            options.addAll(ryscope$variants.getOrDefault(input.what(), List.of()));
            for (AEKey key : options) {
                if (remaining <= 0) break;
                long take = Math.min(remaining, all.get(key));
                if (take <= 0) continue;
                sink.pushInput(key, take);
                all.remove(key, take);
                remaining -= take;
            }
            if (remaining > 0) {
                throw new RuntimeException("Expected %d more of %s or its substitutes when pushing pattern".formatted(remaining, input.what()));
            }
        }
        ci.cancel();
    }
}
