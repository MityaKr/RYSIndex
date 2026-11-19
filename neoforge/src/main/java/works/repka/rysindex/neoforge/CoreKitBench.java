package works.repka.rysindex.neoforge;

import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Type.Weight;

/**
 * Стенд автотеста для нового в коре 1.9.0: линии, пунктир, ломаные, дуги, кольца, значки и виджеты Kit
 * (выпадающий список, флажок, выбор «один из», панелька, окно-вопрос). Режим {@link Mode#LINES} — замер кадра
 * с тысячами наклонных отрезков, как на полном отдалении карты.
 */
final class CoreKitBench extends ScaledScreen {
    enum Mode { KIT, DIALOG, LINES, COMPACT }

    static Mode mode = Mode.KIT;
    static long cpu, count, calls;
    static String clicked = "";

    private final Kit.Hits hits = new Kit.Hits();
    private final Kit.Dropdown dropdown = new Kit.Dropdown();
    private final Kit.Dialog dialog;
    private boolean checkA = true, checkB;
    private int radioPick = 1;
    private static float[] roads;
    private static int[] roadColors;
    private static float[] nodes;

    CoreKitBench() {
        super(Component.literal("corekit"));
        dropdown.open = true;
        dialog = new Kit.Dialog("Сохранить 3 правки?")
            .text("Вы закрываете книгу, а в черновике остались правки. Игроки увидят их только после сохранения.")
            .body(26, (g, x, y, w, mx, my) -> {
                Theme t = ThemeManager.get();
                String[] rows = {"«Электромеханизм»: новая награда", "связь «Электромеханизм» и «Конденсатор»", "новый квест «Катушка возбуждения»"};
                Icons.Shape[] shapes = {Icons.Shape.PENCIL, Icons.Shape.ROUTE, Icons.Shape.PLUS};
                for (int i = 0; i < rows.length; i++) {
                    Icons.draw(g, shapes[i], x, y + i * 8.5f + 0.75f, 6.5f, t.c("text.secondary"));
                    Draw.label(g, rows[i], x + 10, y + i * 8.5f + 4f, 12.5f, Weight.REGULAR, t.c("text.secondary"));
                }
            })
            .no("Остаться", () -> clicked = "no")
            .button("Не сохранять", () -> clicked = "discard")
            .yes("Сохранить", () -> clicked = "yes");
        if (mode == Mode.COMPACT) useCompactBase();
    }

    static void reset() {
        cpu = 0;
        count = 0;
        calls = 0;
    }

    static String report() {
        return count == 0 ? "нет кадров" : String.format(java.util.Locale.ROOT, "процессор %.0f мкс на кадр, вызовов кора %d, кадров %d",
            cpu / 1000.0 / count, calls / count, count);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float partialTick) {
        Theme t = ThemeManager.get();
        hits.clear();
        Draw.rect(g, 0, 0, width, height, t.c("map.bg"));
        if (mode == Mode.LINES) {
            lines(g, t);
            return;
        }
        if (mode == Mode.COMPACT) {
            t.panel(g, 10, 10, width - 20, height - 20);
            Draw.label(g, "база " + baseWidth() + "×" + baseHeight() + ", экран " + width + "×" + height + ", сжатая " + compact(),
                20, 24, 13, Weight.MEDIUM, t.c("text.primary"));
            return;
        }
        primitives(g, t);
        icons(g, t);
        widgets(g, t, mx, my);
        if (mode == Mode.DIALOG) Kit.dialog(g, t, hits, dialog, mx, my);
    }

    private void primitives(GuiGraphics g, Theme t) {
        t.panel(g, 8, 8, 200, 384);
        int ink = t.c("text.primary");
        // веер линий разной толщины
        float cx = 60, cy = 62;
        float[] widths = {0.5f, 1f, 2f, 4f};
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            float w = widths[i % widths.length];
            Draw.line(g, cx + (float) Math.cos(a) * 8, cy + (float) Math.sin(a) * 8, cx + (float) Math.cos(a) * 46, cy + (float) Math.sin(a) * 46, w, t.category(i));
        }
        // пунктир
        Draw.dashed(g, 118, 22, 198, 40, 1f, 4, 3, ink);
        Draw.dashed(g, 118, 34, 198, 70, 1.5f, 6, 3, t.c("map.node.done"));
        Draw.dashed(g, 118, 50, 198, 100, 0.75f, 2, 2, t.c("link"));
        // ломаная и острый угол
        float[] zig = {20, 140, 50, 115, 80, 150, 110, 112, 140, 150, 170, 120, 195, 128};
        Draw.polyline(g, zig, 7, false, 3f, t.c("accent"));
        float[] tri = {30, 170, 90, 200, 40, 230};
        Draw.polyline(g, tri, 3, true, 1.5f, t.c("state.ok"));
        // дуги: поворот ствола
        Draw.arc(g, 140, 210, 30, 180, 180, 2f, t.c("text.secondary"));
        Draw.arc(g, 140, 210, 20, -90, 270, 4f, t.c("medal.gold"));
        // кольца любой толщины и круг
        Draw.ring(g, 40, 280, 22, 0.75f, ink);
        Draw.ring(g, 40, 280, 15, 3f, t.c("medal.silver"));
        Draw.ring(g, 95, 280, 22, 11f, t.c("medal.bronze"));
        Draw.disc(g, 150, 280, 18, t.c("coin"));
        Draw.ring(g, 150, 280, 23, 1.5f, t.c("map.node.open"));
        // узлы карты: точка, кольцо вехи, «ты здесь»
        for (int i = 0; i < 12; i++) {
            float x = 24 + i * 15, y = 330;
            Draw.dot(g, (int) x - 2, (int) y - 2, 5, i % 3 == 0 ? t.c("map.node.done") : i % 3 == 1 ? t.c("map.node.open") : t.c("map.node.locked"));
            if (i % 4 == 0) Draw.ring(g, x + 0.5f, y + 0.5f, 5.5f, 0.75f, t.c("map.node.open"));
        }
        Draw.label(g, "линии, пунктир, ломаные, дуги, кольца", 16, 370, 11.5f, Weight.REGULAR, t.c("text.muted"));
    }

    private void icons(GuiGraphics g, Theme t) {
        t.panel(g, 216, 8, 200, 168);
        Icons.Shape[] all = Icons.Shape.values();
        for (int i = 0; i < all.length; i++) {
            int col = i % 10, row = i / 10;
            Icons.draw(g, all[i], 226 + col * 18.5f, 18 + row * 18, 12, t.c("text.primary"));
        }
        for (int i = 17; i < all.length; i++) {
            int col = (i - 17) % 11, row = (i - 17) / 11;
            Icons.draw(g, all[i], 226 + col * 17, 98 + row * 14, 8, t.c("text.secondary"));
        }
        for (int i = 0; i < 12; i++) Draw.round(g, 226 + i * 15.5f, 150, 12, 12, 3, t.category(i));
    }

    private void widgets(GuiGraphics g, Theme t, int mx, int my) {
        t.panel(g, 424, 8, 208, 384);
        int x = 434;
        Kit.section(g, t, "Раздел", x, 22);
        Kit.dropdown(g, t, hits, dropdown, x + 40, 15, 130, "IV Энергия и разум", true, mx, my);
        Kit.checkbox(g, t, hits, x, 186, checkA, mx, my, () -> checkA = !checkA);
        Draw.label(g, "Флажок отмечен", x + 14, 190.5f, 12.5f, Weight.REGULAR, t.c("text.body"));
        Kit.checkbox(g, t, hits, x, 200, checkB, mx, my, () -> checkB = !checkB);
        Draw.label(g, "Флажок пуст", x + 14, 204.5f, 12.5f, Weight.REGULAR, t.c("text.body"));
        Kit.checkbox(g, t, hits, x, 214, 8, true, true, mx, my, () -> {});
        Draw.label(g, "Круглый, как в «Первых шагах»", x + 14, 218f, 12.5f, Weight.REGULAR, t.c("text.body"));
        Kit.checkbox(g, t, hits, x, 228, true, mx, my, null);
        Draw.label(g, "Неактивный", x + 14, 232.5f, 12.5f, Weight.REGULAR, t.c("text.muted"));
        for (int i = 0; i < 3; i++) {
            int index = i;
            Kit.radio(g, t, hits, x + 110, 186 + i * 14, 7, radioPick == i, mx, my, () -> radioPick = index);
            Draw.label(g, "вариант " + (i + 1), x + 122, 189.5f + i * 14, 12.5f, Weight.REGULAR, t.c("text.body"));
        }
        String[] titles = {"По порядку: этап за этапом", "Пока не знаю"};
        for (int i = 0; i < 2; i++) {
            int index = i;
            int ry = 248 + i * 34;
            Kit.radioRow(g, t, hits, x, ry, 188, 30, radioPick == 3 + i, mx, my, () -> radioPick = 3 + index);
            Icons.draw(g, i == 0 ? Icons.Shape.ROUTE : Icons.Shape.SPARK, x + 8, ry + 8, 14, t.c("text.secondary"));
            Draw.label(g, titles[i], x + 30, ry + 11, 12.5f, Weight.SEMIBOLD, t.c("text.primary"));
            Draw.label(g, "строка с кружком, выбрана одна", x + 30, ry + 20, 11, Weight.REGULAR, t.c("text.muted"));
        }
        // якорь панельки и сама панелька (как легенда карты)
        Kit.iconButton(g, t, x + 170, 330, 16, true);
        Icons.draw(g, Icons.Shape.COMPASS, x + 174, 334, 8, t.c("text.primary"));
        Kit.popover(g, t, x + 170, 330, 16, 16, 150, 70, Kit.Side.BELOW, new int[] {424, 8, 208, 384}, (pg, px, py, pw, ph) -> {
            Kit.popoverTitle(pg, t, "Как читать карту", px + Kit.POP_PAD_X, py + Kit.POP_PAD_Y + 6);
            String[] rows = {"выполнено", "можно делать сейчас", "пока закрыто"};
            int[] colors = {t.c("map.node.done"), t.c("map.node.open"), t.c("map.node.locked")};
            for (int i = 0; i < rows.length; i++) {
                float ry = py + Kit.POP_PAD_Y + 20 + i * 13;
                Draw.disc(pg, px + Kit.POP_PAD_X + 3, ry, 3, colors[i]);
                Draw.label(pg, rows[i], px + Kit.POP_PAD_X + 12, ry, 12, Weight.REGULAR, t.c("text.secondary"));
            }
        });
        Draw.label(g, "монеты: ", x, 380, 12, Weight.REGULAR, t.c("text.muted"));
        Draw.disc(g, x + 40, 380, 3.5f, t.c("coin"));
        Draw.label(g, "214", x + 46, 380, 12, Weight.SEMIBOLD, t.c("text.primary"));
        Draw.label(g, "Забрать", x + 80, 380, 12, Weight.MEDIUM, t.c("link"));
        Icons.draw(g, Icons.Shape.WARN, x + 130, 375, 9, t.c("state.warn"));
        Draw.roundBox(g, x + 142, 372, 46, 16, 4, t.c("callout.bg"), t.c("callout.border"));
        Draw.label(g, "важно", x + 148, 380, 11.5f, Weight.REGULAR, t.c("text.body"));
        // раскрытый список последним: лежит поверх всего
        List<Kit.Option> options = List.of(new Kit.Option("Жетоны", 1), new Kit.Option("I Механика", 6), new Kit.Option("II Индустрия", 4),
            new Kit.Option("III Машины", 5), new Kit.Option("IV Энергия и разум", 3), new Kit.Option("V Магия и жизнь", 3),
            new Kit.Option("VI Высокие технологии", 3), new Kit.Option("VII Атом и квант", "2", false), new Kit.Option("IX Творение", 1));
        Kit.dropdownList(g, t, hits, dropdown, options, 4, 125, mx, my, i -> clicked = "option " + i);
    }

    private void lines(GuiGraphics g, Theme t) {
        if (roads == null) {
            Random r = new Random(42);
            int n = 3200;
            roads = new float[n * 4];
            roadColors = new int[n];
            nodes = new float[4900 * 2];
            for (int i = 0; i < nodes.length; i += 2) {
                nodes[i] = 10 + r.nextFloat() * 620;
                nodes[i + 1] = 10 + r.nextFloat() * 380;
            }
            for (int i = 0; i < n; i++) {
                int a = r.nextInt(nodes.length / 2), b = Math.min(nodes.length / 2 - 1, a + 1 + r.nextInt(6));
                roads[i * 4] = nodes[a * 2];
                roads[i * 4 + 1] = nodes[a * 2 + 1];
                roads[i * 4 + 2] = nodes[b * 2];
                roads[i * 4 + 3] = nodes[b * 2 + 1];
                roadColors[i] = i % 5 == 0 ? t.c("map.node.done") : 0xFF3A4150;
            }
        }
        long t0 = System.nanoTime();
        works.repka.ryscore.ui.Batch.takeDrawCalls();
        Draw.lines(g, roads, roadColors, roads.length / 4, 0.6f);
        for (int i = 0; i < nodes.length; i += 2) Draw.dot(g, (int) nodes[i] - 1, (int) nodes[i + 1] - 1, 3, i % 10 == 0 ? t.c("map.node.done") : t.c("map.node.locked"));
        works.repka.ryscore.ui.Batch.flush();
        cpu += System.nanoTime() - t0;
        calls += works.repka.ryscore.ui.Batch.takeDrawCalls();
        count++;
    }

    @Override
    protected boolean onClick(double x, double y, int button) {
        return hits.click(x, y, button) || super.onClick(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (mode == Mode.DIALOG && dialog.keyPressed(key)) return true;
        if (dropdown.keyPressed(key)) return true;
        return super.keyPressed(key, scan, mods);
    }
}
