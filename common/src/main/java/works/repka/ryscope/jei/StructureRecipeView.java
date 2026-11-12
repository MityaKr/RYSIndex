package works.repka.ryscope.jei;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscore.scene.BlockScene;
import works.repka.ryscore.scene.SceneView;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Type.Weight;

/**
 * Превью многоблочной структуры нашим видом из кора вместо чужого окна внутри рецепта.
 * Мышью структура вращается, колесом приближается, стрелками показывается по слоям.
 */
public final class StructureRecipeView implements RecipeView {
    private static final String MMR_RECIPE = "es.degrassi.mmreborn.common.integration.xei.MultiblockRecipe";
    private static final int WIDTH = 200, SCENE_H = 132, SLOT = 18, GAP = 2, COLUMNS = (WIDTH + GAP) / (SLOT + GAP), MAX_ROWS = 4;
    private static final int ARROW = 6, CONTROL_X = 6, CONTROL_Y = 6, LABEL_W = 24;
    private static final long IDLE_MS = 5000L;
    private static final Set<StructureRecipeView> LIVE = Collections.newSetFromMap(new IdentityHashMap<>());

    private static final long CYCLE_MS = 1500L;

    private final ResourceLocation id;
    private final BlockScene scene;
    /** Все подходящие блоки по позициям: где их несколько, модель перебирает варианты, как в JEI. */
    private final Map<BlockPos, List<BlockState>> variants;
    private long shownStep;
    private final List<Ing> inputs;
    private final int layers;
    @Nullable
    private SceneView view;
    private long drawnAt;
    private int lastX, lastY;
    private boolean rotating;
    /** Ячейка, у которой раскрыты все варианты блока, или -1. */
    private int openIndex = -1;
    private static final int POPUP_COLUMNS = 8, POPUP_PAD = 4;

    private StructureRecipeView(ResourceLocation id, BlockScene scene, Map<BlockPos, List<BlockState>> variants, List<Ing> inputs) {
        this.id = id;
        this.scene = scene;
        this.variants = variants;
        this.inputs = inputs;
        this.layers = scene.size().getY();
    }

    /** Вид для рецепта JEI, если это известное превью структуры, иначе {@code null}: тогда рецепт рисует JEI. */
    @Nullable
    public static StructureRecipeView of(Object recipe) {
        if (!MMR_RECIPE.equals(recipe.getClass().getName())) return null;
        try {
            return ofMmr(recipe);
        } catch (ReflectiveOperationException | RuntimeException e) {
            RYScope.LOG.warn("Превью структуры Modular Machinery не прочитано: {}", e.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static StructureRecipeView ofMmr(Object recipe) throws ReflectiveOperationException {
        Field field = recipe.getClass().getDeclaredField("multiblock");
        field.setAccessible(true);
        Object machine = field.get(recipe);
        Object pattern = machine.getClass().getMethod("getPattern").invoke(machine);
        Map<BlockPos, ?> blocks = (Map<BlockPos, ?>) pattern.getClass().getMethod("getBlocksFiltered", Direction.class).invoke(pattern, Direction.NORTH);
        Map<BlockPos, BlockState> states = new HashMap<>();
        Map<BlockPos, List<BlockState>> variants = new HashMap<>();
        for (Map.Entry<BlockPos, ?> e : blocks.entrySet()) {
            List<?> options = (List<?>) e.getValue().getClass().getMethod("getAll").invoke(e.getValue());
            List<BlockState> found = new ArrayList<>();
            for (Object option : options) {
                BlockState state = (BlockState) option.getClass().getMethod("getBlockState").invoke(option);
                if (state != null && !found.contains(state)) found.add(state);
            }
            if (found.isEmpty()) continue;
            states.put(e.getKey(), found.get(0));
            if (found.size() > 1) variants.put(e.getKey(), found);
        }
        Block controller = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("modular_machinery_reborn", "controller"));
        if (controller != Blocks.AIR) states.put(BlockPos.ZERO, controller.defaultBlockState());

        List<Ing> inputs = new ArrayList<>();
        for (List<ItemStack> stacks : (List<List<ItemStack>>) machine.getClass().getMethod("getStacks").invoke(machine)) {
            List<Stack> options = new ArrayList<>();
            for (ItemStack stack : stacks) if (!stack.isEmpty()) options.add(new ItemRes(stack));
            if (!options.isEmpty()) inputs.add(new Ing(options, null));
        }
        ResourceLocation name = (ResourceLocation) machine.getClass().getMethod("getRegistryName").invoke(machine);
        // BlockScene сдвигает структуру к нулю по непустым блокам: так же сдвигаются позиции вариантов
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (Map.Entry<BlockPos, BlockState> e : states.entrySet()) {
            if (e.getValue().isAir()) continue;
            minX = Math.min(minX, e.getKey().getX());
            minY = Math.min(minY, e.getKey().getY());
            minZ = Math.min(minZ, e.getKey().getZ());
        }
        Map<BlockPos, List<BlockState>> shifted = new HashMap<>();
        if (minX != Integer.MAX_VALUE) {
            BlockPos min = new BlockPos(minX, minY, minZ);
            for (Map.Entry<BlockPos, List<BlockState>> e : variants.entrySet()) shifted.put(e.getKey().subtract(min), e.getValue());
        }
        return new StructureRecipeView(name.withPrefix("multiblock_preview/"), BlockScene.of(states), Map.copyOf(shifted), inputs);
    }

    /** Освобождает буферы всех превью: окно рецептов закрыто. */
    public static void releaseAll() {
        for (StructureRecipeView v : LIVE) v.free();
        LIVE.clear();
    }

    private static void releaseIdle(long now) {
        for (Iterator<StructureRecipeView> it = LIVE.iterator(); it.hasNext(); ) {
            StructureRecipeView v = it.next();
            if (now - v.drawnAt > IDLE_MS) {
                v.free();
                it.remove();
            }
        }
    }

    private void free() {
        if (view != null) view.close();
        view = null;
    }

    private int rows() {
        return Math.min(MAX_ROWS, (inputs.size() + COLUMNS - 1) / COLUMNS);
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        int rows = rows();
        return SCENE_H + (rows == 0 ? 0 : GAP * 2 + rows * (SLOT + GAP) - GAP);
    }

    @Override
    public boolean isCrafting() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, Theme t, int x, int y, int mouseX, int mouseY, float partialTick) {
        lastX = x;
        lastY = y;
        long now = System.currentTimeMillis();
        drawnAt = now;
        releaseIdle(now);
        if (view == null) {
            view = new SceneView(scene);
            shownStep = 0;
            LIVE.add(this);
        }
        cycleVariants(now);
        if (t.beveled()) t.plate(g, x, y, WIDTH, SCENE_H);
        else Draw.roundBox(g, x, y, WIDTH, SCENE_H, 4f, t.c("slot.bg"), t.c("panel.border"));
        view.render(g, x, y, WIDTH, SCENE_H);
        renderControls(g, t, x, y, mouseX, mouseY);
        renderSlots(g, t, x, y + SCENE_H + GAP * 2, mouseX, mouseY, now);
        renderPopup(g, t, mouseX, mouseY);
    }

    /** Левый верхний угол ячейки с этим номером. */
    private int[] slotPos(int index) {
        int top = lastY + SCENE_H + GAP * 2;
        return new int[] {lastX + index % COLUMNS * (SLOT + GAP), top + index / COLUMNS * (SLOT + GAP)};
    }

    /** Панель вариантов над раскрытой ячейкой: x, y, w, h, столбцов. */
    private int[] popupRect() {
        int n = inputs.get(openIndex).options.size();
        int cols = Math.min(POPUP_COLUMNS, n), rows = (n + cols - 1) / cols;
        int w = cols * (SLOT + GAP) - GAP + POPUP_PAD * 2, h = rows * (SLOT + GAP) - GAP + POPUP_PAD * 2;
        int[] slot = slotPos(openIndex);
        int px = Math.max(lastX, Math.min(lastX + WIDTH - w, slot[0] + SLOT / 2 - w / 2));
        int py = Math.max(lastY, slot[1] - h - 3);
        return new int[] {px, py, w, h, cols};
    }

    @Nullable
    private Stack popupStackAt(double mx, double my) {
        if (openIndex < 0) return null;
        int[] r = popupRect();
        List<Stack> options = inputs.get(openIndex).options;
        for (int i = 0; i < options.size(); i++) {
            int sx = r[0] + POPUP_PAD + i % r[4] * (SLOT + GAP), sy = r[1] + POPUP_PAD + i / r[4] * (SLOT + GAP);
            if (mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT) return options.get(i);
        }
        return null;
    }

    private void renderPopup(GuiGraphics g, Theme t, int mx, int my) {
        if (openIndex < 0 || openIndex >= inputs.size()) return;
        int[] r = popupRect();
        List<Stack> options = inputs.get(openIndex).options;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        t.tooltipBox(g, r[0], r[1], r[2], r[3]);
        for (int i = 0; i < options.size(); i++) {
            int sx = r[0] + POPUP_PAD + i % r[4] * (SLOT + GAP), sy = r[1] + POPUP_PAD + i / r[4] * (SLOT + GAP);
            t.slot(g, sx, sy, SLOT, mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT);
            options.get(i).render(g, sx + 1, sy + 1);
        }
        g.pose().popPose();
    }

    @Override
    public boolean clickBeforeStack(double mx, double my, int button) {
        if (button != 0) return false;
        if (openIndex >= 0) {
            int[] r = popupRect();
            boolean inside = mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
            // вариант в панели открывает свои рецепты обычным кликом окна
            if (inside) return popupStackAt(mx, my) == null;
        }
        int index = slotIndexAt(mx, my);
        if (index >= 0 && inputs.get(index).options.size() > 1) {
            openIndex = openIndex == index ? -1 : index;
            return true;
        }
        if (openIndex >= 0) {
            openIndex = -1;
            return true;
        }
        return false;
    }

    private int slotIndexAt(double mouseX, double mouseY) {
        int top = lastY + SCENE_H + GAP * 2;
        int dx = (int) mouseX - lastX, dy = (int) mouseY - top;
        if (dx < 0 || dy < 0 || dx % (SLOT + GAP) >= SLOT || dy % (SLOT + GAP) >= SLOT) return -1;
        int column = dx / (SLOT + GAP), index = dy / (SLOT + GAP) * COLUMNS + column;
        if (column >= COLUMNS || index >= Math.min(inputs.size(), COLUMNS * MAX_ROWS)) return -1;
        return index;
    }

    /** Следующий вариант блоков там, где подходят несколько: модель сменяется без мигания. */
    private void cycleVariants(long now) {
        if (variants.isEmpty() || view == null) return;
        long step = now / CYCLE_MS;
        if (step == shownStep) return;
        shownStep = step;
        Map<BlockPos, BlockState> states = new HashMap<>(scene.blocks());
        for (Map.Entry<BlockPos, List<BlockState>> e : variants.entrySet()) {
            List<BlockState> options = e.getValue();
            states.put(e.getKey(), options.get((int) (step % options.size())));
        }
        view.setScene(BlockScene.of(states));
    }

    private void renderControls(GuiGraphics g, Theme t, int x, int y, int mx, int my) {
        int left = x + CONTROL_X, top = y + CONTROL_Y, right = left + ARROW + LABEL_W + 4;
        Icons.draw(g, Icons.Shape.CHEVRON_LEFT, left, top, ARROW, inZone(mx, my, left) ? t.c("accent") : t.c("button.fg"));
        Icons.draw(g, Icons.Shape.CHEVRON_RIGHT, right, top, ARROW, inZone(mx, my, right) ? t.c("accent") : t.c("button.fg"));
        Integer layer = view == null ? null : view.layer();
        String label = (layer == null ? "–" : Integer.toString(layer + 1)) + "/" + layers;
        float labelW = Draw.labelWidth(label, 11.5f, Weight.REGULAR);
        Draw.label(g, label, left + ARROW + 2 + (LABEL_W - labelW) / 2f, top + 3.5f, 11.5f, Weight.REGULAR, t.c("text.muted"));
    }

    private boolean inZone(double mx, double my, int arrowX) {
        return mx >= arrowX - 2 && mx < arrowX + ARROW + 2 && my >= lastY + CONTROL_Y - 2 && my < lastY + CONTROL_Y + ARROW + 2;
    }

    private void renderSlots(GuiGraphics g, Theme t, int x, int top, int mx, int my, long now) {
        int count = Math.min(inputs.size(), COLUMNS * MAX_ROWS);
        for (int i = 0; i < count; i++) {
            int sx = x + i % COLUMNS * (SLOT + GAP), sy = top + i / COLUMNS * (SLOT + GAP);
            t.slot(g, sx, sy, SLOT, mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT);
            Stack shown = inputs.get(i).shown(System.currentTimeMillis());
            if (shown == null) continue;
            shown.render(g, sx + 1, sy + 1);
            shown.renderScaledAmount(g, sx, sy, SLOT);
            if (inputs.get(i).options.size() > 1) {
                // несколько вариантов блока: клик раскрывает их все
                g.pose().pushPose();
                g.pose().translate(0, 0, 250);
                Draw.rect(g, sx + 1, sy + SLOT - 3, 2, 2, t.c("accent"));
                g.pose().popPose();
            }
        }
    }

    @Nullable
    @Override
    public Stack stackAt(int x, int y, int mouseX, int mouseY) {
        Stack variant = popupStackAt(mouseX, mouseY);
        if (variant != null) return variant;
        if (openIndex >= 0) {
            int[] r = popupRect();
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) return null;
        }
        int top = y + SCENE_H + GAP * 2;
        int dx = mouseX - x, dy = mouseY - top;
        if (dx < 0 || dy < 0 || dx % (SLOT + GAP) >= SLOT || dy % (SLOT + GAP) >= SLOT) return null;
        int column = dx / (SLOT + GAP), index = dy / (SLOT + GAP) * COLUMNS + column;
        if (column >= COLUMNS || index >= Math.min(inputs.size(), COLUMNS * MAX_ROWS)) return null;
        return inputs.get(index).shown(System.currentTimeMillis());
    }

    private boolean overScene(double mx, double my) {
        return mx >= lastX && mx < lastX + WIDTH && my >= lastY && my < lastY + SCENE_H;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || view == null || !overScene(mx, my)) return false;
        int left = lastX + CONTROL_X, right = left + ARROW + LABEL_W + 4;
        rotating = false;
        if (inZone(mx, my, left)) {
            stepLayer(-1);
        } else if (inZone(mx, my, right)) {
            stepLayer(1);
        } else {
            rotating = true;
        }
        return true;
    }

    /** По кругу: вся сцена, затем слои снизу вверх. */
    private void stepLayer(int by) {
        Integer layer = view.layer();
        int position = (layer == null ? 0 : layer + 1) + by;
        int states = layers + 1;
        position = (position % states + states) % states;
        view.setLayer(position == 0 ? null : Integer.valueOf(position - 1));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!rotating || view == null) return false;
        view.drag(dx, dy);
        return true;
    }

    @Override
    public void mouseReleased(double mx, double my, int button) {
        rotating = false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (view == null || !overScene(mx, my)) return false;
        view.scroll(scrollY);
        return true;
    }

    @Override
    public List<Ing> inputs() {
        return inputs;
    }

    @Override
    public List<Stack> outputs() {
        return List.of();
    }
}
