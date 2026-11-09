package works.repka.ryscope.jei;

import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.TooltipFlag;
import works.repka.ryscope.model.Stack;

/** Ингредиент JEI, который не является предметом: жидкость, газ, энергия и всё, что зарегистрировали моды. */
public final class JeiRes<T> extends Stack {
    private record Key(String type, Object uid) {}

    public final ITypedIngredient<T> typed;
    private final IIngredientHelper<T> helper;
    private final IIngredientRenderer<T> renderer;
    private final Key key;

    public JeiRes(ITypedIngredient<T> typed, IIngredientHelper<T> helper, IIngredientRenderer<T> renderer) {
        this.typed = typed;
        this.helper = helper;
        this.renderer = renderer;
        this.key = new Key(typed.getType().getUid(), helper.getUid(typed.getIngredient(), UidContext.Ingredient));
    }

    @Override
    public Object key() {
        return key;
    }

    @Override
    public String type() {
        return key.type;
    }

    @Override
    public ResourceLocation id() {
        return helper.getResourceLocation(typed.getIngredient());
    }

    @Override
    public Component name() {
        return Component.literal(helper.getDisplayName(typed.getIngredient()));
    }

    @Override
    public void render(GuiGraphics g, int x, int y) {
        int ox = (16 - renderer.getWidth()) / 2, oy = (16 - renderer.getHeight()) / 2;
        renderer.render(g, typed.getIngredient(), x + ox, y + oy);
    }

    @Override
    public List<Component> tooltipLines() {
        return renderer.getTooltip(typed.getIngredient(), TooltipFlag.NORMAL);
    }

    @Override
    public List<String> tags() {
        List<String> out = new ArrayList<>();
        helper.getTagStream(typed.getIngredient()).forEach(t -> out.add(t.toString()));
        return out;
    }

    @Override
    public boolean same(Stack other) {
        return other instanceof JeiRes<?> o && o.key.equals(key);
    }

    @Override
    public Stack copy() {
        JeiRes<T> r = new JeiRes<>(typed, helper, renderer);
        r.amount = amount;
        r.chance = chance;
        return r;
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }
}
