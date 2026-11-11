package works.repka.ryscope.neoforge.ae2;

import appeng.client.gui.Icon;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.widgets.ToggleButton;
import appeng.parts.encoding.EncodingMode;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import works.repka.ryscope.neoforge.net.ProcessingAlternativesPayload;
import works.repka.ryscope.runtime.Ae2Terminal;

/** Клиент: кнопка замещения в режиме обработки терминала шаблонов и строка в подсказке шаблона. Только при AE2. */
public final class Ae2ClientHooks {
    /** Место кнопки в стиле экрана AE2: между «очистить» (79) и «переключить результат» (98) панели обработки. */
    private static final int LEFT = 88, BOTTOM = 159;
    private static final Map<PatternEncodingTermScreen<?>, ToggleButton> BUTTONS = new WeakHashMap<>();

    private Ae2ClientHooks() {}

    public static void register(IEventBus bus) {
        bus.addListener((ScreenEvent.Init.Post e) -> {
            if (e.getScreen() instanceof PatternEncodingTermScreen<?> screen) addButton(e, screen);
        });
        bus.addListener((ScreenEvent.Render.Pre e) -> {
            if (!(e.getScreen() instanceof PatternEncodingTermScreen<?> screen)) return;
            ToggleButton button = BUTTONS.get(screen);
            if (button == null) return;
            button.visible = screen.getMenu().getMode() == EncodingMode.PROCESSING;
            button.setState(screen.getMenu().isSubstitute());
        });
        bus.addListener(ItemTooltipEvent.class, Ae2ClientHooks::tooltip);
        // варианты разложенного рецепта уходят на сервер, только если там тоже стоит RYScope с этой функцией
        Ae2Terminal.alternativesSink = groups -> {
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null && connection.hasChannel(ProcessingAlternativesPayload.TYPE)) {
                PacketDistributor.sendToServer(new ProcessingAlternativesPayload(groups));
            }
        };
    }

    private static void addButton(ScreenEvent.Init.Post e, PatternEncodingTermScreen<?> screen) {
        var menu = screen.getMenu();
        ToggleButton button = new ToggleButton(Icon.S_SUBSTITUTION_ENABLED, Icon.S_SUBSTITUTION_DISABLED, menu::setSubstitute);
        button.setHalfSize(true);
        button.setDisableBackground(true);
        button.setTooltipOn(List.of(Component.translatable("ryscope.ae2.substitution.on"), Component.translatable("ryscope.ae2.substitution.on.desc")));
        button.setTooltipOff(List.of(Component.translatable("ryscope.ae2.substitution.off"), Component.translatable("ryscope.ae2.substitution.off.desc")));
        button.setX(screen.getGuiLeft() + LEFT);
        button.setY(screen.getGuiTop() + screen.getYSize() - BOTTOM);
        button.visible = menu.getMode() == EncodingMode.PROCESSING;
        button.setState(menu.isSubstitute());
        e.addListener(button);
        BUTTONS.put(screen, button);
    }

    private static void tooltip(ItemTooltipEvent e) {
        List<List<Item>> alternatives = e.getItemStack().get(ProcessingSubstitution.ALTERNATIVES.get());
        if (alternatives == null) return;
        int inputs = 0;
        for (List<Item> variants : alternatives) if (variants.size() > 1) inputs++;
        if (inputs > 0) e.getToolTip().add(Component.translatable("ryscope.ae2.substitution.tooltip", inputs).withStyle(ChatFormatting.DARK_GREEN));
    }
}
