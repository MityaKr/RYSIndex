package works.repka.rysindex.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import works.repka.rysindex.model.Ing;
import works.repka.rysindex.model.RecipeView;
import works.repka.rysindex.model.Stack;
import works.repka.ryscore.theme.Theme;

/**
 * Страница справки из JSON. Блоки: {@code p} (абзац), {@code warn} (предупреждение), {@code items} (ряд предметов).
 * В тексте работают ссылки {@code [[item:id]]} (название предмета, сам предмет показывается под абзацем)
 * и {@code [[что-угодно|подпись]]}. Рисует {@link HelpPage}.
 */
public final class HelpView implements RecipeView {
    private static final Pattern LINK = Pattern.compile("\\[\\[([a-z_]+):([^\\]|]+)(?:\\|([^\\]]+))?]]");

    private final ResourceLocation id;
    private final List<Stack> targets;
    private final JsonArray body;
    private final List<Ing> inputs;
    private HelpPage page;
    private String pageLanguage;

    public HelpView(ResourceLocation id, List<Stack> targets, JsonArray body) {
        this.id = id;
        this.targets = targets;
        this.body = body;
        this.inputs = List.of(new Ing(targets, null));
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public int width() {
        return HelpPage.WIDTH;
    }

    @Override
    public int height() {
        return page().height();
    }

    /** Текст страницы как его видит игрок: для автотеста. */
    public String plainText() {
        return page().plainText();
    }

    private static String text(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return "";
        String s = e.getAsString();
        return Language.getInstance().has(s) ? Language.getInstance().getOrDefault(s) : s;
    }

    /** Блоки собираются при первом показе и при смене языка: в тексте бывают ключи перевода и названия предметов. */
    private HelpPage page() {
        String language = net.minecraft.client.Minecraft.getInstance().options.languageCode;
        if (page != null && language.equals(pageLanguage)) return page;
        pageLanguage = language;
        List<HelpPage.Block> blocks = new ArrayList<>();
        if (body != null) {
            for (JsonElement be : body) {
                if (!be.isJsonObject()) continue;
                JsonObject block = be.getAsJsonObject();
                boolean warn = block.has("warn");
                if (block.has("p") || warn) {
                    List<Stack> mentioned = new ArrayList<>();
                    List<HelpPage.Span> spans = rich(text(block.get(warn ? "warn" : "p")), mentioned);
                    blocks.add(warn ? HelpPage.Block.warning(spans, mentioned) : HelpPage.Block.paragraph(spans, mentioned));
                } else if (block.has("items")) {
                    List<Stack> items = new ArrayList<>();
                    for (Ing ing : JsonStacks.list(block.get("items"))) if (!ing.isEmpty()) items.add(ing.options.get(0));
                    if (!items.isEmpty()) blocks.add(HelpPage.Block.items(items));
                }
            }
        }
        page = new HelpPage(targets, blocks);
        return page;
    }

    /** Заменяет ссылки на названия и собирает упомянутые предметы. */
    private static List<HelpPage.Span> rich(String raw, List<Stack> mentioned) {
        List<HelpPage.Span> out = new ArrayList<>();
        Matcher m = LINK.matcher(raw);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(new HelpPage.Span(raw.substring(last, m.start()), HelpPage.Tone.BODY));
            String kind = m.group(1), target = m.group(2), label = m.group(3);
            String shown = label != null ? label : target;
            if (kind.equals("item")) {
                Ing ing = JsonStacks.ing(new JsonPrimitive(target));
                if (!ing.isEmpty()) {
                    Stack s = ing.options.get(0);
                    if (label == null) shown = s.name().getString();
                    if (mentioned.stream().noneMatch(x -> x.same(s))) mentioned.add(s);
                }
            }
            out.add(new HelpPage.Span(shown, HelpPage.Tone.LINK));
            last = m.end();
        }
        if (last < raw.length()) out.add(new HelpPage.Span(raw.substring(last), HelpPage.Tone.BODY));
        return out;
    }

    @Override
    public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick) {
        page().render(g, theme, x, y, mouseX, mouseY, HelpContent.focus());
    }

    @Override
    public Stack stackAt(int x, int y, int mouseX, int mouseY) {
        return page().stackAt(x, y, mouseX, mouseY, HelpContent.focus());
    }

    @Override
    public List<Ing> inputs() {
        return inputs;
    }

    @Override
    public List<Stack> outputs() {
        return targets;
    }

    @Override
    public boolean isCrafting() {
        return false;
    }
}
