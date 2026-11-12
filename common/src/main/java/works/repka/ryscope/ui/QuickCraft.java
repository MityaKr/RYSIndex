package works.repka.ryscope.ui;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.RecipeView.Transfer;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Foreign;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.runtime.RecipeDefaults;
import works.repka.ryscope.runtime.RecipeMark;
import works.repka.ryscope.runtime.RecipeRef;

/**
 * Быстрый крафт (идея EMI): клавиша над предметом раскладывает его рецепт в сетку открытого окна,
 * не открывая карточку. Берётся рецепт из закладки на рецепт, рецепт по умолчанию или первый, который
 * раскладывается из того, что есть. Работает везде, куда работает «+»: верстак, печь, терминал AE2.
 */
final class QuickCraft {
    /** Сколько рецептов проверять переносом: проверка рецептов модов не бесплатна. */
    private static final int MAX_TRIES = 48;

    private QuickCraft() {}

    /** @return обработана ли клавиша */
    static boolean run(Screen screen, Stack stack, boolean max) {
        AbstractContainerScreen<?> host = screen instanceof RecipeScreen rs ? rs.containerParent()
            : screen instanceof AbstractContainerScreen<?> acs ? acs : null;
        if (host == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu != host.getMenu()) return false;
        RecipeView recipe = pick(stack, host);
        if (recipe == null) {
            Overlay.toast(Component.translatable("ryscope.craft.none", stack.name().getString()).getString());
            return true;
        }
        Transfer result;
        try {
            result = recipe.transfer(host, max, false);
        } catch (Throwable t) {
            result = Transfer.missing("ryscope.transfer.failed");
        }
        if (result.applicable()) {
            if (mc.screen != host) mc.setScreen(host);
        } else {
            String message = result.message() == null ? "ryscope.transfer.missing" : result.message();
            Overlay.toast(Component.translatable(message).getString());
        }
        return true;
    }

    @Nullable
    private static RecipeView pick(Stack stack, AbstractContainerScreen<?> host) {
        if (stack instanceof RecipeMark mark) return mark.recipe();
        List<Lookup.Group> groups = Lookup.find(stack.withAmount(1), Lookup.Mode.RECIPES);
        RecipeRef preferred = RecipeDefaults.get(stack);
        if (preferred != null) {
            for (Lookup.Group g : groups) {
                for (RecipeView r : g.recipes()) if (preferred.matches(g.category(), r) && fits(host, r)) return r;
            }
        }
        // полностью собираемый лучше частичного, частичный лучше «не хватает»: тогда хотя бы видно, чего
        RecipeView partial = null, missing = null;
        int tries = 0;
        for (Lookup.Group g : groups) {
            if (!transfers(host, g)) continue;
            for (RecipeView r : g.recipes()) {
                if (!r.isCrafting() || tries++ >= MAX_TRIES) continue;
                Transfer t;
                try {
                    t = r.transfer(host, false, true);
                } catch (Throwable e) {
                    continue;
                }
                if (t.status() == Transfer.Status.OK) return r;
                if (t.status() == Transfer.Status.PARTIAL && partial == null) partial = r;
                if (t.status() == Transfer.Status.MISSING && missing == null) missing = r;
            }
        }
        return partial != null ? partial : missing;
    }

    private static boolean fits(AbstractContainerScreen<?> host, RecipeView r) {
        try {
            return r.transfer(host, false, true).status() != Transfer.Status.UNSUPPORTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Категория вообще переносится в это окно: без этой проверки перебирались бы сотни чужих рецептов. */
    private static boolean transfers(AbstractContainerScreen<?> host, Lookup.Group g) {
        if (g.recipes().isEmpty()) return false;
        RecipeView first = g.recipes().get(0);
        Foreign.Source foreign = Foreign.get();
        if (!(first instanceof works.repka.ryscope.content.NativeRecipe) && foreign != null) {
            try {
                Boolean known = foreign.transfersTo(host.getMenu(), g.category());
                if (known != null) return known;
            } catch (Throwable ignored) {
                // спросим сам рецепт
            }
        }
        return fits(host, first);
    }
}
