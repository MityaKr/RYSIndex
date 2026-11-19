package works.repka.rysindex.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Строка с именем мода в готовой подсказке. Имя мода дописывают и Jade, и наши панели, а порядок их слушателей
 * события подсказки случаен (моды строятся параллельно), поэтому решаем по итоговому списку строк:
 * если строк ровно с именем мода нет, дописываем одну в конец; если их несколько, оставляем последнюю.
 * Строки, где имя мода только часть текста, не трогаем: так мод может писать о себе специально.
 */
public final class ModNameLines {
    private ModNameLines() {}

    /** Что сделать с подсказкой: убрать строки по номерам (по возрастанию) и дописать ли имя мода в конец. */
    public record Plan(List<Integer> remove, boolean append) {
        public boolean isEmpty() {
            return remove.isEmpty() && !append;
        }
    }

    /** @param lines текст строк подсказки, как {@code Component.getString()} */
    public static Plan plan(List<String> lines, String mod) {
        String want = normalize(mod);
        if (want.isEmpty()) return new Plan(List.of(), false);
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (normalize(lines.get(i)).equals(want)) matches.add(i);
        }
        if (matches.isEmpty()) return new Plan(List.of(), true);
        return new Plan(List.copyOf(matches.subList(0, matches.size() - 1)), false);
    }

    /** Без кодов форматирования через §, пробелов по краям и регистра: Jade и другие могут красить имя по-своему. */
    static String normalize(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().trim().toLowerCase(Locale.ROOT);
    }
}
