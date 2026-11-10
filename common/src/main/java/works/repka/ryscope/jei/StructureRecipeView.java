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

    private final ResourceLocation id;
    private final BlockScene scene;
    private final List<Ing> inputs;
    private final int layers;
    @Nullable
    private SceneView view;
    private long drawnAt;
    private int lastX, lastY;
    private boolean rotating;

    private StructureRecipeView(ResourceLocation id, BlockScene scene, List<Ing> inputs) {
        this.id = id;
        this.scene = scene;
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
        for (Map.Entry<BlockPos, ?> e : blocks.entrySet()) {
            List<?> options = (List<?>) e.getValue().getClass().getMethod("getAll").invoke(e.getValue());
            if (options.isEmpty()) continue;
            Object first = options.get(0);
            BlockState state = (BlockState) first.getClass().getMethod("getBlockState").invoke(first);
            if (state != null) states.put(e.getKey(), state);
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
        return new StructureRecipeView(name.withPrefix("multiblock_preview/"), BlockScene.of(states), inputs);
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
            LIVE.add(this);
        }
        if (t.beveled()) t.plate(g, x, y, WIDTH, SCENE_H);
        else Draw.roundBox(g, x, y, WIDTH, SCENE_H, 4f, t.c("slot.bg"), t.c("panel.border"));
        view.render(g, x, y, WIDTH, SCENE_H);
        renderControls(g, t, x, y, mouseX, mouseY);
        renderSlots(g, t, x, y + SCENE_H + GAP * 2, mouseX, mouseY, now);
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
            Stack shown = inputs.get(i).shown(now);
            if (shown == null) continue;
            shown.render(g, sx + 1, sy + 1);
            shown.renderScaledAmount(g, sx, sy, SLOT);
        }
    }

    @Nullable
    @Override
    public Stack stackAt(int x, int y, int mouseX, int mouseY) {
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
