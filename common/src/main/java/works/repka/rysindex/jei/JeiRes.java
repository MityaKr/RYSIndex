package works.repka.rysindex.jei;

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
import works.repka.rysindex.model.Stack;

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
        String name = helper.getDisplayName(typed.getIngredient());
        // Productive Bees отдаёт имя настраиваемой пчелы как текст объекта: «translation{key='entity.…', args=[]}»
        java.util.regex.Matcher m = RAW_TRANSLATION.matcher(name);
        if (m.find()) return Component.translatable(m.group(1)).append(name.substring(m.end()).replaceFirst("^[^}]*}", ""));
        return Component.literal(name);
    }

    private static final java.util.regex.Pattern RAW_TRANSLATION = java.util.regex.Pattern.compile("^translation\\{key='([^']+)'");

    @Override
    public void render(GuiGraphics g, int x, int y) {
        int ox = (16 - renderer.getWidth()) / 2, oy = (16 - renderer.getHeight()) / 2;
        // чужие рендеры ингредиентов (химикаты Mekanism и др.) рисуют мимо GuiGraphics: накопленные подложки кора
        // должны лечь раньше. Пустая пачка сбрасывается сразу
        works.repka.ryscore.ui.Batch.flush();
        renderer.render(g, typed.getIngredient(), x + ox, y + oy);
    }

    /** Как в JEI: одна строка с модом в конце, если её ещё нет (Jade мог дописать) и настройка включена. */
    @Override
    public List<Component> tooltipLines() {
        List<Component> lines = renderer.getTooltip(typed.getIngredient(), TooltipFlag.NORMAL);
        if (!works.repka.rysindex.runtime.Config.get().showModName) return lines;
        try {
            String mod = works.repka.rysindex.platform.Platform.get().modName(helper.getDisplayModId(typed.getIngredient()));
            return works.repka.rysindex.model.ModNameTooltip.finish(lines, mod);
        } catch (Throwable t) {
            return lines;
        }
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
