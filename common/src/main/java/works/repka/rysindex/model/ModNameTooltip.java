package works.repka.rysindex.model;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import works.repka.rysindex.core.ModNameLines;

/** Подпись мода в подсказке наших панелей, как в списке JEI: одна строка в конце, без повтора за Jade. */
public final class ModNameTooltip {
    private ModNameTooltip() {}

    /** Готовые строки подсказки с подписью мода; без настройки «показывать мод» строки не меняются. */
    public static List<Component> finish(List<Component> lines, String mod) {
        if (!works.repka.rysindex.runtime.Config.get().showModName) return lines;
        List<String> plain = new ArrayList<>(lines.size());
        for (Component line : lines) plain.add(line.getString());
        ModNameLines.Plan plan = ModNameLines.plan(plain, mod);
        if (plan.isEmpty()) return lines;
        List<Component> out = new ArrayList<>(lines);
        for (int i = plan.remove().size() - 1; i >= 0; i--) out.remove((int) plan.remove().get(i));
        if (plan.append()) out.add(Component.literal(mod).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
        return out;
    }
}
