package works.repka.ryscope.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.vanilla.IJeiIngredientInfoRecipe;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.content.HelpContent;
import works.repka.ryscope.content.HelpPage;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscore.theme.Theme;

/**
 * «Сведения» JEI ({@code addInformation}) страницей справки нашим видом вместо окна JEI с моноширинным текстом
 * в рамке со своей прокруткой. Каждая строка описания JEI это абзац; цвет и жирность чужого текста становятся
 * выделенным тоном темы. Показывается во вкладке «Справка» после наших страниц.
 */
public final class JeiInfoView implements RecipeView {
    private final List<Stack> targets;
    private final List<Ing> inputs;
    private final HelpPage page;

    private JeiInfoView(List<Stack> targets, List<HelpPage.Block> blocks) {
        this.targets = targets;
        this.inputs = List.of(new Ing(targets, null));
        this.page = new HelpPage(targets, blocks);
    }

    /** Вид для рецепта JEI, если это сведения о предмете, иначе {@code null}. */
    @Nullable
    static JeiInfoView of(JeiBridge bridge, Object recipe) {
        if (!(recipe instanceof IJeiIngredientInfoRecipe info)) return null;
        List<Stack> targets = new ArrayList<>();
        for (ITypedIngredient<?> typed : info.getIngredients()) {
            Stack s = bridge.fromTyped(typed);
            if (s != null && targets.stream().noneMatch(s::same)) targets.add(s);
        }
        if (targets.isEmpty()) return null;
        List<HelpPage.Block> blocks = new ArrayList<>();
        for (FormattedText line : info.getDescription()) {
            List<HelpPage.Span> spans = spans(line);
            boolean blank = spans.stream().allMatch(s -> s.text().isBlank());
            blocks.add(blank ? HelpPage.Block.gap() : HelpPage.Block.paragraph(spans, List.of()));
        }
        // пустые строки по краям ничего не значат
        while (!blocks.isEmpty() && blocks.get(0).spans().isEmpty()) blocks.remove(0);
        while (!blocks.isEmpty() && blocks.get(blocks.size() - 1).spans().isEmpty()) blocks.remove(blocks.size() - 1);
        if (blocks.isEmpty()) return null;
        return new JeiInfoView(targets, blocks);
    }

    private static List<HelpPage.Span> spans(FormattedText line) {
        List<HelpPage.Span> out = new ArrayList<>();
        line.visit((Style style, String text) -> {
            String plain = ChatFormatting.stripFormatting(text);
            if (plain != null && !plain.isEmpty()) {
                boolean strong = style.getColor() != null || style.isBold();
                out.add(new HelpPage.Span(plain, strong ? HelpPage.Tone.STRONG : HelpPage.Tone.BODY));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /** Текст сведений как его видит игрок: для автотеста. */
    public String plainText() {
        return page.plainText();
    }

    @Override
    public ResourceLocation id() {
        return null;
    }

    @Override
    public int width() {
        return HelpPage.WIDTH;
    }

    @Override
    public int height() {
        return page.height();
    }

    @Override
    public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick) {
        page.render(g, theme, x, y, mouseX, mouseY, HelpContent.focus());
    }

    @Override
    public Stack stackAt(int x, int y, int mouseX, int mouseY) {
        return page.stackAt(x, y, mouseX, mouseY, HelpContent.focus());
    }

    @Override
    public List<Ing> inputs() {
        return inputs;
    }

    @Override
    public List<Stack> outputs() {
        return targets;
    }

    @Override
    public boolean isCrafting() {
        return false;
    }
}
