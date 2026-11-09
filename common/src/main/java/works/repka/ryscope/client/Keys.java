package works.repka.ryscope.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Горячие клавиши. Сами объекты создаёт модуль загрузчика (нужен контекст конфликтов), здесь только доступ. */
public final class Keys {
    public static final String CATEGORY = "key.categories.ryscope";

    public static KeyMapping RECIPES;
    public static KeyMapping USES;
    public static KeyMapping BOOKMARK;
    public static KeyMapping LENS;
    public static KeyMapping SEARCH;
    public static KeyMapping TOGGLE;
    public static KeyMapping THEME;
    public static KeyMapping BACK;
    public static KeyMapping TREE;

    /** Только для автотеста: считать клавишу линзы зажатой. */
    public static boolean forceLens;

    private Keys() {}

    /** Проверка с учётом модификаторов и контекста: её подставляет модуль загрузчика. */
    public interface Matcher {
        boolean test(KeyMapping mapping, int keyCode, int scanCode);
    }

    public static Matcher matcher = (mapping, keyCode, scanCode) -> mapping.matches(keyCode, scanCode);

    public static boolean matches(KeyMapping mapping, int keyCode, int scanCode) {
        return mapping != null && !mapping.isUnbound() && matcher.test(mapping, keyCode, scanCode);
    }

    /** Только сама клавиша, без модификаторов и контекста: для клавиш интерфейса, нажатых в мире. */
    public static boolean matchesRaw(KeyMapping mapping, int keyCode, int scanCode) {
        return mapping != null && !mapping.isUnbound() && mapping.matches(keyCode, scanCode);
    }

    /** Нажата ли клавиша прямо сейчас. Работает и при открытом экране, где обычный isDown() молчит. */
    public static boolean isHeld(KeyMapping mapping) {
        if (forceLens && mapping == LENS) return true;
        if (mapping == null || mapping.isUnbound()) return false;
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        if (key.getType() != InputConstants.Type.KEYSYM || key.getValue() <= 0) return false;
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key.getValue());
    }

    public static String label(KeyMapping mapping) {
        return mapping == null ? "?" : mapping.getTranslatedKeyMessage().getString();
    }
}
