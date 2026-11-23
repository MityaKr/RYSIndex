package works.repka.rysindex.content;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;

/** Цели страницы справки по одной, без реестров игры (поэтому проверяется обычным тестом). */
final class HelpTargets {
    private HelpTargets() {}

    /** Массив раскрывается, остальное как есть: строка-id, «#тег» или объект. */
    static List<JsonElement> parts(JsonElement e) {
        List<JsonElement> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            for (JsonElement el : e.getAsJsonArray()) out.addAll(parts(el));
        } else {
            out.add(e);
        }
        return out;
    }

    /** Объект {@code {"id": ..., "components": {...}}}: страница только у этого подтипа предмета. */
    static boolean hasComponents(JsonElement e) {
        return e != null && e.isJsonObject() && e.getAsJsonObject().has("components");
    }
}
