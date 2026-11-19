package works.repka.rysindex.model;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Категория рецептов: вкладка в окне рецептов. */
public class Category {
    public final ResourceLocation id;
    public final Component title;
    @Nullable
    public final Stack icon;
    /** Станции, на которых делаются рецепты категории. */
    public final List<Stack> stations = new ArrayList<>();
    public final int order;

    public Category(ResourceLocation id, Component title, @Nullable Stack icon, int order) {
        this.id = id;
        this.title = title;
        this.icon = icon;
        this.order = order;
    }

    public void renderIcon(GuiGraphics g, int x, int y) {
        if (icon != null) {
            icon.render(g, x, y);
        } else if (!stations.isEmpty()) {
            stations.get(0).render(g, x, y);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Category c && c.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
