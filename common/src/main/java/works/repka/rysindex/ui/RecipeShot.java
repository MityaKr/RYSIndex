package works.repka.rysindex.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.model.Category;
import works.repka.rysindex.model.RecipeView;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.UiScale;
import works.repka.ryscore.ui.Type.Weight;

/**
 * Снимок рецепта в PNG (идея EMI): рецепт рисуется во внеэкранный буфер в масштабе интерфейса и ложится в
 * {@code screenshots/rysindex/}. Для Discord и гайдов: не надо вырезать кусок обычного скриншота.
 */
final class RecipeShot {
    private static final int PAD = 6, TITLE_H = 14, MAX_PIXELS = 8192;

    private RecipeShot() {}

    static void take(RecipeView recipe, Category category) {
        Minecraft mc = Minecraft.getInstance();
        Theme t = ThemeManager.get();
        String title = category.title.getString();
        int plate = recipe.needsPlate() ? 3 : 0;
        int titleW = (int) Math.ceil(Draw.labelWidth(title, 12.5f, Weight.MEDIUM));
        int w = Math.max(recipe.width() + plate * 2, titleW) + PAD * 2, h = recipe.height() + plate * 2 + PAD * 2 + TITLE_H;
        double pixels = UiScale.pixels(), gui = mc.getWindow().getGuiScale();
        int pw = (int) Math.ceil(w * pixels), ph = (int) Math.ceil(h * pixels);
        if (pw <= 0 || ph <= 0 || pw > MAX_PIXELS || ph > MAX_PIXELS) {
            Overlay.toast(Component.translatable("rysindex.shot.failed").getString());
            return;
        }
        RenderTarget target = null;
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        boolean pushed = false;
        NativeImage image = null;
        try {
            // накопленное кором относится к главному окну: рисуем его до переключения на буфер снимка
            works.repka.ryscore.ui.Batch.flush();
            target = new TextureTarget(pw, ph, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            // единица GUI игры занимает столько же пикселей, сколько на экране: UiScale.render даёт тот же масштаб
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, (float) (pw / gui), (float) (ph / gui), 0f, 1000f, 21000f),
                VertexSorting.ORTHOGRAPHIC_Z);
            modelView.pushMatrix();
            pushed = true;
            modelView.identity();
            modelView.translate(0f, 0f, -11000f);
            RenderSystem.applyModelViewMatrix();
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            int rx = PAD + plate + (w - PAD * 2 - recipe.width() - plate * 2) / 2, ry = PAD + TITLE_H + plate;
            UiScale.render(g, s -> {
                // сплошной фон: снимок читается без прозрачности
                Draw.rect(s, 0, 0, w, h, t.c("panel.bg"));
                Draw.label(s, title, PAD, PAD + 5, 12.5f, Weight.MEDIUM, t.c("text.primary"));
                if (plate > 0) t.plate(s, rx - plate, ry - plate, recipe.width() + plate * 2, recipe.height() + plate * 2);
                Draw.isolated(s, () -> recipe.render(s, t, rx, ry, -1, -1, 0));
            });
            g.flush();
            image = Screenshot.takeScreenshot(target);
            File dir = new File(mc.gameDirectory, "screenshots/rysindex");
            dir.mkdirs();
            String base = recipe.id() == null ? category.id.getPath() : recipe.id().getNamespace() + "_" + recipe.id().getPath().replace('/', '_');
            File file = new File(dir, base + "_" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date()) + ".png");
            image.writeToFile(file);
            Component link = Component.literal(file.getName()).withStyle(ChatFormatting.UNDERLINE)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath())));
            mc.gui.getChat().addMessage(Component.translatable("rysindex.shot.saved", link));
            Overlay.toast(Component.translatable("rysindex.shot.toast").getString());
        } catch (Throwable e) {
            RYSIndex.LOG.warn("Снимок рецепта не сохранён: {}", e.toString());
            Overlay.toast(Component.translatable("rysindex.shot.failed").getString());
        } finally {
            if (image != null) image.close();
            if (pushed) {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            if (target != null) target.destroyBuffers();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }
}
