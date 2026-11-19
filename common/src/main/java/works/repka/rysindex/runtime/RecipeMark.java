package works.repka.rysindex.runtime;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import works.repka.rysindex.model.RecipeView;
import works.repka.rysindex.model.Stack;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;

/**
 * Закладка на конкретный рецепт (как закладка рецепта в JEI 19): в ячейке результат рецепта со значком
 * в углу, при наведении сам рецепт. Клик открывает именно его, клавиша крафта раскладывает его в сетку.
 */
public final class RecipeMark extends Stack {
    public final Stack output;
    public final RecipeRef ref;
    /**
     * Найденный рецепт и снимок, для которого он искался: после переиндексации ищется заново.
     * Снимок держится слабо, чтобы закладка не держала прежний индекс; рецепт отпускает {@link #release}.
     */
    @Nullable
    private Lookup.Group resolved;
    private java.lang.ref.WeakReference<Snapshot> resolvedFor = new java.lang.ref.WeakReference<>(null);

    public RecipeMark(Stack output, RecipeRef ref) {
        this.output = output.withAmount(1);
        this.ref = ref;
    }

    /** Рецепт закладки или null, если его больше нет в сборке. */
    @Nullable
    public Lookup.Group group() {
        Snapshot snap = Indexer.snapshot();
        if (resolvedFor.get() != snap) {
            resolvedFor = new java.lang.ref.WeakReference<>(snap);
            resolved = ref.resolve(output);
        }
        return resolved;
    }

    /** Отпускает найденный рецепт: в нём рецепты и мост прежнего рантайма JEI. Найдётся заново при наведении. */
    void release() {
        resolved = null;
        resolvedFor = new java.lang.ref.WeakReference<>(null);
    }

    @Nullable
    public RecipeView recipe() {
        Lookup.Group g = group();
        return g == null || g.recipes().isEmpty() ? null : g.recipes().get(0);
    }

    @Override
    public Object key() {
        return output.key();
    }

    @Override
    public String type() {
        return output.type();
    }

    @Override
    public ResourceLocation id() {
        return output.id();
    }

    @Override
    public Component name() {
        return output.name();
    }

    @Override
    public void render(GuiGraphics g, int x, int y) {
        output.render(g, x, y);
        // значок рецепта в углу: отличить от закладки на сам предмет
        Theme t = ThemeManager.get();
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        Draw.roundBox(g, x + 10, y + 10, 6.5f, 6.5f, 1.5f, t.c("panel.bg"), t.c("accent"));
        works.repka.ryscore.ui.Icons.draw(g, works.repka.ryscore.ui.Icons.Shape.PLUS, x + 11.25f, y + 11.25f, 4f, t.c("accent"));
        g.pose().popPose();
    }

    @Override
    public List<Component> tooltipLines() {
        List<Component> lines = new ArrayList<>(output.tooltipLines());
        Lookup.Group g = group();
        lines.add(Component.translatable(g == null ? "rysindex.recipe_mark.missing" : "rysindex.recipe_mark.tooltip",
            g == null ? ref.recipe().toString() : g.category().title.getString()));
        return lines;
    }

    /** Подсказка с самим рецептом: его не надо открывать, чтобы вспомнить раскладку. */
    @Override
    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        RecipeView r = recipe();
        if (r == null) {
            super.renderTooltip(g, mouseX, mouseY);
            return;
        }
        Theme t = ThemeManager.get();
        int pad = 5, w = r.width() + pad * 2, h = r.height() + pad * 2 + 10;
        int x = mouseX + 10, y = mouseY - 6;
        if (x + w > g.guiWidth() - 4) x = mouseX - 10 - w;
        if (x < 4) x = 4;
        if (y + h > g.guiHeight() - 4) y = g.guiHeight() - 4 - h;
        if (y < 4) y = 4;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        t.tooltipBox(g, x, y, w, h);
        String title = Draw.ellipsize(output.name().getString(), w - pad * 2, 11.5f, works.repka.ryscore.ui.Type.Weight.MEDIUM);
        Draw.label(g, title, x + pad, y + pad + 3, 11.5f, works.repka.ryscore.ui.Type.Weight.MEDIUM, t.c("text.primary"));
        int rx = x + pad, ry = y + pad + 10;
        if (r.needsPlate()) t.plate(g, rx - 2, ry - 2, r.width() + 4, r.height() + 4);
        try {
            Draw.isolated(g, () -> r.render(g, t, rx, ry, -1, -1, 0));
        } catch (Throwable ignored) {
            // чужой рецепт
        }
        g.pose().popPose();
    }

    @Override
    public List<String> tags() {
        return output.tags();
    }

    @Override
    public boolean same(Stack other) {
        return other instanceof RecipeMark m && m.ref.equals(ref);
    }

    @Override
    public Stack copy() {
        RecipeMark m = new RecipeMark(output, ref);
        m.amount = amount;
        m.chance = chance;
        return m;
    }

    @Override
    public ItemStack item() {
        return output.item();
    }

    @Override
    public int hashCode() {
        return ref.hashCode();
    }
}
