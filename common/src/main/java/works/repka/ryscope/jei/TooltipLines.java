package works.repka.ryscope.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import net.minecraft.network.chat.Component;

/** Строки подсказки, которую мод пишет в построитель JEI: берём только текст. */
final class TooltipLines {
    private TooltipLines() {}

    static List<Component> collect(Consumer<ITooltipBuilder> writer) {
        List<Component> out = new ArrayList<>();
        writer.accept(new ITooltipBuilder() {
            @Override
            public void add(net.minecraft.network.chat.FormattedText text) {
                if (text instanceof Component c) out.add(c);
                else if (text != null) out.add(Component.literal(text.getString()));
            }

            @Override
            public void addAll(java.util.Collection<? extends net.minecraft.network.chat.FormattedText> texts) {
                texts.forEach(this::add);
            }

            @Override
            public void add(net.minecraft.world.inventory.tooltip.TooltipComponent component) {
            }

            @Override
            public void addKeyUsageComponent(String key, mezz.jei.api.runtime.IJeiKeyMapping mapping) {
            }

            @Override
            public void setIngredient(mezz.jei.api.ingredients.ITypedIngredient<?> ingredient) {
            }

            @Override
            public void clearIngredient() {
            }

            @Override
            public List<com.mojang.datafixers.util.Either<net.minecraft.network.chat.FormattedText, net.minecraft.world.inventory.tooltip.TooltipComponent>> getLines() {
                return new ArrayList<>();
            }

            @Override
            public List<Component> toLegacyToComponents() {
                return out;
            }

            @Override
            public void removeAll(List<Component> lines) {
                out.removeAll(lines);
            }
        });
        return out;
    }
}
