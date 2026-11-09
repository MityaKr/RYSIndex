package works.repka.ryscope.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import works.repka.ryscope.model.Ing;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.ui.Draw;
import works.repka.ryscope.ui.theme.Theme;
import works.repka.ryscope.ui.theme.ThemeManager;

/**
 * Страница справки. Блоки: {@code p} (абзац), {@code warn} (предупреждение), {@code items} (ряд предметов).
 * В тексте работают ссылки {@code [[item:id]]} (название предмета, сам предмет показывается под абзацем)
 * и {@code [[что-угодно|подпись]]}.
 */
public final class HelpView implements RecipeView {
    private static final int WIDTH = 200;
    private static final int MAX_HEIGHT = 150;
    private static final Pattern LINK = Pattern.compile("\\[\\[([a-z_]+):([^\\]|]+)(?:\\|([^\\]]+))?]]");

    private record Line(FormattedCharSequence text, int x, int y, boolean warn) {}

    private record Spot(Stack stack, int x, int y) {}

    private final ResourceLocation id;
    private final List<Stack> targets;
    private final JsonArray body;
    private final List<Ing> inputs;

    private String layoutTheme;
    private final List<Line> lines = new ArrayList<>();
    private final List<Spot> spots = new ArrayList<>();
    private final List<int[]> warnBoxes = new ArrayList<>();
    private int height = 20;

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
        return WIDTH;
    }

    @Override
    public int height() {
        layout();
        return height;
    }

    private static String text(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return "";
        String s = e.getAsString();
        return Language.getInstance().has(s) ? Language.getInstance().getOrDefault(s) : s;
    }

    /** Раскладка считается при первом показе и при смене темы: ей нужен шрифт и цвета. */
    private void layout() {
        Theme theme = ThemeManager.get();
        if (theme.id.equals(layoutTheme)) return;
        layoutTheme = theme.id;
        lines.clear();
        spots.clear();
        warnBoxes.clear();
        Font font = Minecraft.getInstance().font;
        int y = 0;
        if (body != null) {
            for (JsonElement be : body) {
                if (!be.isJsonObject() || y > MAX_HEIGHT - 10) continue;
                JsonObject block = be.getAsJsonObject();
                boolean warn = block.has("warn");
                if (block.has("p") || warn) {
                    List<Stack> mentioned = new ArrayList<>();
                    MutableComponent text = rich(text(block.get(warn ? "warn" : "p")), theme, warn, mentioned);
                    int indent = warn ? 7 : 0;
                    int top = y;
                    for (FormattedCharSequence seq : font.split(text, WIDTH - indent - 2)) {
                        if (y > MAX_HEIGHT - 10) break;
                        lines.add(new Line(seq, indent, y + (warn ? 3 : 0), warn));
                        y += 10;
                    }
                    if (warn) {
                        warnBoxes.add(new int[] {top, y - top + 5});
                        y += 5;
                    }
                    if (!mentioned.isEmpty() && y <= MAX_HEIGHT - 20) {
                        int x = 0;
                        for (Stack s : mentioned) {
                            if (x + 20 > WIDTH) break;
                            spots.add(new Spot(s, x, y + 1));
                            x += 20;
                        }
                        y += 22;
                    }
                    y += 3;
                } else if (block.has("items")) {
                    int x = 0;
                    for (Ing ing : JsonStacks.list(block.get("items"))) {
                        if (x + 20 > WIDTH) break;
                        spots.add(new Spot(ing.options.get(0), x, y + 1));
                        x += 20;
                    }
                    y += 23;
                }
            }
        }
        height = Math.max(20, Math.min(MAX_HEIGHT, y));
    }

    /** Заменяет ссылки на названия и собирает упомянутые предметы. */
    private static MutableComponent rich(String raw, Theme theme, boolean warn, List<Stack> mentioned) {
        int base = (warn ? theme.c("text.primary") : theme.c("text.body")) & 0xFFFFFF;
        int accent = theme.c("accent") & 0xFFFFFF;
        MutableComponent out = Component.empty();
        Matcher m = LINK.matcher(raw);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.append(Component.literal(raw.substring(last, m.start())).withStyle(Style.EMPTY.withColor(base)));
            String kind = m.group(1), target = m.group(2), label = m.group(3);
            String shown = label != null ? label : target;
            if (kind.equals("item")) {
                Ing ing = JsonStacks.ing(new com.google.gson.JsonPrimitive(target));
                if (!ing.isEmpty()) {
                    Stack s = ing.options.get(0);
                    if (label == null) shown = s.name().getString();
                    if (mentioned.stream().noneMatch(x -> x.same(s))) mentioned.add(s);
                }
            }
            out.append(Component.literal(shown).withStyle(Style.EMPTY.withColor(accent)));
            last = m.end();
        }
        if (last < raw.length()) out.append(Component.literal(raw.substring(last)).withStyle(Style.EMPTY.withColor(base)));
        return out;
    }

    @Override
    public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, float partialTick) {
        layout();
        Font font = Minecraft.getInstance().font;
        for (int[] box : warnBoxes) {
            Draw.round(g, x, y + box[0], WIDTH, box[1], 2f, Draw.alpha(theme.c("state.warn"), 0.16f));
            // предупреждение помечено полосой слева, а не только цветом текста
            Draw.rect(g, x, y + box[0], 2, box[1], theme.c("state.warn"));
        }
        for (Line line : lines) {
            g.drawString(font, line.text, x + line.x, y + line.y, 0xFFFFFFFF, false);
        }
        for (Spot s : spots) {
            int sx = x + s.x, sy = y + s.y;
            boolean hover = mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18;
            theme.slot(g, sx, sy, 18, hover);
            s.stack.render(g, sx + 1, sy + 1);
        }
    }

    @Override
    public Stack stackAt(int x, int y, int mouseX, int mouseY) {
        for (Spot s : spots) {
            int sx = x + s.x, sy = y + s.y;
            if (mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18) return s.stack;
        }
        return null;
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
