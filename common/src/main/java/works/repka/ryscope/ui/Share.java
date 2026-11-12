package works.repka.ryscope.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Foreign;

/** Показать ресурс в чате: открывается строка чата с меткой, которую JEI получателя превращает в ссылку. */
final class Share {
    private Share() {}

    static void toChat(Stack stack) {
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
        if (text == null) text = "[" + stack.name().getString() + "] ";
        Minecraft.getInstance().setScreen(new ChatScreen(text));
    }
}
