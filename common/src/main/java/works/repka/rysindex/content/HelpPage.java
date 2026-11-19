package works.repka.rysindex.content;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;
import works.repka.rysindex.model.Stack;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Type.Weight;

/**
 * Страница справки нашим видом: слева сверху предмет на плашке слота, справа колонка текста шрифтом семьи.
 * Абзацы переносятся по словам, предупреждение лежит на подложке с полосой, упомянутые предметы встают рядом
 * под абзацем. Высота не ограничена: длинную страницу листает прокрутка карточки. Так рисуются и наши страницы
 * ({@link HelpView}), и «Сведения» JEI.
 */
public final class HelpPage {
    public static final int WIDTH = 200;
    /** Кегль текста в пикселях макета, высота строки и промежуток между абзацами в единицах. */
    static final float PX = 12f;
    static final int LINE = 10, PARA_GAP = 4;
    static final int SLOT = 18, SLOT_STEP = 20;
    /** Колонка текста начинается правее предмета страницы. */
    static final int TEXT_X = SLOT + 7, TEXT_W = WIDTH - TEXT_X;
    private static final int WARN_PAD_X = 7, WARN_PAD_Y = 3;
    private static final long CYCLE_MS = 1000L;

    /** Тон куска текста: обычный, выделенный (цвет или жирный у чужого текста), ссылка на предмет. */
    public enum Tone { BODY, STRONG, LINK }

    public record Span(String text, Tone tone) {}

    /** Абзац ({@code warn = false}), предупреждение или ряд предметов (пустые {@code spans}). */
    public record Block(List<Span> spans, boolean warn, List<Stack> items) {
        public static Block paragraph(List<Span> spans, List<Stack> mentioned) {
            return new Block(spans, false, mentioned);
        }

        public static Block warning(List<Span> spans, List<Stack> mentioned) {
            return new Block(spans, true, mentioned);
        }

        public static Block items(List<Stack> items) {
            return new Block(List.of(), false, items);
        }

        /** Пустая строка в чужом тексте: только промежуток. */
        public static Block gap() {
            return new Block(List.of(), false, List.of());
        }
    }

    record Run(String text, float x, int y, Tone tone, boolean warn) {}

    /** Ширина строки в единицах интерфейса: в игре шрифт темы, в юнит-тестах простая мера. */
    interface Measure {
        float width(String s);
    }

    private static final Measure FONT = s -> Draw.labelWidth(s, PX, Weight.REGULAR);

    private record Spot(Stack stack, int x, int y) {}

    /** Предметы страницы: показываются по очереди в слоте слева сверху. */
    private final List<Stack> targets;
    private final List<Block> blocks;
    private String layoutTheme;
    private final List<Run> runs = new ArrayList<>();
    private final List<Spot> spots = new ArrayList<>();
    private final List<int[]> warnBoxes = new ArrayList<>();
    private int height = SLOT;
    /** Короткий текст стоит по середине предмета страницы, а не прижат к его верху. */
    private int shift;

    public HelpPage(List<Stack> targets, List<Block> blocks) {
        this.targets = targets;
        this.blocks = blocks;
    }

    public int height() {
        layout();
        return height;
    }

    /** Весь текст страницы одной строкой: для автотеста и поиска. */
    public String plainText() {
        StringBuilder out = new StringBuilder();
        for (Block b : blocks) {
            if (b.spans.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            for (Span s : b.spans) out.append(s.text);
        }
        return out.toString();
    }

    /** Раскладка считается при первом показе и при смене темы: ширина текста зависит от шрифта темы. */
    private void layout() {
        Theme theme = ThemeManager.get();
        if (theme.id.equals(layoutTheme)) return;
        layoutTheme = theme.id;
        runs.clear();
        spots.clear();
        warnBoxes.clear();
        int y = 0;
        boolean first = true;
        for (Block block : blocks) {
            if (!first) y += PARA_GAP;
            first = false;
            if (block.spans.isEmpty() && block.items.isEmpty()) continue;
            if (!block.spans.isEmpty()) {
                int indent = block.warn ? WARN_PAD_X : 0;
                int top = y;
                if (block.warn) y += WARN_PAD_Y;
                int lines = flow(block.spans, TEXT_X + indent, y, TEXT_W - indent - (block.warn ? 3 : 0), block.warn, runs, FONT);
                y += lines * LINE;
                if (block.warn) {
                    y += WARN_PAD_Y;
                    warnBoxes.add(new int[] {top, y - top});
                }
            }
            if (!block.items.isEmpty()) {
                if (!block.spans.isEmpty()) y += 2;
                y = itemRows(block.items, y);
            }
        }
        shift = y < SLOT ? (SLOT - y) / 2 : 0;
        height = Math.max(SLOT, y);
    }

    /** Ряды предметов по ширине колонки текста. @return низ последнего ряда */
    private int itemRows(List<Stack> items, int y) {
        int x = 0;
        for (Stack s : items) {
            if (x > 0 && x + SLOT > TEXT_W) {
                x = 0;
                y += SLOT_STEP;
            }
            spots.add(new Spot(s, TEXT_X + x, y));
            x += SLOT_STEP;
        }
        return y + SLOT;
    }

    /** Слово из одного или нескольких кусков разного тона без пробела между ними («предмет,»). */
    record Piece(String text, Tone tone) {}

    static List<List<Piece>> words(List<Span> spans) {
        List<List<Piece>> out = new ArrayList<>();
        List<Piece> word = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (Span span : spans) {
            String s = span.text;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (chunk.length() > 0) {
                        word.add(new Piece(chunk.toString(), span.tone));
                        chunk.setLength(0);
                    }
                    if (!word.isEmpty()) {
                        out.add(word);
                        word = new ArrayList<>();
                    }
                } else {
                    chunk.append(c);
                }
            }
            if (chunk.length() > 0) {
                word.add(new Piece(chunk.toString(), span.tone));
                chunk.setLength(0);
            }
        }
        if (!word.isEmpty()) out.add(word);
        return out;
    }

    /**
     * Перенос по словам в колонку ширины {@code width}. Соседние куски одного тона в строке сливаются в один вызов
     * отрисовки. Слово длиннее строки режется по буквам. @return число строк
     */
    static int flow(List<Span> spans, int x0, int y0, int width, boolean warn, List<Run> out, Measure measure) {
        float space = measure.width(" ");
        int line = 0;
        float x = 0;
        boolean empty = true;
        StringBuilder run = new StringBuilder();
        Tone runTone = null;
        float runX = 0;
        for (List<Piece> word : words(spans)) {
            float w = 0;
            for (Piece p : word) w += measure.width(p.text);
            if (!empty && x + space + w > width) {
                if (run.length() > 0) out.add(new Run(run.toString(), x0 + runX, y0 + line * LINE, runTone, warn));
                run.setLength(0);
                runTone = null;
                line++;
                x = 0;
                empty = true;
            }
            if (w > width) {
                // слово шире колонки (ссылка, длинный id): по буквам
                for (Piece p : word) {
                    for (int i = 0; i < p.text.length(); ) {
                        int cp = p.text.codePointAt(i);
                        String ch = new String(Character.toChars(cp));
                        i += Character.charCount(cp);
                        float cw = measure.width(ch);
                        if (!empty && x + cw > width) {
                            if (run.length() > 0) out.add(new Run(run.toString(), x0 + runX, y0 + line * LINE, runTone, warn));
                            run.setLength(0);
                            runTone = null;
                            line++;
                            x = 0;
                        }
                        if (runTone != p.tone) {
                            if (run.length() > 0) out.add(new Run(run.toString(), x0 + runX, y0 + line * LINE, runTone, warn));
                            run.setLength(0);
                            runTone = p.tone;
                            runX = x;
                        }
                        run.append(ch);
                        x += cw;
                        empty = false;
                    }
                }
                continue;
            }
            boolean spaced = !empty;
            if (spaced) x += space;
            for (Piece p : word) {
                if (runTone != p.tone) {
                    if (run.length() > 0) out.add(new Run(run.toString(), x0 + runX, y0 + line * LINE, runTone, warn));
                    run.setLength(0);
                    runTone = p.tone;
                    runX = x;
                } else if (spaced && run.length() > 0) {
                    run.append(' ');
                }
                spaced = false;
                run.append(p.text);
                x += measure.width(p.text);
            }
            empty = false;
        }
        if (run.length() > 0) out.add(new Run(run.toString(), x0 + runX, y0 + line * LINE, runTone, warn));
        return empty && line == 0 ? 0 : line + 1;
    }

    /** Предмет в слоте страницы сейчас: фокус карточки, если он среди предметов страницы, иначе по очереди. */
    @Nullable
    private Stack shownTarget(@Nullable Stack focus) {
        if (targets.isEmpty()) return null;
        if (focus != null) {
            for (Stack s : targets) if (s.same(focus)) return s;
        }
        return targets.get((int) ((System.currentTimeMillis() / CYCLE_MS) % targets.size()));
    }

    private static int color(Theme theme, Tone tone, boolean warn) {
        return switch (tone) {
            case LINK -> theme.c("accent");
            case STRONG -> theme.c("text.primary");
            case BODY -> warn ? theme.c("text.primary") : theme.c("text.body");
        };
    }

    public void render(GuiGraphics g, Theme theme, int x, int y, int mouseX, int mouseY, @Nullable Stack focus) {
        layout();
        Stack target = shownTarget(focus);
        if (target != null) {
            theme.slot(g, x, y, SLOT, over(mouseX, mouseY, x, y));
            target.render(g, x + 1, y + 1);
        }
        int ty = y + shift;
        for (int[] box : warnBoxes) {
            Draw.round(g, x + TEXT_X, ty + box[0], TEXT_W, box[1], 2f, Draw.alpha(theme.c("state.warn"), 0.16f));
            // предупреждение помечено полосой слева, а не только цветом текста
            Draw.rect(g, x + TEXT_X, ty + box[0], 2, box[1], theme.c("state.warn"));
        }
        for (Run r : runs) {
            Draw.label(g, r.text, x + r.x, ty + r.y + LINE / 2f, PX, Weight.REGULAR, color(theme, r.tone, r.warn));
        }
        for (Spot s : spots) {
            int sx = x + s.x, sy = ty + s.y;
            theme.slot(g, sx, sy, SLOT, over(mouseX, mouseY, sx, sy));
            s.stack.render(g, sx + 1, sy + 1);
        }
    }

    private static boolean over(int mouseX, int mouseY, int sx, int sy) {
        return mouseX >= sx && mouseX < sx + SLOT && mouseY >= sy && mouseY < sy + SLOT;
    }

    @Nullable
    public Stack stackAt(int x, int y, int mouseX, int mouseY, @Nullable Stack focus) {
        if (over(mouseX, mouseY, x, y)) return shownTarget(focus);
        layout();
        for (Spot s : spots) {
            if (over(mouseX, mouseY, x + s.x, y + shift + s.y)) return s.stack;
        }
        return null;
    }
}
