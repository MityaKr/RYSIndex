package works.repka.ryscope.content;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.InventoryView;
import works.repka.ryscope.ui.Draw;
import works.repka.ryscope.ui.theme.Theme;

/** Рецепт со своей раскладкой: слоты и декор расставлены по координатам, вид задаёт тема. */
public final class NativeRecipe implements RecipeView {
    public static final int SLOT = 18;
    public static final int LARGE = 26;
    /** Показывать ли красные метки недостающего: имеет смысл только там, где рецепт можно разложить по слотам. */
    public static boolean missingBadges = true;

    public enum Kind { CRAFTING, COOKING, SINGLE, SMITHING, OTHER }

    public static final class SlotW {
        public final Ing ing;
        public final int x, y;
        public final boolean output;
        public final boolean large;

        SlotW(Ing ing, int x, int y, boolean output, boolean large) {
            this.ing = ing;
            this.x = x;
            this.y = y;
            this.output = output;
            this.large = large;
        }

        public int size() {
            return large ? LARGE : SLOT;
        }
    }

    private enum DecoType { ARROW, FLAME, TEXT, PLUS, SHAPELESS }

    private record Deco(DecoType type, int x, int y, int w, String text) {}

    public final Kind kind;
    @Nullable
    private final ResourceLocation id;
    /** Исходный рецепт игры, нужен для переноса через книгу рецептов. */
    @Nullable
    public final RecipeHolder<?> holder;
    private final int width, height;
    public final List<SlotW> slots = new ArrayList<>();
    private final List<Deco> decos = new ArrayList<>();
    private final List<Ing> inputs = new ArrayList<>();
    private final List<Stack> outputs = new ArrayList<>();
    /** Для сеточных рецептов: ширина и высота сетки, вход i лежит в (i % gridW, i / gridW). */
    public int gridW, gridH;
    public boolean shapeless;
    /** Время выполнения в тиках и энергия в FE на одно выполнение (0 = нет данных). */
    public int ticks;
    public long energy;
    /** false для обмена и других способов получения, которые не являются рецептом (торговля). */
    public boolean crafting = true;

    @Override
    public boolean isCrafting() {
        return crafting;
    }

    @Override
    public int ticks() {
        return ticks;
    }

    @Override
    public long energy() {
        return energy;
    }

    public NativeRecipe(Kind kind, @Nullable RecipeHolder<?> holder, int width, int height) {
        this.kind = kind;
        this.holder = holder;
        this.id = holder == null ? null : holder.id();
        this.width = width;
        this.height = height;
    }

    public NativeRecipe input(Ing ing, int x, int y) {
        slots.add(new SlotW(ing, x, y, false, false));
        inputs.add(ing);
        return this;
    }

    public NativeRecipe inputLarge(Ing ing, int x, int y) {
        slots.add(new SlotW(ing, x, y, false, true));
        inputs.add(ing);
        return this;
    }

    public NativeRecipe output(Stack stack, int x, int y, boolean large) {
        slots.add(new SlotW(Ing.of(stack), x, y, true, large));
        outputs.add(stack);
        return this;
    }

    public NativeRecipe arrow(int x, int y, int w) {
        decos.add(new Deco(DecoType.ARROW, x, y, w, null));
        return this;
    }

    public NativeRecipe flame(int x, int y) {
        decos.add(new Deco(DecoType.FLAME, x, y, 0, null));
        return this;
    }

    public NativeRecipe plus(int x, int y) {
        decos.add(new Deco(DecoType.PLUS, x, y, 0, null));
        return this;
    }

    public NativeRecipe text(String text, int x, int y) {
        decos.add(new Deco(DecoType.TEXT, x, y, 0, text));
        return this;
    }

    public NativeRecipe shapelessMark(int x, int y) {
        decos.add(new Deco(DecoType.SHAPELESS, x, y, 0, null));
        return this;
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        for (Deco d : decos) {
            int dx = x + d.x, dy = y + d.y;
            switch (d.type) {
                case ARROW -> Draw.arrow(g, dx, dy, d.w, theme.c("text.dim"));
                case FLAME -> flame(g, dx, dy, theme.c("accent"), now);
                case PLUS -> {
                    Draw.rect(g, dx + 3, dy, 2, 8, theme.c("text.dim"));
                    Draw.rect(g, dx, dy + 3, 8, 2, theme.c("text.dim"));
                }
                case TEXT -> Draw.text(g, d.text, dx, dy, theme.c("text.muted"));
                case SHAPELESS -> shapelessIcon(g, dx, dy, theme.c("text.dim"));
            }
        }
        float missingAlpha = theme.m("missing.alpha", 0.45f);
        for (SlotW s : slots) {
            int sx = x + s.x, sy = y + s.y, size = s.size();
            boolean hover = mouseX >= sx && mouseX < sx + size && mouseY >= sy && mouseY < sy + size;
            theme.slot(g, sx, sy, size, hover && !s.ing.isEmpty());
            Stack shown = s.ing.shown(now);
            if (shown == null) continue;
            int ix = sx + (size - 16) / 2, iy = sy + (size - 16) / 2;
            shown.render(g, ix, iy);
            shown.renderOverlay(g, ix, iy);
            if (!s.output && missingBadges && crafting && InventoryView.tracking() && !InventoryView.has(s.ing)) {
                // недостающий ингредиент: приглушаем слот и помечаем уголок, не только цветом
                g.pose().pushPose();
                g.pose().translate(0, 0, 250);
                Draw.rect(g, sx + 1, sy + 1, size - 2, size - 2, Draw.alpha(theme.c("slot.bg"), 1f - missingAlpha));
                Draw.rect(g, sx + size - 5, sy + 1, 4, 4, theme.c("state.bad"));
                g.pose().popPose();
            }
            if (s.ing.options.size() > 1) {
                // несколько взаимозаменяемых вариантов
                g.pose().pushPose();
                g.pose().translate(0, 0, 250);
                Draw.rect(g, sx + 1, sy + size - 3, 2, 2, theme.c("accent"));
                g.pose().popPose();
            }
        }
    }

    private static void flame(GuiGraphics g, int x, int y, int color, long now) {
        int flick = (int) ((now / 180) % 3);
        Draw.rect(g, x + 3, y + 6, 6, 4, color);
        Draw.rect(g, x + 4, y + 3 - (flick == 0 ? 1 : 0), 4, 4, color);
        Draw.rect(g, x + 5, y + 1 - (flick == 1 ? 1 : 0), 2, 3, Draw.alpha(color, 0.8f));
        Draw.rect(g, x + 2, y + 7, 1, 2, Draw.alpha(color, 0.7f));
        Draw.rect(g, x + 9, y + 7, 1, 2, Draw.alpha(color, 0.7f));
    }

    private static void shapelessIcon(GuiGraphics g, int x, int y, int color) {
        // две перекрещенные стрелки: «порядок не важен»
        for (int i = 0; i < 7; i++) {
            Draw.rect(g, x + i, y + i, 1, 1, color);
            Draw.rect(g, x + i, y + 6 - i, 1, 1, color);
        }
        Draw.rect(g, x + 5, y, 2, 1, color);
        Draw.rect(g, x + 6, y + 1, 1, 1, color);
        Draw.rect(g, x + 5, y + 6, 2, 1, color);
        Draw.rect(g, x + 6, y + 5, 1, 1, color);
    }

    @Override
    public Stack stackAt(int x, int y, int mouseX, int mouseY) {
        for (SlotW s : slots) {
            int sx = x + s.x, sy = y + s.y, size = s.size();
            if (mouseX >= sx && mouseX < sx + size && mouseY >= sy && mouseY < sy + size) {
                return s.ing.shown(System.currentTimeMillis());
            }
        }
        return null;
    }

    @Override
    public List<Ing> inputs() {
        return inputs;
    }

    @Override
    public List<Stack> outputs() {
        return outputs;
    }

    @Override
    public Transfer transfer(AbstractContainerScreen<?> screen, boolean max, boolean simulate) {
        if (Minecraft.getInstance().player == null) return Transfer.UNSUPPORTED;
        return ClickTransfer.transfer(this, screen, max, simulate);
    }
}
