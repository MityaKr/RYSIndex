package works.repka.ryscope.runtime;

import java.util.List;
import works.repka.ryscope.model.Stack;

/**
 * Скрытые игроком предметы. С JEI они пишутся в его общий список (blacklist.json), без JEI в наши настройки.
 * Скрытое остаётся в индексе: его прячет поиск, а при «показывать скрытые» его видно и можно вернуть той же клавишей.
 */
public final class HiddenItems {
    private HiddenItems() {}

    /** @return скрыт ли ресурс теперь */
    public static boolean toggle(Stack stack, boolean allVariants) {
        Foreign.Source foreign = Foreign.get();
        if (foreign != null) {
            try {
                Boolean hidden = foreign.toggleHidden(stack, allVariants);
                if (hidden != null) return hidden;
            } catch (Throwable ignored) {
                // источник не смог: остаётся свой список
            }
        }
        List<String> list = Config.get().hiddenItems;
        String single = single(stack), all = stack.id().toString();
        boolean hidden;
        if (list.remove(single) | list.remove(all)) {
            hidden = false;
        } else {
            list.add(allVariants ? all : single);
            hidden = true;
        }
        Config.save();
        SearchService.submit(SearchService.text());
        return hidden;
    }

    /** Скрыт ли ресурс своим списком (без JEI). */
    static boolean hiddenLocally(Stack stack, java.util.Set<String> keys) {
        return keys.contains(stack.id().toString()) || keys.contains(single(stack));
    }

    private static String single(Stack stack) {
        net.minecraft.world.item.ItemStack item = stack.item();
        String id = stack.id().toString();
        return item.isEmpty() || item.getComponentsPatch().isEmpty() ? id : id + "#" + item.getComponentsPatch();
    }
}
