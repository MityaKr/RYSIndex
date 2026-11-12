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
    /** Ингредиент игры, из которого собран список: по нему тег узнаётся только когда понадобится. */
    @Nullable
    private final Ingredient source;
    private String lazyTag;
    private boolean tagRead;

    public Ing(List<Stack> options, @Nullable String tag) {
        this(options, tag, null);
    }

    private Ing(List<Stack> options, @Nullable String tag, @Nullable Ingredient source) {
        this.options = options;
        this.tag = tag;
        this.source = source;
    }

    /**
     * Тег, которым задан ингредиент («любые доски»), или null. У ингредиента игры читается из его кодека:
     * один тег пишется как {"tag": "..."}; составные и особые ингредиенты загрузчика тега не дают.
     */
    @Nullable
    public String tag() {
        if (tag != null || source == null) return tag;
        if (!tagRead) {
            tagRead = true;
            try {
                lazyTag = Ingredient.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, source).result()
                    .filter(com.google.gson.JsonElement::isJsonObject).map(com.google.gson.JsonElement::getAsJsonObject)
                    .filter(o -> o.size() == 1 && o.has("tag")).map(o -> o.get("tag").getAsString()).orElse(null);
            } catch (Throwable t) {
                lazyTag = null;
            }
        }
        return lazyTag;
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
        return out.isEmpty() ? EMPTY : new Ing(out, null, out.size() > 1 ? ingredient : null);
    }

    public boolean isEmpty() {
        return options.isEmpty();
    }

    /** Вариант, который показывается сейчас: варианты сменяются раз в секунду, пока не зажат Shift. */
    public Stack shown(long timeMs) {
        if (options.isEmpty()) return null;
        if (options.size() == 1) return options.get(0);
        return options.get((int) ((clock(timeMs) / 1000L) % options.size()));
    }

    private static long pausedAt = -1, pausedTotal;

    /**
     * Время перебора вариантов: стоит, пока зажат Shift (как pauseRecipeCycling в JEI), и после отпускания
     * идёт дальше с того же варианта. Иначе нужную доску приходилось ловить на лету.
     */
    private static long clock(long timeMs) {
        boolean hold = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        if (hold) {
            if (pausedAt < 0) pausedAt = timeMs;
            return pausedAt - pausedTotal;
        }
        if (pausedAt >= 0) {
            pausedTotal += Math.max(0, timeMs - pausedAt);
            pausedAt = -1;
        }
        return timeMs - pausedTotal;
    }

    public boolean accepts(Stack stack) {
        for (Stack o : options) {
            if (o.key() == stack.key() || o.key().equals(stack.key())) return true;
        }
        return false;
    }
}
