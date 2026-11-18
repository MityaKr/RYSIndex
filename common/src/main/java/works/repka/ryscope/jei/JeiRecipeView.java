package works.repka.ryscope.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscore.theme.Theme;

/**
 * Рецепт из JEI. Рисует его сам JEI (раскладка, фон, анимации, подсказки категории),
 * поэтому рецепты модов выглядят и ведут себя так, как задумал автор плагина.
 */
public final class JeiRecipeView<T> implements RecipeView {
    private final JeiBridge bridge;
    private final IRecipeCategory<T> category;
    private final T recipe;
    private final IFocusGroup focus;
    private IRecipeLayoutDrawable<T> layout;
    private boolean failed;
    private int border = 4;
    /** Насколько ячейки рецепта выходят за размер категории: слева, сверху, справа, снизу. */
    private int padLeft, padTop, padRight, padBottom;
    /** Насколько ячейка может отстоять от края категории и всё ещё растягивать окошко рецепта. */
    private static final int SLOT_REACH = 32;
    /** Масштаб рецепта в окне ({@link JeiScale}) и для каких пикселей экрана в единице он посчитан. */
    private double scale = 1;
    private double scaleKey = -1;
    private List<Ing> inputs;
    private List<Stack> outputs;
    /** Ответ обработчика переноса: сам рисует на рецепте, чего не хватает. */
    private IRecipeTransferError transferError;

    public JeiRecipeView(JeiBridge bridge, IRecipeCategory<T> category, T recipe, IFocusGroup focus) {
        this.bridge = bridge;
        this.category = category;
        this.recipe = recipe;
        this.focus = focus;
    }

    @Nullable
    private IRecipeLayoutDrawable<T> layout() {
        if (layout == null && !failed) {
            try {
                Optional<IRecipeLayoutDrawable<T>> created = bridge.runtime.getRecipeManager().createRecipeLayoutDrawable(category, recipe, focus);
                if (created.isPresent()) {
                    layout = created.get();
                    Rect2i inner = layout.getRect(), outer = layout.getRectWithBorder();
                    border = Math.max(0, (outer.getWidth() - inner.getWidth()) / 2);
                    measureSlots(layout);
                    scaleKey = -1;
                    // раскладка живёт в своих единицах от угла рецепта; на экран её переводит поза в render
                    layout.setPosition(border + padLeft, border + padTop);
                } else {
                    failed = true;
                }
            } catch (Throwable t) {
                failed = true;
                RYScope.LOG.debug("JEI не смог построить рецепт {}: {}", category.getRecipeType().getUid(), t.toString());
            }
        }
        return layout;
    }

    /**
     * Ячейки, которые мод ставит за край своей категории (шкала энергии Modular Machinery Reborn выше, чем рецепт задал себе
     * высоты), растягивают окошко рецепта, а не вылезают из него.
     * <p>
     * Ячейки далеко за краем не считаются: так моды прячут ингредиенты для поиска. JEI Mekanism Multiblocks ставит все
     * затраты калькулятора в точку (9999, 9999), чтобы блоки находились в «Использованиях», и окошко растягивалось на весь
     * экран. Ячейка, которая не касается рецепта даже с запасом {@link #SLOT_REACH}, на экране не видна и в размер не идёт.
     */
    private void measureSlots(IRecipeLayoutDrawable<T> l) {
        int w = category.getWidth(), h = category.getHeight();
        int left = 0, top = 0, right = w, bottom = h;
        try {
            for (IRecipeSlotView slot : l.getRecipeSlotsView().getSlotViews()) {
                if (!(slot instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable drawable)) continue;
                Rect2i r = drawable.getRect();
                if (r.getX() >= w + SLOT_REACH || r.getY() >= h + SLOT_REACH
                    || r.getX() + r.getWidth() <= -SLOT_REACH || r.getY() + r.getHeight() <= -SLOT_REACH) continue;
                left = Math.min(left, r.getX());
                top = Math.min(top, r.getY());
                right = Math.max(right, r.getX() + r.getWidth());
                bottom = Math.max(bottom, r.getY() + r.getHeight());
            }
        } catch (Throwable ignored) {
            // чужие ячейки: остаётся размер категории
        }
        padLeft = -left;
        padTop = -top;
        padRight = right - w;
        padBottom = bottom - h;
    }

    @Override
    public ResourceLocation id() {
        try {
            return category.getRegistryName(recipe);
        } catch (Throwable t) {
            return null;
        }
    }

    private int nativeWidth() {
        return category.getWidth() + border * 2 + padLeft + padRight;
    }

    private int nativeHeight() {
        return category.getHeight() + border * 2 + padTop + padBottom;
    }

    /** Масштаб рецепта в окне: см. {@link JeiScale}. Пересчитывается, когда меняется размер окна игры или настройка масштаба. */
    double scale() {
        layout();
        double px = works.repka.ryscore.ui.UiScale.pixels();
        if (px == scaleKey) return scale;
        scaleKey = px;
        scale = JeiScale.pick(px, nativeWidth(), nativeHeight(), JeiScale.maxWidth(), JeiScale.hardWidth(), JeiScale.MAX_H);
        return scale;
    }

    @Override
    public int width() {
        return (int) Math.ceil(nativeWidth() * scale() - 1e-6);
    }

    @Override
    public int height() {
        return (int) Math.ceil(nativeHeight() * scale() - 1e-6);
    }

    /**
     * Угол рецепта на экране, привязанный к пикселю ({@link JeiScale#snap}). По нему и рисуется, и считается курсор,
     * поэтому подсветка ячейки и клик совпадают.
     */
    private static double corner(int v) {
        return JeiScale.snap(works.repka.ryscore.ui.UiScale.pixels(), v);
    }

    /** Курсор окна в единицах раскладки JEI относительно угла рецепта. */
    private double localX(double mouseX, int x) {
        return (mouseX - corner(x)) / scale;
    }

    private double localY(double mouseY, int y) {
        return (mouseY - corner(y)) / scale;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** Поза: угол рецепта и масштаб; внутри всё в единицах JEI от угла рецепта. */
    private void enter(GuiGraphics g, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(corner(x), corner(y), 0);
        g.pose().scale((float) scale, (float) scale, 1f);
    }

    @Override
    public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) {
            theme.plate(g, x, y, width(), height());
            return;
        }
        scale();
        int lx = floor(localX(mouseX, x)), ly = floor(localY(mouseY, y));
        works.repka.ryscore.ui.Draw.isolated(g, () -> {
            enter(g, x, y);
            boolean restyled = works.repka.ryscope.ui.RecipeRestyle.begin(theme);
            try {
                if (padLeft > 0 || padTop > 0 || padRight > 0 || padBottom > 0) {
                    // окошко рецепта растянуто под ячейки за его краем: подложка того же серого, что фон окон модов (перекраска переведёт его в тему)
                    int w = category.getWidth() + padLeft + padRight, h = category.getHeight() + padTop + padBottom;
                    int x0 = border, y0 = border;
                    // как окно мода в ванильном стиле: тёмный контур, светлая грань, серый фон
                    g.fill(x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, 0xFF000000);
                    g.fill(x0, y0, x0 + w, y0 + h, 0xFFFFFFFF);
                    g.fill(x0 + 1, y0 + 1, x0 + w - 1, y0 + h - 1, 0xFFC6C6C6);
                }
                l.drawRecipe(g, lx, ly);
            } finally {
                if (restyled) {
                    works.repka.ryscope.ui.RecipeRestyle.end();
                    com.mojang.blaze3d.systems.RenderSystem.disableBlend();
                }
            }
            if (transferError != null && works.repka.ryscope.content.NativeRecipe.missingBadges) {
                try {
                    transferError.showError(g, lx, ly, l.getRecipeSlotsView(), border + padLeft, border + padTop);
                } catch (Throwable t) {
                    transferError = null;
                }
            }
        });
    }

    @Override
    public Stack stackAt(int x, int y, int mouseX, int mouseY) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return null;
        return l.getRecipeSlotUnderMouse(localX(mouseX, x), localY(mouseY, y))
            .flatMap(IRecipeSlotView::getDisplayedIngredient)
            .map(bridge::fromTyped)
            .orElse(null);
    }

    @Override
    public boolean renderTooltip(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return false;
        int lx = floor(localX(mouseX, x)), ly = floor(localY(mouseY, y));
        // подсветка ячейки увеличена вместе с рецептом, а подсказка откладывается и рисуется обычного размера у курсора
        works.repka.ryscope.ui.TooltipDefer.unscaled(() -> works.repka.ryscore.ui.Draw.isolated(g, () -> {
            enter(g, x, y);
            l.drawOverlays(g, lx, ly);
        }));
        return true;
    }

    @Override
    public void tick() {
        if (layout != null) layout.tick();
    }

    /** Раскладка со всеми слотами и виджетами нужна только для показа: при следующем показе она соберётся заново. */
    @Override
    public void release() {
        layout = null;
    }

    @Override
    public boolean mouseScrolled(int x, int y, double mouseX, double mouseY, double scrollX, double scrollY) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return false;
        try {
            return l.getInputHandler().handleMouseScrolled(localX(mouseX, x), localY(mouseY, y), scrollX, scrollY);
        } catch (Throwable t) {
            return false;
        }
    }

    /** В JEI нажатие приходит пробным вызовом, а настоящий идёт при отпускании кнопки. */
    @Override
    public boolean mouseClicked(int x, int y, double mouseX, double mouseY, int button) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return false;
        InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(button);
        try {
            return l.getInputHandler().handleInput(localX(mouseX, x), localY(mouseY, y), new Click(key, true));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void mouseReleased(int x, int y, double mouseX, double mouseY, int button) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return;
        try {
            l.getInputHandler().handleInput(localX(mouseX, x), localY(mouseY, y), new Click(InputConstants.Type.MOUSE.getOrCreate(button), false));
        } catch (Throwable t) {
            // чужая категория
        }
    }

    @Override
    public boolean mouseDragged(int x, int y, double mouseX, double mouseY, int button, double dragX, double dragY) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return false;
        try {
            return l.getInputHandler().handleMouseDragged(localX(mouseX, x), localY(mouseY, y), InputConstants.Type.MOUSE.getOrCreate(button),
                dragX / scale, dragY / scale);
        } catch (Throwable t) {
            return false;
        }
    }

    private record Click(InputConstants.Key key, boolean simulate) implements IJeiUserInput {
        @Override
        public InputConstants.Key getKey() {
            return key;
        }

        @Override
        public int getModifiers() {
            return 0;
        }

        @Override
        public boolean isSimulate() {
            return simulate;
        }

        @Override
        public boolean is(net.minecraft.client.KeyMapping mapping) {
            return works.repka.ryscope.client.Keys.keyMatcher.test(mapping, key);
        }

        @Override
        public boolean is(mezz.jei.api.runtime.IJeiKeyMapping mapping) {
            return mapping.isActiveAndMatches(key);
        }
    }

    private void readSlots() {
        inputs = new ArrayList<>();
        outputs = new ArrayList<>();
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return;
        for (IRecipeSlotView slot : l.getRecipeSlotsView().getSlotViews()) {
            RecipeIngredientRole role = slot.getRole();
            if (role == RecipeIngredientRole.RENDER_ONLY) continue;
            List<Stack> options = new ArrayList<>();
            slot.getAllIngredients().forEach(t -> {
                Stack s = bridge.fromTyped(t);
                if (s != null) options.add(s);
            });
            if (options.isEmpty()) continue;
            if (role == RecipeIngredientRole.OUTPUT) outputs.addAll(options);
            else inputs.add(new Ing(options, tagOf(slot)));
        }
    }

    /** Метод getTagKey появился в JEI 19.50; со старым JEI слот просто остаётся без подписи тега. */
    private static final java.lang.reflect.Method TAG_KEY = tagKeyMethod();

    private static java.lang.reflect.Method tagKeyMethod() {
        try {
            return IRecipeSlotView.class.getMethod("getTagKey");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String tagOf(IRecipeSlotView slot) {
        if (TAG_KEY == null) return null;
        try {
            if (TAG_KEY.invoke(slot) instanceof java.util.Optional<?> o && o.isPresent()
                && o.get() instanceof net.minecraft.tags.TagKey<?> key) {
                return key.location().toString();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // без подписи тега
        }
        return null;
    }

    @Override
    public List<Ing> inputs() {
        if (inputs == null) readSlots();
        return inputs;
    }

    @Override
    public List<Stack> outputs() {
        if (outputs == null) readSlots();
        return outputs;
    }

    @Override
    public Transfer transfer(AbstractContainerScreen<?> screen, boolean max, boolean simulate) {
        return transferTyped(screen.getMenu(), max, simulate);
    }

    private <C extends AbstractContainerMenu> Transfer transferTyped(C menu, boolean max, boolean simulate) {
        Minecraft mc = Minecraft.getInstance();
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null || mc.player == null) return Transfer.UNSUPPORTED;
        Optional<IRecipeTransferHandler<C, T>> handler = bridge.runtime.getRecipeTransferManager().getRecipeTransferHandler(menu, category);
        if (handler.isEmpty()) {
            // аддона AE2 для JEI может не быть: терминал умеет принимать рецепт и без него
            Transfer ae2 = works.repka.ryscope.runtime.Ae2Terminal.transfer(menu,
                recipe instanceof net.minecraft.world.item.crafting.RecipeHolder<?> holder ? holder : null, inputs(), outputs(), simulate);
            return ae2 != null ? ae2 : Transfer.UNSUPPORTED;
        }
        IRecipeTransferError error = handler.get().transferRecipe(menu, recipe, l.getRecipeSlotsView(), mc.player, max, !simulate);
        if (simulate) transferError = error;
        if (error == null) return Transfer.OK;
        List<Component> lines = tooltip(error);
        if (error.getType().allowsTransfer) return lines.isEmpty() ? Transfer.OK : Transfer.partial(lines);
        if (error.getType() == IRecipeTransferError.Type.INTERNAL) return Transfer.UNSUPPORTED;
        return Transfer.missing(lines.isEmpty() ? "ryscope.transfer.missing" : lines.get(0).getString());
    }

    /** Все варианты ячейки под курсором: для плашки вариантов по Ctrl+клику. */
    @Override
    public Ing ingAt(int x, int y, int mouseX, int mouseY) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return null;
        return l.getRecipeSlotUnderMouse(localX(mouseX, x), localY(mouseY, y)).map(slot -> {
            List<Stack> options = new ArrayList<>();
            slot.getAllIngredients().forEach(t -> {
                Stack s = bridge.fromTyped(t);
                if (s != null) options.add(s);
            });
            return options.isEmpty() ? null : new Ing(options, tagOf(slot));
        }).orElse(null);
    }

    /** Подсказку обработчики пишут то в старый метод, то в новый: собираем из нового, он вызывает и старый. */
    private static List<Component> tooltip(IRecipeTransferError error) {
        List<Component> out = new ArrayList<>();
        try {
            error.getTooltip(new mezz.jei.api.gui.builder.ITooltipBuilder() {
                @Override
                public void add(net.minecraft.network.chat.FormattedText text) {
                    if (text instanceof Component c) out.add(c);
                }

                @Override
                public void addAll(java.util.Collection<? extends net.minecraft.network.chat.FormattedText> texts) {
                    texts.forEach(this::add);
                }

                @Override
                public void add(net.minecraft.world.inventory.tooltip.TooltipComponent component) {
                }

                @Override
                public void addKeyUsageComponent(String key, mezz.jei.api.runtime.IJeiKeyMapping mapping) {
                }

                @Override
                public void setIngredient(mezz.jei.api.ingredients.ITypedIngredient<?> ingredient) {
                }

                @Override
                public void clearIngredient() {
                }

                @Override
                public List<com.mojang.datafixers.util.Either<net.minecraft.network.chat.FormattedText, net.minecraft.world.inventory.tooltip.TooltipComponent>> getLines() {
                    return List.of();
                }

                @Override
                public List<Component> toLegacyToComponents() {
                    return out;
                }

                @Override
                public void removeAll(List<Component> lines) {
                    out.removeAll(lines);
                }
            });
        } catch (Throwable t) {
            return error.getTooltip();
        }
        return out;
    }
}
