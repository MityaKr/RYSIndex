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
        return out.isEmpty() ? EMPTY : new Ing(out, out.size() > 1 ? tagOf(ingredient) : null);
    }

    /** Тег, если ингредиент задан одним тегом. Поля ингредиента закрыты, поэтому тег берётся из его кодека. */
    @Nullable
    private static String tagOf(Ingredient ingredient) {
        try {
            var json = Ingredient.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, ingredient).result().orElse(null);
            if (json instanceof com.google.gson.JsonObject o && o.has("tag")) return o.get("tag").getAsString();
        } catch (Throwable ignored) {
            // чужой ингредиент без кодека: тег просто не показывается
        }
        return null;
    }

    private static long pausedSince = -1, pausedTotal;

    /**
     * Часы смены вариантов: пока зажат Shift, варианты стоят на месте, как в JEI, а после отпускания
     * смена продолжается с того же варианта.
     */
    public static long clock() {
        long now = System.currentTimeMillis();
        if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
            if (pausedSince < 0) pausedSince = now;
            return pausedSince - pausedTotal;
        }
        if (pausedSince >= 0) {
            pausedTotal += now - pausedSince;
            pausedSince = -1;
        }
        return now - pausedTotal;
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
