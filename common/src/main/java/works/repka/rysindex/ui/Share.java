package works.repka.rysindex.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import works.repka.rysindex.model.Stack;
import works.repka.rysindex.runtime.CraftTree;
import works.repka.rysindex.runtime.Foreign;

/** Показать ресурс в чате: открывается строка чата с меткой, которую JEI получателя превращает в ссылку. */
final class Share {
    /** Длина строки чата в игре. */
    private static final int CHAT_LIMIT = 256;

    private Share() {}

    static void toChat(Stack stack) {
        Minecraft.getInstance().setScreen(new ChatScreen(marker(stack)));
    }

    /**
     * Общая сумма целей: строка чата со списком того, что ещё добывать (если добывать нечего, всего нужного).
     * Что не влезает в строку чата, отбрасывается целыми предметами. Отправляет игрок сам.
     */
    static void toChat(CraftTree sum) {
        StringBuilder text = new StringBuilder(Component.translatable("rysindex.tree.all_goals").getString()).append(": ");
        boolean anyLeft = false;
        for (CraftTree.Leaf l : sum.leaves.values()) anyLeft |= l.left > 0;
        boolean first = true;
        for (CraftTree.Leaf l : sum.leaves.values()) {
            long n = anyLeft ? l.left : l.need;
            if (n <= 0) continue;
            String part = (first ? "" : ", ") + n + " " + marker(l.stack).trim();
            if (text.length() + part.length() > CHAT_LIMIT) break;
            text.append(part);
            first = false;
        }
        Minecraft.getInstance().setScreen(new ChatScreen(text.toString()));
    }

    private static String marker(Stack stack) {
        String text = null;
        Foreign.Source foreign = Foreign.get();
        if (foreign != null) {
            try {
                text = foreign.chatMarker(stack);
            } catch (Throwable ignored) {
                // без метки остаётся имя
            }
        }
        // без JEI у получателя ссылки не будет: хотя бы имя
        return text != null ? text : "[" + stack.name().getString() + "] ";
    }
}
