package works.repka.ryscope.model;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Ресурс первого класса: предмет, жидкость или ингредиент чужого мода.
 * У каждого вида свой рендер и своя подсказка, остальной код работает с этим классом.
 */
public abstract class Stack {
    public long amount = 1;
    /** Шанс получения, 1 = всегда. */
    public float chance = 1f;

    /** Грубая идентичность без учёта количества и компонентов: Item, Fluid или строка. */
    public abstract Object key();

    /** Вид ресурса: "item", "fluid" или id типа ингредиента чужого мода. */
    public abstract String type();

    public abstract ResourceLocation id();

    public abstract Component name();

    /** Иконка 16×16. */
    public abstract void render(GuiGraphics g, int x, int y);

    public abstract List<Component> tooltipLines();

    public abstract List<String> tags();

    /** Точное сравнение с учётом компонентов, без учёта количества. */
    public abstract boolean same(Stack other);

    public abstract Stack copy();

    /** Предмет, если ресурс им является, иначе пустой стек. */
    public ItemStack item() {
        return ItemStack.EMPTY;
    }

    public boolean isEmpty() {
        return false;
    }

    public String namespace() {
        return id().getNamespace();
    }

    /** Количество и шанс поверх иконки. */
    public void renderOverlay(GuiGraphics g, int x, int y) {
        Font font = Minecraft.getInstance().font;
        if (amount != 1) {
            String text = formatAmount();
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            g.drawString(font, text, x + 17 - font.width(text), y + 9, 0xFFFFFFFF, true);
            g.pose().popPose();
        }
        renderChance(g, x, y);
    }

    /** Шанс получения: процент в углу, а не только цвет или мерцание. */
    protected void renderChance(GuiGraphics g, int x, int y) {
        if (chance >= 0.999f) return;
        Font font = Minecraft.getInstance().font;
        float p = chance * 100f;
        String text = p >= 10f ? Math.round(p) + "%" : p >= 1f ? String.format(java.util.Locale.ROOT, "%.1f%%", p) : "<1%";
        g.pose().pushPose();
        g.pose().translate(x - 1, y - 1, 200);
        g.pose().scale(0.6f, 0.6f, 1f);
        g.fill(-1, -1, font.width(text) + 1, 9, 0xC0000000);
        g.drawString(font, text, 0, 0, 0xFFFFD166, false);
        g.pose().popPose();
    }

    public String formatAmount() {
        if (amount >= 1_000_000) return (amount / 1_000_000) + "M";
        if (amount >= 10_000) return (amount / 1000) + "k";
        return Long.toString(amount);
    }

    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        g.renderComponentTooltip(Minecraft.getInstance().font, tooltipLines(), mouseX, mouseY);
    }

    public Stack withAmount(long amount) {
        Stack s = copy();
        s.amount = amount;
        return s;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Stack s && same(s);
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
