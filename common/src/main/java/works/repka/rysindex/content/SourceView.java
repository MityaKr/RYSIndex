package works.repka.rysindex.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.RecipeView;
import works.repka.rysindex.model.Stack;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.theme.Theme;

/** Источник предметов: моб, блок или сундук со списком того, что из него выпадает, и шансами. */
public final class SourceView implements RecipeView {
    private static final int COLS = 8;
    private static final int PITCH_X = 20;
    private static final int PITCH_Y = 27;
    private static final int HEAD = 22;
    private static final int MAX_ROWS = 4;

    public record Drop(Stack stack, int min, int max, float chance, @Nullable String note) {}

    private final ResourceLocation id;
    @Nullable
    private final Stack icon;
    private final Component name;
    private final List<Drop> drops;
    private final List<Ing> inputs;
    private final List<Stack> outputs = new ArrayList<>();

    public SourceView(ResourceLocation id, @Nullable Stack icon, boolean indexIcon, Component name, List<Drop> drops) {
        this.id = id;
        this.icon = icon;
        this.name = name;
        this.drops = drops;
        this.inputs = icon != null && indexIcon ? List.of(Ing.of(icon)) : List.of();
        for (Drop d : drops) outputs.add(d.stack);
    }

    public Component name() {
        return name;
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    private int rows() {
        return Math.min(MAX_ROWS, (drops.size() + COLS - 1) / COLS);
    }

    @Override
    public int width() {
        return COLS * PITCH_X;
    }

    @Override
    public int height() {
        return HEAD + rows() * PITCH_Y - 2;
    }

    public static String percent(float chance) {
        float p = chance * 100f;
        if (p >= 99.95f) return "100%";
        if (p >= 10f) return Math.round(p) + "%";
        if (p >= 1f) return String.format(Locale.ROOT, "%.1f%%", p);
        if (p >= 0.1f) return String.format(Locale.ROOT, "%.1f%%", p);
        return "<0.1%";
    }

    @Override
    public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int tx = x;
        if (icon != null) {
            theme.slot(g, x, y, 18, over(mouseX, mouseY, x, y, 18, 18));
            icon.render(g, x + 1, y + 1);
            tx += 23;
        }
        Draw.text(g, Draw.ellipsize(name.getString(), width() - (tx - x)), tx, y + 5, theme.c("text.body"));
        int shown = Math.min(drops.size(), rows() * COLS);
        for (int i = 0; i < shown; i++) {
            Drop d = drops.get(i);
            int sx = x + (i % COLS) * PITCH_X, sy = y + HEAD + (i / COLS) * PITCH_Y;
            theme.slot(g, sx, sy, 18, over(mouseX, mouseY, sx, sy, 18, 18));
            d.stack.render(g, sx + 1, sy + 1);
            if (d.max > 1 || d.min != d.max) {
                String count = d.min == d.max ? Integer.toString(d.max) : d.min + "-" + d.max;
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                Draw.textScaled(g, count, sx + 18 - Draw.widthScaled(count, 0.75f), sy + 11, 0.75f, 0xFFFFFFFF);
                g.pose().popPose();
            }
            String pct = percent(d.chance);
            int color = d.chance >= 0.5f ? theme.c("text.body") : d.chance >= 0.05f ? theme.c("text.muted") : theme.c("state.warn");
            Draw.textScaled(g, pct, sx + 9 - Draw.width(pct) * 0.3f, sy + 19, 0.6f, color);
            if (d.note != null) {
                // условие обозначено не только цветом: точка в углу и строка в подсказке
                Draw.rect(g, sx + 1, sy + 1, 3, 3, theme.c("accent"));
            }
        }
        if (drops.size() > shown) {
            String more = "+" + (drops.size() - shown);
            Draw.textScaled(g, more, x + width() - Draw.widthScaled(more, 0.75f), y + 6, 0.75f, theme.c("text.dim"));
        }
    }

    private static boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Nullable
    private Drop dropAt(int x, int y, int mouseX, int mouseY) {
        int shown = Math.min(drops.size(), rows() * COLS);
        for (int i = 0; i < shown; i++) {
            int sx = x + (i % COLS) * PITCH_X, sy = y + HEAD + (i / COLS) * PITCH_Y;
            if (over(mouseX, mouseY, sx, sy, 18, 18)) return drops.get(i);
        }
        return null;
    }

    @Override
    public Stack stackAt(int x, int y, int mouseX, int mouseY) {
        if (icon != null && over(mouseX, mouseY, x, y, 18, 18)) return icon;
        Drop d = dropAt(x, y, mouseX, mouseY);
        return d == null ? null : d.stack;
    }

    @Override
    public boolean renderTooltip(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        Drop d = dropAt(x, y, mouseX, mouseY);
        if (d == null) return false;
        List<Component> lines = new ArrayList<>(d.stack.tooltipLines());
        lines.add(Component.translatable("rysindex.source.chance", percent(d.chance)));
        if (d.max > 1 || d.min != d.max) {
            lines.add(Component.translatable("rysindex.source.count", d.min == d.max ? Integer.toString(d.max) : d.min + "–" + d.max));
        }
        if (d.note != null) lines.add(Component.translatable("rysindex.source.note." + d.note));
        g.renderComponentTooltip(Minecraft.getInstance().font, lines, mouseX, mouseY);
        return true;
    }

    @Override
    public List<Ing> inputs() {
        return inputs;
    }

    @Override
    public List<Stack> outputs() {
        return outputs;
    }

    public List<Drop> drops() {
        return drops;
    }

    @Override
    public boolean isCrafting() {
        return false;
    }
}
