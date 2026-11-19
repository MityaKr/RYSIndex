package works.repka.rysindex.ui;

import java.util.function.Supplier;
import net.minecraft.client.renderer.ShaderInstance;
import works.repka.rysindex.runtime.Config;
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
    private static int[] anchors = new int[0];

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
        anchors = new int[] {theme.c("slot.border"), theme.c("slot.border"), theme.c("slot.bg"), theme.c("card.bg"), theme.c("card.border")};
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
        if (replacement == null) return original;
        // токены темы могут быть полупрозрачными (Glass): без смешивания они становятся чистым чёрным и белым
        return () -> {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            return replacement;
        };
    }

    /** Фон, который мод рисует сам через {@code RenderSystem.setShader}, минуя blit: так делает Just Enough Resources. */
    public static Supplier<ShaderInstance> direct(Supplier<ShaderInstance> original) {
        if (!active) return original;
        ShaderInstance chosen = original.get();
        if (chosen != null && chosen == net.minecraft.client.renderer.GameRenderer.getPositionTexShader()) return shader(original, false);
        if (chosen != null && chosen == net.minecraft.client.renderer.GameRenderer.getPositionTexColorShader()) return shader(original, true);
        return () -> chosen;
    }

    /**
     * Сплошная серая заливка мода: те же опорные оттенки, что в шейдере. Полупрозрачные заливки
     * (подсветка слота под курсором) остаются как есть.
     */
    public static int fill(int argb) {
        if (!active || (argb >>> 24) < 200) return argb;
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) >= 23) return argb;
        float l = (r + g + b) / 765f;
        if (l < 0.216f) return mix(anchors[0], anchors[1], l / 0.216f);
        if (l < 0.545f) return mix(anchors[1], anchors[2], (l - 0.216f) / 0.329f);
        if (l < 0.776f) return mix(anchors[2], anchors[3], (l - 0.545f) / 0.231f);
        // светлая панель мода становится фоном карточки, к цвету рамки уходят только почти белые грани
        if (l < 0.95f) return anchors[3];
        return mix(anchors[3], anchors[4], (l - 0.95f) / 0.05f);
    }

    private static int mix(int from, int to, float t) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            out |= Math.round(a + (b - a) * t) << shift;
        }
        return out;
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
