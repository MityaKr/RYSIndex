package works.repka.rysindex.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.RecipeView.Transfer;
import works.repka.rysindex.model.Stack;

/**
 * Перенос рецепта в слоты без серверной части мода.
 * Если рецепт открыт в книге рецептов, используется её штатный пакет (раскладывает сервер).
 * Иначе раскладка делается обычными кликами по слотам, как это сделал бы игрок.
 */
public final class ClickTransfer {
    private ClickTransfer() {}

    public static Transfer transfer(NativeRecipe recipe, AbstractContainerScreen<?> screen, boolean max, boolean simulate) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        AbstractContainerMenu menu = screen.getMenu();
        if (player == null || mc.gameMode == null) return Transfer.UNSUPPORTED;

        List<Slot> targets = targetSlots(recipe, menu);
        if (targets == null) return Transfer.UNSUPPORTED;

        // какие ингредиенты в какие слоты
        List<Ing> plan = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) plan.add(Ing.EMPTY);
        if (recipe.kind == NativeRecipe.Kind.CRAFTING) {
            int gw = gridWidth(targets);
            int gh = targets.size() / Math.max(1, gw);
            if (recipe.gridW > gw || recipe.gridH > gh) {
                return Transfer.missing("rysindex.transfer.too_small");
            }
            List<Ing> in = recipe.inputs();
            for (int i = 0; i < in.size(); i++) {
                int col = i % recipe.gridW, row = i / recipe.gridW;
                plan.set(row * gw + col, in.get(i));
            }
        } else if (recipe.kind == NativeRecipe.Kind.SMITHING) {
            // шаблон, основа и добавка по своим слотам, как в окне стола
            List<Ing> in = recipe.inputs();
            for (int i = 0; i < in.size() && i < targets.size(); i++) plan.set(i, in.get(i));
        } else {
            plan.set(0, recipe.inputs().get(0));
        }

        // хватает ли предметов: считаем по копии инвентаря
        List<Slot> sources = new ArrayList<>();
        for (Slot s : menu.slots) {
            if (s.container instanceof Inventory && s.getContainerSlot() < 36) sources.add(s);
        }
        int[] avail = new int[sources.size()];
        ItemStack[] kinds = new ItemStack[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            kinds[i] = sources.get(i).getItem();
            avail[i] = kinds[i].getCount();
        }
        // то, что сейчас лежит в целевых слотах, вернётся в инвентарь: считаем доступным
        List<ItemStack> returning = new ArrayList<>();
        for (Slot t : targets) if (t.hasItem()) returning.add(t.getItem().copy());

        int batches = max ? 64 : 1;
        int needed = 0;
        for (Ing ing : plan) if (!ing.isEmpty()) needed++;
        if (needed == 0) return Transfer.UNSUPPORTED;
        if (max) {
            // сколько полных наборов можно собрать
            batches = maxBatches(plan, kinds, avail, returning);
            if (batches < 1) batches = 1;
        }
        int missing = countMissing(plan, kinds, avail.clone(), returning, batches);
        if (missing > 0) return Transfer.missing("rysindex.transfer.missing");
        if (!menu.getCarried().isEmpty()) return Transfer.missing("rysindex.transfer.cursor");
        if (simulate) return Transfer.OK;

        // 1. книга рецептов: сервер раскладывает сам. Не для сеток крафта: в верстаке сервер кладёт результат,
        // а клиенту слот результата не приходит, и он остаётся пустым до следующего действия; клики дают его сразу
        if (recipe.kind != NativeRecipe.Kind.CRAFTING && recipe.holder != null && menu instanceof RecipeBookMenu<?, ?>
            && player.getRecipeBook().contains(recipe.holder)) {
            mc.gameMode.handlePlaceRecipe(menu.containerId, recipe.holder, max);
            return Transfer.OK;
        }

        // 2. клики
        try {
            MultiPlayerGameMode gm = mc.gameMode;
            for (Slot t : targets) {
                if (t.hasItem()) gm.handleInventoryMouseClick(menu.containerId, t.index, 0, ClickType.QUICK_MOVE, player);
            }
            for (int i = 0; i < plan.size(); i++) {
                Ing ing = plan.get(i);
                if (ing.isEmpty()) continue;
                Slot target = targets.get(i);
                int left = Math.min(batches, 64);
                int guard = 0;
                while (left > 0 && guard++ < 40) {
                    Slot source = findSource(sources, ing);
                    if (source == null) break;
                    int have = source.getItem().getCount();
                    int limit = Math.min(left, source.getItem().getMaxStackSize() - (target.hasItem() ? target.getItem().getCount() : 0));
                    if (limit <= 0) break;
                    gm.handleInventoryMouseClick(menu.containerId, source.index, 0, ClickType.PICKUP, player);
                    if (have <= limit) {
                        gm.handleInventoryMouseClick(menu.containerId, target.index, 0, ClickType.PICKUP, player);
                        left -= have;
                    } else {
                        for (int k = 0; k < limit; k++) {
                            gm.handleInventoryMouseClick(menu.containerId, target.index, 1, ClickType.PICKUP, player);
                        }
                        gm.handleInventoryMouseClick(menu.containerId, source.index, 0, ClickType.PICKUP, player);
                        left -= limit;
                    }
                    if (!menu.getCarried().isEmpty()) {
                        // предмет остался на курсоре (слот не принял): возвращаем и прекращаем
                        gm.handleInventoryMouseClick(menu.containerId, source.index, 0, ClickType.PICKUP, player);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            RYSIndex.LOG.warn("Перенос рецепта кликами не удался", e);
            return Transfer.missing("rysindex.transfer.failed");
        }
        return Transfer.OK;
    }

    private static List<Slot> targetSlots(NativeRecipe recipe, AbstractContainerMenu menu) {
        switch (recipe.kind) {
            case CRAFTING: {
                List<Slot> grid = new ArrayList<>();
                for (Slot s : menu.slots) if (s.container instanceof CraftingContainer) grid.add(s);
                if (grid.isEmpty()) return null;
                grid.sort(Comparator.comparingInt(Slot::getContainerSlot));
                return grid;
            }
            case COOKING:
                if (menu instanceof AbstractFurnaceMenu && !menu.slots.isEmpty()) return List.of(menu.slots.get(0));
                return null;
            case SINGLE:
                if (menu instanceof StonecutterMenu && !menu.slots.isEmpty()) return List.of(menu.slots.get(0));
                return null;
            case SMITHING:
                // стол кузнеца и его наследники: слоты 0..2 шаблон, основа, добавка
                // (SmithingMenu.createInputSlotDefinitions), 3 результат
                if (menu instanceof SmithingMenu && menu.slots.size() > SmithingMenu.ADDITIONAL_SLOT) {
                    return List.of(menu.slots.get(SmithingMenu.TEMPLATE_SLOT), menu.slots.get(SmithingMenu.BASE_SLOT),
                        menu.slots.get(SmithingMenu.ADDITIONAL_SLOT));
                }
                return null;
            default:
                return null;
        }
    }

    private static int gridWidth(List<Slot> grid) {
        if (grid.get(0).container instanceof CraftingContainer cc) return cc.getWidth();
        return (int) Math.round(Math.sqrt(grid.size()));
    }

    private static boolean matches(Ing ing, ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (Stack o : ing.options) if (o.key() == stack.getItem()) return true;
        return false;
    }

    private static Slot findSource(List<Slot> sources, Ing ing) {
        Slot best = null;
        for (Slot s : sources) {
            if (!matches(ing, s.getItem())) continue;
            // сначала расходуем неполные стеки
            if (best == null || s.getItem().getCount() < best.getItem().getCount()) best = s;
        }
        return best;
    }

    private static int countMissing(List<Ing> plan, ItemStack[] kinds, int[] avail, List<ItemStack> returning, int batches) {
        int[] ret = new int[returning.size()];
        for (int i = 0; i < ret.length; i++) ret[i] = returning.get(i).getCount();
        int missing = 0;
        for (Ing ing : plan) {
            if (ing.isEmpty()) continue;
            int need = batches;
            for (int i = 0; i < kinds.length && need > 0; i++) {
                if (avail[i] > 0 && matches(ing, kinds[i])) {
                    int take = Math.min(need, avail[i]);
                    avail[i] -= take;
                    need -= take;
                }
            }
            for (int i = 0; i < ret.length && need > 0; i++) {
                if (ret[i] > 0 && matches(ing, returning.get(i))) {
                    int take = Math.min(need, ret[i]);
                    ret[i] -= take;
                    need -= take;
                }
            }
            if (need > 0) missing++;
        }
        return missing;
    }

    private static int maxBatches(List<Ing> plan, ItemStack[] kinds, int[] avail, List<ItemStack> returning) {
        int lo = 0, hi = 64;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (countMissing(plan, kinds, avail.clone(), returning, mid) == 0) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }
}
