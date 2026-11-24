package works.repka.rysindex.client;

import java.util.Locale;
import java.util.function.Function;

/** Имена клавиш без раскладки для подписей {@link Keys#label}: чистые функции, без классов игры. */
final class KeyNames {
    private KeyNames() {}

    /**
     * Имя клавиши без раскладки по её имени в настройках ({@code key.keyboard.r}): буква или цифра латиницей, знак из
     * перевода игры ({@code key.keyboard.semicolon} это «;»). null, если своего имени нет и годится подпись игры.
     */
    static String stableName(String name, Function<String, String> translation) {
        String prefix = "key.keyboard.";
        if (name == null || !name.startsWith(prefix) || name.equals("key.keyboard.unknown")) return null;
        String rest = name.substring(prefix.length());
        if (rest.length() == 1) return rest.toUpperCase(Locale.ROOT);
        return translation.apply(name);
    }

    /** Заменить в полной подписи (с модификаторами, «Ctrl + F») часть самой клавиши на имя без раскладки. */
    static String replaceKeyPart(String full, String layout, String stable) {
        if (stable == null || stable.isEmpty() || layout.equals(stable)) return full;
        if (full.endsWith(layout)) return full.substring(0, full.length() - layout.length()) + stable;
        return full;
    }
}
