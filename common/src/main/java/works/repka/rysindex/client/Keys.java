package works.repka.rysindex.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Горячие клавиши. Сами объекты создаёт модуль загрузчика (нужен контекст конфликтов), здесь только доступ. */
public final class Keys {
    public static final String CATEGORY = "key.categories.rysindex";

    public static KeyMapping RECIPES;
    public static KeyMapping USES;
    public static KeyMapping BOOKMARK;
    public static KeyMapping LENS;
    public static KeyMapping SEARCH;
    public static KeyMapping TOGGLE;
    public static KeyMapping THEME;
    public static KeyMapping BACK;
    public static KeyMapping TREE;
    /** Скрыть предмет под курсором из списка или вернуть его; с Shift все его варианты. */
    public static KeyMapping HIDE;
    /** Скрыть всё, что нашёл текущий запрос. */
    public static KeyMapping HIDE_QUERY;
    public static KeyMapping SAVE_SEARCH;
    public static KeyMapping COPY_ID;
    /** Следующая вкладка истории карточки; с Shift предыдущая. */
    public static KeyMapping NEXT_TAB;
    public static KeyMapping CLOSE_TAB;
    /** Страницы: в карточке листают рецепты, над списком листают список. */
    public static KeyMapping PREV_PAGE;
    public static KeyMapping NEXT_PAGE;
    public static KeyMapping PREV_CATEGORY;
    public static KeyMapping NEXT_CATEGORY;
    public static KeyMapping FORWARD;
    /** Показать предмет под курсором в чате. */
    public static KeyMapping SHARE;
    /** Разложить рецепт предмета под курсором в сетку открытого окна, не открывая карточку. */
    public static KeyMapping CRAFT;
    /** Снимок рецепта под курсором в PNG. */
    public static KeyMapping SHOT;

    /** Только для автотеста: считать клавишу линзы зажатой. */
    public static boolean forceLens;

    private Keys() {}

    /** Проверка с учётом модификаторов и контекста: её подставляет модуль загрузчика. */
    public interface Matcher {
        boolean test(KeyMapping mapping, int keyCode, int scanCode);
    }

    public static Matcher matcher = (mapping, keyCode, scanCode) -> mapping.matches(keyCode, scanCode);

    /** Совпадает ли клавиша или кнопка мыши с привязкой, с учётом контекста: её тоже подставляет модуль загрузчика. */
    public static java.util.function.BiPredicate<KeyMapping, InputConstants.Key> keyMatcher =
        (mapping, key) -> !mapping.isUnbound() && InputConstants.getKey(mapping.saveString()).equals(key);

    public static boolean matches(KeyMapping mapping, int keyCode, int scanCode) {
        if (mapping == null) return false;
        if (!mapping.isUnbound() && matcher.test(mapping, keyCode, scanCode)) return true;
        // игрок переназначил клавишу в JEI, а нашу не трогал: привычная клавиша работает и у нас.
        // Окно JEI отключено, поэтому двойного срабатывания нет; чужие настройки не меняем.
        KeyMapping twin = jeiTwin(mapping);
        return twin != null && mapping.isDefault() && !twin.isDefault() && !twin.isUnbound()
            && works.repka.rysindex.runtime.Config.get().replaceJeiInterface && matcher.test(twin, keyCode, scanCode);
    }

    private static java.util.Map<KeyMapping, KeyMapping> jeiTwins;

    /** Клавиша JEI того же действия или null, если JEI нет. */
    private static KeyMapping jeiTwin(KeyMapping mapping) {
        if (jeiTwins == null) {
            java.util.Map<String, KeyMapping> byName = new java.util.HashMap<>();
            for (KeyMapping k : Minecraft.getInstance().options.keyMappings) byName.put(k.getName(), k);
            java.util.Map<KeyMapping, KeyMapping> twins = new java.util.IdentityHashMap<>();
            String[] names = {"key.jei.showRecipe", "key.jei.showUses", "key.jei.bookmark", "key.jei.focusSearch", "key.jei.toggleOverlay", "key.jei.recipeBack"};
            KeyMapping[] ours = {RECIPES, USES, BOOKMARK, SEARCH, TOGGLE, BACK};
            for (int i = 0; i < ours.length; i++) {
                KeyMapping jei = byName.get(names[i]);
                if (ours[i] != null && jei != null) twins.put(ours[i], jei);
            }
            jeiTwins = twins;
        }
        return jeiTwins.get(mapping);
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

    /**
     * Подпись клавиши для подсказок: буквы и знаки по латинскому имени клавиши, как бы ни была переключена раскладка.
     * Игра берёт имя у системы ({@code glfwGetKeyName}), и при русской раскладке R показывалась как «К», Ctrl+F как «Ctrl+А».
     * Модификаторы и кнопки мыши остаются как у игры.
     */
    public static String label(KeyMapping mapping) {
        if (mapping == null) return "?";
        String full = mapping.getTranslatedKeyMessage().getString();
        if (mapping.isUnbound()) return full;
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        if (key.getType() != InputConstants.Type.KEYSYM) return full;
        String layout = key.getDisplayName().getString();
        String stable = KeyNames.stableName(key.getName(), name -> net.minecraft.client.resources.language.I18n.exists(name)
            ? net.minecraft.client.resources.language.I18n.get(name) : null);
        return KeyNames.replaceKeyPart(full, layout, stable);
    }
}
