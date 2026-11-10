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
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.theme.Theme;

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

    private enum DecoType { ARROW, FLAME, TEXT, PLUS, SHAPELESS, FLOW }

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
    /** Размеры слотов: у рецептов игры они как в макете, 24 и 32. */
    public int slotSize = SLOT, largeSize = LARGE;

    private int sizeOf(SlotW s) {
        return s.large ? largeSize : slotSize;
    }

    /** Стрелка с подписью под ней, как в макете. {@code w} ширина колонки, {@code y} её середина. */
    public NativeRecipe flow(int x, int y, int w, String caption) {
        decos.add(new Deco(DecoType.FLOW, x, y, w, caption));
        return this;
    }

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
                case FLOW -> {
                    works.repka.ryscore.ui.Icons.draw(g, works.repka.ryscore.ui.Icons.Shape.ARROW, dx + d.w / 2f - 4.5f, dy - 4.5f, 9f, theme.c("text.muted"));
                    if (!d.text.isEmpty()) {
                        float cw = Draw.labelWidth(d.text, 10.5f, works.repka.ryscore.ui.Type.Weight.REGULAR);
                        Draw.label(g, d.text, dx + (d.w - cw) / 2f, dy + 9f, 10.5f, works.repka.ryscore.ui.Type.Weight.REGULAR, theme.c("text.muted"));
                    }
                }
            }
        }
        float missingAlpha = theme.m("missing.alpha", 0.45f);
        for (SlotW s : slots) {
            int sx = x + s.x, sy = y + s.y, size = sizeOf(s);
            boolean hover = mouseX >= sx && mouseX < sx + size && mouseY >= sy && mouseY < sy + size;
            int stateBorder = 0;
            if (!s.output && missingBadges && crafting && !s.ing.isEmpty()) {
                // нехватка одним цветом: рамка bad; что есть, то с обычной рамкой
                boolean terminal = works.repka.ryscope.runtime.Ae2Terminal.active();
                boolean lacking = terminal
                    ? works.repka.ryscope.runtime.Ae2Terminal.stateOf(s.ing) != works.repka.ryscope.runtime.Ae2Terminal.State.HAVE
                    : !InventoryView.has(s.ing);
                if ((terminal || InventoryView.tracking()) && lacking) stateBorder = Draw.alpha(theme.c("state.bad"), 0.7f);
            }
            if (slotSize != SLOT) theme.slotRounded(g, sx, sy, size, s.large ? 8f : 5f, hover && !s.ing.isEmpty(), stateBorder);
            else theme.slot(g, sx, sy, size, hover && !s.ing.isEmpty(), stateBorder);
            Stack shown = s.ing.shown(now);
            if (shown == null) continue;
            int ix = sx + (size - 16) / 2, iy = sy + (size - 16) / 2;
            if (slotSize != SLOT && size < 25) {
                // значок в ячейке макета: половина ячейки, у результата три четверти
                float itemScale = s.large ? 0.75f : 0.5f;
                float shift = (size - 16 * itemScale) / 2f;
                g.pose().pushPose();
                g.pose().translate(sx + shift, sy + shift, 0);
                g.pose().scale(itemScale, itemScale, 1f);
                shown.render(g, 0, 0);
                g.pose().popPose();
                shown.renderScaledAmount(g, sx, sy, size);
            } else {
                shown.render(g, ix, iy);
                shown.renderOverlay(g, ix, iy);
            }
            works.repka.ryscope.runtime.Ae2Terminal.State ae2 = !s.output && missingBadges && crafting
                ? works.repka.ryscope.runtime.Ae2Terminal.stateOf(s.ing) : works.repka.ryscope.runtime.Ae2Terminal.State.HAVE;
            if (ae2 == works.repka.ryscope.runtime.Ae2Terminal.State.CRAFTABLE) {
                // ингредиента нет, но сеть AE2 умеет его сделать: Ctrl при переносе закажет
                g.pose().pushPose();
                g.pose().translate(0, 0, 250);
                Draw.rect(g, sx + size - 5, sy + 1, 4, 4, theme.c("accent"));
                g.pose().popPose();
            }
            boolean ae2Terminal = works.repka.ryscope.runtime.Ae2Terminal.active();
            if (!s.output && missingBadges && crafting
                && (ae2Terminal ? ae2 == works.repka.ryscope.runtime.Ae2Terminal.State.MISSING : InventoryView.tracking() && !InventoryView.has(s.ing))) {
                // недостающий ингредиент: приглушаем слот и помечаем уголок, не только цветом
                g.pose().pushPose();
                g.pose().translate(0, 0, 250);
                Draw.rect(g, sx + 1, sy + 1, size - 2, size - 2, Draw.alpha(theme.c("slot.bg"), 1f - missingAlpha));
                if (slotSize == SLOT) Draw.rect(g, sx + size - 5, sy + 1, 4, 4, theme.c("state.bad"));
                g.pose().popPose();
            }
            if (s.ing.options.size() > 1 && slotSize == SLOT) {
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
            int sx = x + s.x, sy = y + s.y, size = sizeOf(s);
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
        Transfer ae2 = works.repka.ryscope.runtime.Ae2Terminal.transfer(screen.getMenu(), holder, inputs, outputs, simulate);
        if (ae2 != null) return ae2;
        return ClickTransfer.transfer(this, screen, max, simulate);
    }
}
