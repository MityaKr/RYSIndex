package works.repka.rysindex.model;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Предмет. */
public final class ItemRes extends Stack {
    public final ItemStack stack;

    public ItemRes(ItemStack stack) {
        this.stack = stack.copyWithCount(1);
        this.amount = Math.max(1, stack.getCount());
    }

    public static ItemRes of(ItemStack stack) {
        return new ItemRes(stack);
    }

    @Override
    public Object key() {
        return stack.getItem();
    }

    @Override
    public String type() {
        return "item";
    }

    @Override
    public ResourceLocation id() {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    @Override
    public Component name() {
        return stack.getHoverName();
    }

    @Override
    public void render(GuiGraphics g, int x, int y) {
        g.renderItem(stack, x, y);
        if (stack.isBarVisible()) {
            g.renderItemDecorations(Minecraft.getInstance().font, stack, x, y, "");
        }
    }

    @Override
    public List<Component> tooltipLines() {
        return Screen.getTooltipFromItem(Minecraft.getInstance(), stack);
    }

    /**
     * Сейчас рисуется подсказка из наших панелей или карточки: модуль загрузчика тогда дописывает в неё мод,
     * если его ещё нет (Jade дописывает сам), как JEI в своём списке.
     */
    public static boolean ourTooltip;

    @Override
    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        ourTooltip = true;
        try {
            g.renderTooltip(Minecraft.getInstance().font, stack, mouseX, mouseY);
        } finally {
            ourTooltip = false;
        }
    }

    @Override
    public List<String> tags() {
        List<String> out = new ArrayList<>();
        stack.getTags().forEach(t -> out.add(t.location().toString()));
        return out;
    }

    @Override
    public boolean same(Stack other) {
        return other instanceof ItemRes o && ItemStack.isSameItemSameComponents(stack, o.stack);
    }

    @Override
    public Stack copy() {
        ItemRes r = new ItemRes(stack);
        r.amount = amount;
        r.chance = chance;
        return r;
    }

    @Override
    public ItemStack item() {
        return stack;
    }

    @Override
    public boolean isEmpty() {
        return stack.isEmpty();
    }

    @Override
    public int hashCode() {
        return ItemStack.hashItemAndComponents(stack);
    }
}
