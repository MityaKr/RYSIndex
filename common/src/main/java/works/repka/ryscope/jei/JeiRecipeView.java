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

    @Override
    public ResourceLocation id() {
        try {
            return category.getRegistryName(recipe);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int width() {
        return category.getWidth() + border * 2;
    }

    @Override
    public int height() {
        return category.getHeight() + border * 2;
    }

    @Override
    public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) {
            theme.plate(g, x, y, width(), height());
            return;
        }
        l.setPosition(x + border, y + border);
        boolean restyled = works.repka.ryscope.ui.RecipeRestyle.begin(theme);
        try {
            l.drawRecipe(g, mouseX, mouseY);
        } finally {
            if (restyled) works.repka.ryscope.ui.RecipeRestyle.end();
        }
        if (transferError != null && works.repka.ryscope.content.NativeRecipe.missingBadges) {
            try {
                transferError.showError(g, mouseX, mouseY, l.getRecipeSlotsView(), x + border, y + border);
            } catch (Throwable t) {
                transferError = null;
            }
        }
    }

    @Override
    public Stack stackAt(int x, int y, int mouseX, int mouseY) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return null;
        return l.getRecipeSlotUnderMouse(mouseX, mouseY)
            .flatMap(IRecipeSlotView::getDisplayedIngredient)
            .map(bridge::fromTyped)
            .orElse(null);
    }

    @Override
    public boolean renderTooltip(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return false;
        l.drawOverlays(g, mouseX, mouseY);
        return true;
    }

    @Override
    public void tick() {
        if (layout != null) layout.tick();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return false;
        try {
            return l.getInputHandler().handleMouseScrolled(mouseX, mouseY, scrollX, scrollY);
        } catch (Throwable t) {
            return false;
        }
    }

    /** В JEI нажатие приходит пробным вызовом, а настоящий идёт при отпускании кнопки. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return false;
        InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(button);
        try {
            return l.getInputHandler().handleInput(mouseX, mouseY, new Click(key, true));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return;
        try {
            l.getInputHandler().handleInput(mouseX, mouseY, new Click(InputConstants.Type.MOUSE.getOrCreate(button), false));
        } catch (Throwable t) {
            // чужая категория
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        IRecipeLayoutDrawable<T> l = layout();
        if (l == null) return false;
        try {
            return l.getInputHandler().handleMouseDragged(mouseX, mouseY, InputConstants.Type.MOUSE.getOrCreate(button), dragX, dragY);
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
            return mapping.isActiveAndMatches(key);
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
