package works.repka.ryscope.ui;

import java.util.function.Supplier;
import net.minecraft.client.renderer.ShaderInstance;
import works.repka.ryscope.runtime.Config;
import works.repka.ryscore.theme.Theme;

/**
 * Перекраска окон рецептов модов под тему. JEI рисует их текстурами в сером стиле Minecraft, и у каждого мода
 * свои. Перерисовать сотни текстур нельзя, поэтому на время рисования рецепта текстуры идут через шейдер:
 * серые оттенки заменяются цветами темы, а всё цветное (стрелки, шкалы, жидкости) остаётся как есть.
 * Тёмные надписи заодно становятся светлыми, иначе на тёмном фоне их не видно.
 */
public final class RecipeRestyle {
    private static ShaderInstance plain;
    private static ShaderInstance colored;
    private static boolean active;
    private static int textColor;

    private RecipeRestyle() {}

    public static void setPlain(ShaderInstance shader) {
        plain = shader;
    }

    public static void setColored(ShaderInstance shader) {
        colored = shader;
    }

    /** @return true, если перекраска включена; после рисования нужно вызвать {@link #end()}. */
    public static boolean begin(Theme theme) {
        if (!Config.get().restyleRecipes || theme.beveled() || plain == null || colored == null) return false;
        // опорные оттенки серого у Minecraft: контур, тёмная грань слота, слот, фон окна, светлая грань
        int[] anchors = {theme.c("slot.border"), theme.c("slot.border"), theme.c("slot.bg"), theme.c("card.bg"), theme.c("card.border")};
        for (ShaderInstance shader : new ShaderInstance[] {plain, colored}) {
            for (int i = 0; i < anchors.length; i++) {
                int c = anchors[i];
                shader.safeGetUniform("C" + i).set(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, ((c >>> 24) & 255) / 255f);
            }
        }
        textColor = theme.c("text.body") & 0xFFFFFF;
        active = true;
        return true;
    }

    public static void end() {
        active = false;
    }

    public static Supplier<ShaderInstance> shader(Supplier<ShaderInstance> original, boolean withColor) {
        if (!active) return original;
        ShaderInstance replacement = withColor ? colored : plain;
        return replacement == null ? original : () -> replacement;
    }

    /** Тёмный бесцветный текст становится цветом текста темы, цветной не меняется. */
    public static int text(int argb) {
        if (!active) return argb;
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min > 30 || max > 140) return argb;
        return (argb & 0xFF000000) | textColor;
    }
}
