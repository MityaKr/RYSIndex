package works.repka.ryscope.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponentPatch;
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

    /**
     * Скрыт ли ресурс своим списком (без JEI).
     * @param variantIds id, у которых в списке есть записи с компонентами: только для них ключ кодируется
     */
    static boolean hiddenLocally(Stack stack, Set<String> keys, Set<String> variantIds) {
        String id = stack.id().toString();
        return keys.contains(id) || (variantIds.contains(id) && keys.contains(single(stack)));
    }

    /** id из записей вида {@code id#компоненты}. */
    static Set<String> variantIds(Collection<String> keys) {
        Set<String> ids = new HashSet<>();
        for (String k : keys) {
            int hash = k.indexOf('#');
            if (hash > 0) ids.add(k.substring(0, hash));
        }
        return ids;
    }

    /**
     * Запись старого формата: компоненты строкой {@code toString()}, в ней хеши объектов, которые меняются
     * между запусками. Такую запись ни один стак больше не совпадёт, и из интерфейса её не убрать.
     */
    static boolean legacy(String key) {
        int hash = key.indexOf('#');
        return hash > 0 && key.startsWith("{", hash + 1) && key.contains("=>");
    }

    private static String single(Stack stack) {
        net.minecraft.world.item.ItemStack item = stack.item();
        String id = stack.id().toString();
        if (item.isEmpty()) return id;
        DataComponentPatch patch = item.getComponentsPatch();
        if (patch.isEmpty()) return id;
        // кодеком, а не toString(): строка стабильна между запусками. Ключи упорядочены, порядок патча
        // и карт внутри компонентов (чары) зависит от того, как собран стак
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return id;
        try {
            return DataComponentPatch.CODEC.encodeStart(level.registryAccess().createSerializationContext(JsonOps.INSTANCE), patch)
                .result().map(json -> id + "#" + canonical(json)).orElse(id);
        } catch (Throwable ignored) {
            return id;
        }
    }

    private static JsonElement canonical(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject src = e.getAsJsonObject(), out = new JsonObject();
            List<String> keys = new ArrayList<>(src.keySet());
            keys.sort(null);
            for (String k : keys) out.add(k, canonical(src.get(k)));
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) out.add(canonical(x));
            return out;
        }
        return e;
    }
}
