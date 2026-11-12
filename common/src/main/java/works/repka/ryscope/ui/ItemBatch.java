package works.repka.ryscope.ui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Пачка иконок предметов за один сброс буфера. {@code GuiGraphics.renderItem} после каждого предмета сбрасывает
 * буфер и заново ставит освещение: на сетке в две сотни ячеек это две сотни отдельных вызовов отрисовки.
 * Здесь предметы копятся в общем буфере, плоские и объёмные рисуются двумя проходами (как пачка JEI).
 *
 * Модель берётся заново в каждом кадре, как в игре: часы и компас продолжают идти.
 * Предметы с особым рендером (сундуки, щиты, предметы с BEWLR) в пачку не берутся: их рисует вызывающий по-старому.
 */
final class ItemBatch {
    private int n;
    private ItemStack[] stacks = new ItemStack[64];
    private BakedModel[] models = new BakedModel[64];
    private float[] xs = new float[64], ys = new float[64], scales = new float[64];

    void clear() {
        for (int i = 0; i < n; i++) {
            stacks[i] = null;
            models[i] = null;
        }
        n = 0;
    }

    boolean isEmpty() {
        return n == 0;
    }

    /**
     * Добавляет иконку 16×16 с левым верхним углом в (x, y) и масштабом {@code scale}.
     * @return false, если предмет нужно нарисовать по одному (особый рендер или пустой стек)
     */
    boolean add(ItemStack stack, float x, float y, float scale) {
        if (stack.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
        if (model.isCustomRenderer()) return false;
        if (n == stacks.length) grow();
        stacks[n] = stack;
        models[n] = model;
        xs[n] = x;
        ys[n] = y;
        scales[n] = scale;
        n++;
        return true;
    }

    private void grow() {
        int size = stacks.length * 2;
        stacks = java.util.Arrays.copyOf(stacks, size);
        models = java.util.Arrays.copyOf(models, size);
        xs = java.util.Arrays.copyOf(xs, size);
        ys = java.util.Arrays.copyOf(ys, size);
        scales = java.util.Arrays.copyOf(scales, size);
    }

    /** Рисует всё накопленное и очищает пачку. */
    void render(GuiGraphics g) {
        if (n == 0) return;
        // то, что уже лежит в буфере (плашки, подсветка), должно лечь под предметы
        g.flush();
        boolean flat = false, lit = false;
        for (int i = 0; i < n; i++) {
            if (models[i].usesBlockLight()) lit = true;
            else flat = true;
        }
        if (flat) {
            Lighting.setupForFlatItems();
            for (int i = 0; i < n; i++) if (!models[i].usesBlockLight()) draw(g, i);
            g.flush();
            Lighting.setupFor3DItems();
        }
        if (lit) {
            for (int i = 0; i < n; i++) if (models[i].usesBlockLight()) draw(g, i);
            g.flush();
        }
        // полоска прочности поверх иконок, как у одиночного предмета
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < n; i++) {
            ItemStack stack = stacks[i];
            if (!stack.isBarVisible()) continue;
            PoseStack pose = g.pose();
            pose.pushPose();
            try {
                pose.translate(xs[i], ys[i], 0);
                if (scales[i] != 1f) pose.scale(scales[i], scales[i], 1f);
                g.renderItemDecorations(mc.font, stack, 0, 0, "");
            } catch (Throwable ignored) {
                // чужой предмет не должен ронять интерфейс
            } finally {
                pose.popPose();
            }
        }
        clear();
    }

    private void draw(GuiGraphics g, int i) {
        PoseStack pose = g.pose();
        pose.pushPose();
        try {
            pose.translate(xs[i], ys[i], 0);
            if (scales[i] != 1f) pose.scale(scales[i], scales[i], 1f);
            // то же преобразование, что в GuiGraphics.renderItem
            pose.translate(8f, 8f, 150f);
            pose.scale(16f, -16f, 16f);
            Minecraft.getInstance().getItemRenderer().render(stacks[i], ItemDisplayContext.GUI, false, pose, g.bufferSource(),
                0xF000F0, OverlayTexture.NO_OVERLAY, models[i]);
        } catch (Throwable ignored) {
            // чужой рендер не должен ронять интерфейс
        } finally {
            pose.popPose();
        }
    }
}
