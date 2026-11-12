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
        List<String> list = Config.get().hiddenItems;
        String single = single(stack), all = stack.id().toString();
        Foreign.Source foreign = Foreign.get();
        // сначала свой список: предмет, скрытый до установки JEI или когда JEI не смог, иначе не вернуть
        if (list.remove(single) | list.remove(all)) {
            Config.save();
            SearchService.submit(SearchService.text());
            if (foreign == null) return false;
            try {
                return foreign.isHidden(stack);
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (foreign != null) {
            try {
                Boolean hidden = foreign.toggleHidden(stack, allVariants);
                if (hidden != null) return hidden;
            } catch (Throwable ignored) {
                // источник не смог: остаётся свой список
            }
        }
        list.add(allVariants ? all : single);
        Config.save();
        SearchService.submit(SearchService.text());
        return true;
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
