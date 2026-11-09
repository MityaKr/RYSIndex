package works.repka.ryscope.model;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/** Ингредиент рецепта: один или несколько взаимозаменяемых ресурсов. */
public final class Ing {
    public static final Ing EMPTY = new Ing(List.of(), null);

    public final List<Stack> options;
    /** Тег, из которого получен список, если он известен. */
    @Nullable
    public final String tag;

    public Ing(List<Stack> options, @Nullable String tag) {
        this.options = options;
        this.tag = tag;
    }

    public static Ing of(Stack stack) {
        return new Ing(List.of(stack), null);
    }

    public static Ing of(Ingredient ingredient) {
        if (ingredient.isEmpty()) return EMPTY;
        ItemStack[] items = ingredient.getItems();
        List<Stack> out = new ArrayList<>(items.length);
        for (ItemStack s : items) {
            if (!s.isEmpty()) out.add(new ItemRes(s));
        }
        return out.isEmpty() ? EMPTY : new Ing(out, null);
    }

    public boolean isEmpty() {
        return options.isEmpty();
    }

    /** Вариант, который показывается сейчас: варианты сменяются раз в секунду. */
    public Stack shown(long timeMs) {
        if (options.isEmpty()) return null;
        if (options.size() == 1) return options.get(0);
        return options.get((int) ((timeMs / 1000L) % options.size()));
    }

    public boolean accepts(Stack stack) {
        for (Stack o : options) {
            if (o.key() == stack.key() || o.key().equals(stack.key())) return true;
        }
        return false;
    }
}
