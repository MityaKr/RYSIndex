package works.repka.ryscope.content;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.LinkMath;
import works.repka.ryscope.runtime.Links;
import works.repka.ryscore.links.ItemLinks;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Type.Weight;

/**
 * Карточка поставщика сведений (сделка магазина книги, награда квеста) нашим видом, по макетам
 * {@code _shared/_audit/ryscope-quests} 1 и 2: значок и название, блоки «что дают» и «что нужно» строками со
 * слотами, «есть / нужно» с полосой, монеты токеном {@code coin}, строка состояния, врезка замка со ссылкой и серая
 * кнопка действия. Это не рецепт крафта: вкладка «Источники» или «Использования», без «+» и дерева.
 * Числа в единицах интерфейса (половина пикселя макета).
 */
public final class LinkCardView implements RecipeView {
    /** Ширина под рейку категорий; без рейки карточка растягивается на весь блок ({@link #stretch}). */
    public static final int MIN_W = 188;
    static final int HEAD = 22, CAPS = 6, ROW = 18, LINE = 8, CALLOUT = 16, BUTTON = 15, GAP = 5;

    private enum Kind { HEADER, CAPS, ITEM, COIN, NOTE, LOCK, LINK, BUTTON }

    private record El(Kind kind, int y, int h, @Nullable ItemLinks.Part part, @Nullable Component text, @Nullable Stack stack) {}

    private final ItemLinks.Card card;
    private final ResourceLocation provider;
    private final List<El> elements = new ArrayList<>();
    private final int height;
    @Nullable
    private final Stack icon;
    /** Количество рядом с названием, когда значок это первый предмет «что дают». */
    private final long iconAmount;
    private final List<Ing> inputs = new ArrayList<>();
    private final List<Stack> outputs = new ArrayList<>();
    private int width = MIN_W;
    /** Где карточка нарисована в последнем кадре: для нажатий по ссылке и кнопке. */
    private int lastX = Integer.MIN_VALUE, lastY;

    public LinkCardView(ItemLinks.Card card, ItemLinks.Provider provider) {
        this.card = card;
        ResourceLocation id;
        try {
            id = provider.id();
        } catch (Throwable t) {
            id = card.group().id();
        }
        this.provider = id;
        List<ItemLinks.Part> gives = card.gives();
        boolean iconFromGives = card.icon().isEmpty() && !gives.isEmpty() && !gives.get(0).coin() && !gives.get(0).stack().isEmpty();
        ItemStack iconStack = !card.icon().isEmpty() ? card.icon() : iconFromGives ? gives.get(0).stack() : ItemStack.EMPTY;
        this.icon = iconStack.isEmpty() ? null : ItemRes.of(iconStack.copyWithCount(1));
        this.iconAmount = iconFromGives ? gives.get(0).amount() : 0;
        add(Kind.HEADER, HEAD, null, null, null);
        List<ItemLinks.Part> givesShown = iconFromGives ? gives.subList(1, gives.size()) : gives;
        parts(card.givesTitle(), givesShown);
        parts(card.takesTitle(), card.takes());
        if (card.note() != null) add(Kind.NOTE, LINE, null, card.note(), null);
        if (card.lock() != null) add(Kind.LOCK, CALLOUT, null, card.lock(), null);
        if (card.lockLink() != null) add(Kind.LINK, LINE, null, card.lockLink().label(), null);
        if (card.open() != null) add(Kind.BUTTON, BUTTON, null, card.open().label(), null);
        int[] heights = new int[elements.size()];
        for (int i = 0; i < heights.length; i++) heights[i] = elements.get(i).h;
        this.height = LinkMath.stacked(heights, GAP);
        for (ItemLinks.Part p : gives) if (!p.coin() && !p.stack().isEmpty()) outputs.add(ItemRes.of(p.stack().copyWithCount(1)));
        for (ItemLinks.Part p : card.takes()) if (!p.coin() && !p.stack().isEmpty()) inputs.add(Ing.of(ItemRes.of(p.stack().copyWithCount(1))));
    }

    private void parts(@Nullable Component title, List<ItemLinks.Part> parts) {
        if (title == null || parts.isEmpty()) return;
        add(Kind.CAPS, CAPS, null, title, null);
        // предметы строками, монеты после них
        for (ItemLinks.Part p : parts) {
            if (!p.coin() && !p.stack().isEmpty()) add(Kind.ITEM, ROW, p, null, ItemRes.of(p.stack().copyWithCount(1)));
        }
        for (ItemLinks.Part p : parts) if (p.coin()) add(Kind.COIN, LINE, p, p.label(), null);
    }

    private void add(Kind kind, int h, @Nullable ItemLinks.Part part, @Nullable Component text, @Nullable Stack stack) {
        int y = 0;
        if (!elements.isEmpty()) {
            El last = elements.get(elements.size() - 1);
            y = last.y + last.h + GAP;
        }
        elements.add(new El(kind, y, h, part, text, stack));
    }

    public ItemLinks.Card card() {
        return card;
    }

    public ResourceLocation provider() {
        return provider;
    }

    /** Ширина блока рецепта: кнопок справа у карточки нет, поэтому она занимает весь блок. */
    public void stretch(int available) {
        width = Math.max(MIN_W, available);
    }

    @Override
    @Nullable
    public ResourceLocation id() {
        // без id у карточки нет звезды «закладка на рецепт»: сделка живёт в книге, а не в индексе
        return null;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    private boolean locked() {
        return card.state() == ItemLinks.State.LOCKED;
    }

    private static boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public void render(GuiGraphics g, Theme t, int x, int y, int mx, int my, float partialTick) {
        lastX = x;
        lastY = y;
        for (El e : elements) {
            int ey = y + e.y;
            switch (e.kind) {
                case HEADER -> renderHeader(g, t, x, ey, mx, my);
                case CAPS -> Kit.caps(g, t, e.text.getString(), x, ey + CAPS / 2f, 11, 0.44f);
                case ITEM -> renderItem(g, t, e, x, ey, mx, my);
                case COIN -> renderCoin(g, t, e, x, ey);
                case NOTE -> {
                    Draw.round(g, x + 1, ey + 2, 4, 4, 2f, t.c(stateToken(card.state())));
                    Draw.label(g, Draw.ellipsize(e.text.getString(), width - 9, 11.5f, Weight.REGULAR), x + 9, ey + LINE / 2f, 11.5f, Weight.REGULAR, t.c("text.body"));
                }
                case LOCK -> {
                    Draw.roundBox(g, x, ey, width, CALLOUT, 4f, t.c("callout.bg"), t.c("callout.border"));
                    Draw.lock(g, x + 5, ey + 4, t.c("state.warn"));
                    Draw.label(g, Draw.ellipsize(e.text.getString(), width - 22, 12, Weight.REGULAR), x + 16, ey + CALLOUT / 2f, 12, Weight.REGULAR, t.c("text.primary"));
                }
                case LINK -> {
                    String text = e.text.getString();
                    float w = Draw.labelWidth(text, 12, Weight.REGULAR);
                    boolean hot = over(mx, my, x, ey - 1, Math.round(w) + 10, LINE + 2);
                    int color = hot ? Draw.mix(t.c("link"), t.c("text.primary"), 0.35f) : t.c("link");
                    Icons.draw(g, Icons.Shape.BOOK, x, ey + 0.5f, 7f, color);
                    Draw.label(g, Draw.ellipsize(text, width - 10, 12, Weight.REGULAR), x + 10, ey + LINE / 2f, 12, Weight.REGULAR, color);
                }
                case BUTTON -> {
                    int bw = buttonWidth(e.text.getString());
                    boolean hot = over(mx, my, x, ey, bw, BUTTON);
                    // серая: покупка и сдача идут в окне мода, янтарной кнопки в карточке предмета нет
                    t.button(g, x, ey, bw, BUTTON, hot, false);
                    Draw.label(g, e.text.getString(), x + 6, ey + BUTTON / 2f, 12, Weight.REGULAR, t.c("button.fg"));
                    Icons.draw(g, Icons.Shape.ARROW, x + bw - 12.5f, ey + 4.25f, 6.5f, hot ? t.c("text.primary") : t.c("button.fg"));
                }
            }
        }
    }

    private int buttonWidth(String label) {
        return Math.min(width, Math.round(Draw.labelWidth(label, 12, Weight.REGULAR)) + 6 + 4 + 6 + 6);
    }

    private void renderHeader(GuiGraphics g, Theme t, int x, int y, int mx, int my) {
        if (icon != null) {
            t.slot(g, x, y, HEAD, over(mx, my, x, y, HEAD, HEAD));
            icon.render(g, x + 3, y + 3);
        } else {
            Draw.roundBox(g, x, y, HEAD, HEAD, 4.5f, t.c("slot.bg"), t.c("slot.border"));
            Icons.draw(g, Icons.Shape.BOOK, x + 5, y + 5, 12f, t.c("link"));
        }
        float tx = x + HEAD + 5;
        float room = x + width - tx;
        String amount = iconAmount > 0 ? "×" + LinkMath.thousands(iconAmount) : "";
        float amountW = amount.isEmpty() ? 0 : Draw.labelWidth(amount, 13, Weight.REGULAR) + 3;
        String title = Draw.ellipsize(card.title().getString(), room - amountW, 13, Weight.MEDIUM);
        Draw.label(g, title, tx, y + 7, 13, Weight.MEDIUM, t.c("text.primary"));
        if (!amount.isEmpty()) {
            Draw.label(g, amount, tx + Draw.labelWidth(title, 13, Weight.MEDIUM) + 3, y + 7, 13, Weight.REGULAR, t.c("text.muted"));
        }
        Component sub = card.subtitle();
        if (sub != null) {
            float sx = tx;
            if (locked()) {
                Draw.lock(g, Math.round(tx), y + 13, t.c("text.muted"));
                sx += 10;
            }
            Draw.label(g, Draw.ellipsize(sub.getString(), x + width - sx, 11.5f, Weight.REGULAR), sx, y + 16.5f, 11.5f, Weight.REGULAR, t.c("text.muted"));
        }
    }

    private void renderItem(GuiGraphics g, Theme t, El e, int x, int y, int mx, int my) {
        ItemLinks.Part p = e.part;
        boolean progress = p.have() >= 0 && !locked();
        boolean full = progress && p.have() >= p.amount();
        t.slot(g, x, y, ROW, over(mx, my, x, y, ROW, ROW), full ? t.c("slot.have.border") : 0);
        e.stack.render(g, x + 1, y + 1);
        float nx = x + ROW + 5;
        float right = x + width;
        float cy = progress ? y + 6.5f : y + ROW / 2f;
        String total = LinkMath.thousands(p.amount());
        float numW;
        if (progress) {
            String have = LinkMath.thousands(p.have()), rest = " / " + total;
            float restW = Draw.labelWidth(rest, 12, Weight.REGULAR), haveW = Draw.labelWidth(have, 12, Weight.REGULAR);
            Draw.label(g, rest, right - restW, cy, 12, Weight.REGULAR, t.c("text.muted"));
            Draw.label(g, have, right - restW - haveW, cy, 12, Weight.REGULAR, full ? t.c("state.ok") : t.c("text.primary"));
            numW = restW + haveW;
            // полоса: заполненная позиция цветом «есть», остальные цветом «в работе» (янтарь остаётся главной кнопке)
            float barX = nx, barW = right - nx, barY = y + 12.5f;
            Draw.round(g, barX, barY, barW, 2, 1f, t.c("slot.bg"));
            float fill = barW * LinkMath.fraction(p.have(), p.amount());
            if (fill > 0) Draw.round(g, barX, barY, Math.max(2, fill), 2, 1f, full ? t.c("state.ok") : t.c("state.warn"));
        } else {
            numW = Draw.labelWidth(total, 12, Weight.REGULAR);
            Draw.label(g, total, right - numW, cy, 12, Weight.REGULAR, t.c("text.primary"));
        }
        String name = p.label() != null ? p.label().getString() : e.stack.name().getString();
        Draw.label(g, Draw.ellipsize(name, right - numW - 6 - nx, 12, Weight.REGULAR), nx, cy, 12, Weight.REGULAR, t.c("text.primary"));
    }

    private void renderCoin(GuiGraphics g, Theme t, El e, int x, int y) {
        ItemLinks.Part p = e.part;
        Draw.round(g, x + 1, y + 1, 6, 6, 3f, t.c("coin"));
        String label = p.label() != null ? p.label().getString() : LinkMath.thousands(p.amount());
        float lw = Draw.labelWidth(label, 12, Weight.REGULAR);
        Draw.label(g, Draw.ellipsize(label, width - 10, 12, Weight.REGULAR), x + 10, y + LINE / 2f, 12, Weight.REGULAR, t.c("text.primary"));
        if (p.detail() != null && x + 10 + lw + 4 < x + width) {
            Draw.label(g, Draw.ellipsize(p.detail().getString(), width - 14 - lw, 12, Weight.REGULAR), x + 10 + lw + 4, y + LINE / 2f, 12, Weight.REGULAR,
                t.c("text.muted"));
        }
    }

    private static String stateToken(ItemLinks.State state) {
        return switch (state) {
            case DONE -> "state.ok";
            case ACTIVE -> "state.warn";
            case OPEN -> "text.muted";
            case LOCKED -> "text.dim";
        };
    }

    @Override
    @Nullable
    public Stack stackAt(int x, int y, int mx, int my) {
        for (El e : elements) {
            if (e.kind == Kind.HEADER && icon != null && over(mx, my, x, y + e.y, HEAD, HEAD)) return icon;
            if (e.kind == Kind.ITEM && over(mx, my, x, y + e.y, ROW, ROW)) return e.stack;
        }
        return null;
    }

    /** Ссылка под замком или кнопка действия под курсором. */
    @Nullable
    private ItemLinks.Action actionAt(int mx, int my) {
        if (lastX == Integer.MIN_VALUE) return null;
        for (El e : elements) {
            int ey = lastY + e.y;
            if (e.kind == Kind.LINK && over(mx, my, lastX, ey - 1, Math.round(Draw.labelWidth(e.text.getString(), 12, Weight.REGULAR)) + 10, LINE + 2)) {
                return card.lockLink();
            }
            if (e.kind == Kind.BUTTON && over(mx, my, lastX, ey, buttonWidth(e.text.getString()), BUTTON)) return card.open();
        }
        return null;
    }

    /** Для автотеста: место кнопки действия ({@code link == false}) или ссылки под замком в последнем кадре, null если нет. */
    @Nullable
    public int[] actionRectForTest(boolean link) {
        if (lastX == Integer.MIN_VALUE) return null;
        for (El e : elements) {
            int ey = lastY + e.y;
            if (link && e.kind == Kind.LINK) return new int[] {lastX, ey - 1, Math.round(Draw.labelWidth(e.text.getString(), 12, Weight.REGULAR)) + 10, LINE + 2};
            if (!link && e.kind == Kind.BUTTON) return new int[] {lastX, ey, buttonWidth(e.text.getString()), BUTTON};
        }
        return null;
    }

    @Override
    public boolean clickBeforeStack(double mx, double my, int button) {
        if (button != 0) return false;
        ItemLinks.Action action = actionAt((int) mx, (int) my);
        if (action == null) return false;
        Links.run(action);
        return true;
    }

    @Override
    public boolean renderTooltip(GuiGraphics g, int x, int y, int mx, int my) {
        // обрезанный многоточием текст показывает себя целиком; у предметов своя подсказка игры
        if (stackAt(x, y, mx, my) != null) return false;
        Theme t = works.repka.ryscore.theme.ThemeManager.get();
        for (El e : elements) {
            int ey = y + e.y;
            String full = null;
            boolean cut = false;
            if (e.kind == Kind.LOCK && over(mx, my, x, ey, width, CALLOUT)) {
                full = e.text.getString();
                cut = Draw.labelWidth(full, 12, Weight.REGULAR) > width - 22;
            } else if (e.kind == Kind.HEADER && over(mx, my, x + HEAD + 5, ey, width - HEAD - 5, HEAD)) {
                full = card.title().getString() + (card.subtitle() != null ? "\n" + card.subtitle().getString() : "");
                float room = width - HEAD - 5;
                cut = Draw.labelWidth(card.title().getString(), 13, Weight.MEDIUM) > room - 16
                    || card.subtitle() != null && Draw.labelWidth(card.subtitle().getString(), 11.5f, Weight.REGULAR) > room - 10;
            } else if (e.kind == Kind.NOTE && over(mx, my, x, ey, width, LINE)) {
                full = e.text.getString();
                cut = Draw.labelWidth(full, 11.5f, Weight.REGULAR) > width - 9;
            }
            if (full != null) {
                if (!cut) return false;
                Kit.tooltip(g, t, List.of(full.split("\n")), mx, my);
                return true;
            }
        }
        return false;
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
    public boolean isCrafting() {
        return false;
    }

    @Override
    public boolean showsTransfer() {
        return false;
    }
}
