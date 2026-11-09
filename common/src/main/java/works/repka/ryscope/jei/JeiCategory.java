package works.repka.ryscope.jei;

import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import works.repka.ryscope.model.Category;

/** Категория рецептов из плагина JEI. */
public final class JeiCategory extends Category {
    public final IRecipeCategory<?> category;

    public JeiCategory(IRecipeCategory<?> category, int order) {
        super(category.getRecipeType().getUid(), category.getTitle(), null, order);
        this.category = category;
    }

    @Override
    public void renderIcon(GuiGraphics g, int x, int y) {
        IDrawable icon = category.getIcon();
        if (icon != null) {
            icon.draw(g, x + (16 - icon.getWidth()) / 2, y + (16 - icon.getHeight()) / 2);
        } else {
            super.renderIcon(g, x, y);
        }
    }
}
