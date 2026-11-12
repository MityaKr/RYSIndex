package works.repka.ryscope.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.runtime.Board;
import works.repka.ryscope.runtime.CraftTree;
import works.repka.ryscope.runtime.InventoryView;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Anchors;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Type.Weight;

/** Стикеры целей из макета: название, сколько есть, сырьё и полоса. На экранах стоят рядом внизу слева. */
public final class Stickers {
    private static final int H = 44;
    private static final int GAP = 6;
    private static final int TILE = 15;
    private static final int TILE_GAP = 3;
    private static final int PAD = 7;

    /** Стикер на экране: цель или сумма по всем целям ({@code goal == null}). */
    private record Spot(@Nullable Board.Goal goal, int x, int y, int w) {}

    /** Что рисует стикер: заголовок, счётчик справа, доля для полосы и дерево с сырьём. */
    private record Card(@Nullable Board.Goal goal, String title, String count, float progress, @Nullable CraftTree tree) {}

    private static final List<Spot> SPOTS = new ArrayList<>();

    private Stickers() {}

    /** Стикеры по настройке: по одному на цель или один общий «Все цели». */
    private static List<Card> cards() {
        List<Card> out = new ArrayList<>();
        List<Board.Goal> goals = Board.goals();
        if (goals.isEmpty()) return out;
        if ("sum".equals(works.repka.ryscope.runtime.Config.get().stickerMode)) {
            int done = 0;
            for (Board.Goal goal : goals) if (InventoryView.count(goal.stack) >= goal.amount) done++;
            out.add(new Card(null, Component.translatable("ryscope.tree.all_goals").getString(), done + " / " + goals.size(),
                (float) done / goals.size(), Board.combined()));
            return out;
        }
        for (Board.Goal goal : goals) {
            CraftTree tree;
            try {
                tree = goal.tree();
            } catch (Throwable th) {
                continue;
            }
            long have = Math.min(goal.amount, InventoryView.count(goal.stack));
            out.add(new Card(goal, title(goal), have + " / " + goal.amount, (float) have / goal.amount, tree));
        }
        return out;
    }

    private static int widthOf(Card card) {
        CraftTree tree = card.tree();
        float text = Draw.labelWidth(card.title(), 12.5f, Weight.SEMIBOLD) + 8 + Draw.labelWidth(card.count(), 12.5f, Weight.REGULAR);
        int tiles = tree == null ? 1 : Math.min(4, Math.max(1, tree.raw.size() + tree.ordered.size()));
        int width = Math.max(tiles >= 4 ? 110 : 90, (int) Math.ceil(text) + PAD * 2);
        if (tree != null && !tree.ordered.isEmpty()) {
            // место под «из них N закажет сеть» справа от плашек, хотя бы две плашки слева
            float order = Draw.labelWidth(orderedLabel(tree), 12f, Weight.REGULAR);
            width = Math.max(width, (int) Math.ceil(order) + 6 + 2 * (TILE + TILE_GAP) + PAD * 2);
        }
        return width;
    }

    private static String orderedLabel(CraftTree tree) {
        return Component.translatable("ryscope.board.ordered", tree.ordered.size()).getString();
    }

    private static String title(Board.Goal goal) {
        String name = goal.stack.name().getString();
        return goal.amount > 1 ? name + " ×" + goal.amount : name;
    }

    /** Высота ряда стикеров над экранами с инвентарём, 0, если целей нет. */
    public static int rowHeight() {
        return Board.goals().isEmpty() ? 0 : H;
    }

    /** Ряд стикеров. {@code bottom} это нижний край, {@code limit} правая граница, {@code avoid} окно, которое нельзя перекрывать. */
    public static void drawRow(GuiGraphics g, Theme t, int x, int bottom, int limit, @Nullable int[] avoid) {
        SPOTS.clear();
        int y = bottom - H, cx = x;
        for (Card card : cards()) {
            int w = widthOf(card);
            if (cx + w > limit) break;
            if (avoid != null && cx + w > avoid[0] && cx < avoid[0] + avoid[2] && y < avoid[1] + avoid[3] && y + H > avoid[1]) break;
            draw(g, t, card, cx, y, w);
            SPOTS.add(new Spot(card.goal(), cx, y, w));
            cx += w + GAP;
        }
    }

    /** Столбец стикеров поверх мира в углу, заданном настройкой кора. */
    public static void drawAnchored(GuiGraphics g, Theme t, int count) {
        record Row(Card card, int w) {}
        List<Row> rows = new ArrayList<>();
        int widest = 0;
        for (Card card : cards()) {
            if (rows.size() >= count) break;
            int w = widthOf(card);
            widest = Math.max(widest, w);
            rows.add(new Row(card, w));
        }
        if (rows.isEmpty()) return;
        int[] at = Anchors.place(Anchors.Element.STICKERS, widest, rows.size() * H + (rows.size() - 1) * GAP, 4, 4);
        boolean right = Anchors.corner(Anchors.Element.STICKERS) == Anchors.Corner.TOP_RIGHT || Anchors.corner(Anchors.Element.STICKERS) == Anchors.Corner.BOTTOM_RIGHT;
        int y = at[1];
        for (Row row : rows) {
            draw(g, t, row.card, right ? at[0] + widest - row.w : at[0], y, row.w);
            y += H + GAP;
        }
    }

    private static void draw(GuiGraphics g, Theme t, Card card, int x, int y, int w) {
        CraftTree tree = card.tree();
        Draw.roundBox(g, x, y, w, H, t.beveled() ? 0 : 6f, Draw.alpha(t.c("hud.bg"), 0.92f), t.c("hud.border"));
        // в скошенной теме стикер тёмный, как подсказка: текст цветом HUD, приглушённый смешан с фоном
        int textColor = t.beveled() ? t.c("hud.fg") : t.c("text.primary");
        int mutedColor = t.beveled() ? Draw.mix(t.c("hud.fg"), t.c("hud.bg"), 0.25f) : t.c("text.muted");
        String count = card.count();
        float countW = Draw.labelWidth(count, 12.5f, Weight.REGULAR);
        float cy = y + 6 + 3.75f;
        Draw.label(g, Draw.ellipsize(card.title(), w - PAD * 2 - countW - 8, 12.5f, Weight.SEMIBOLD), x + PAD, cy, 12.5f, Weight.SEMIBOLD, textColor);
        Draw.label(g, count, x + w - PAD - countW, cy, 12.5f, Weight.REGULAR, mutedColor);
        int ty = y + 6 + 8 + 4;
        if (tree == null) {
            // первое дерево цели ещё строится по частям в тике
            Draw.label(g, Draw.ellipsize(Component.translatable("ryscope.board.computing").getString(), w - PAD * 2, 12f, Weight.REGULAR), x + PAD, ty + TILE / 2f, 12f, Weight.REGULAR, mutedColor);
        } else if (tree.raw.isEmpty() && tree.ordered.isEmpty()) {
            Draw.label(g, Component.translatable("ryscope.board.ready").getString(), x + PAD, ty + TILE / 2f, 12f, Weight.REGULAR, t.c("state.ok"));
        } else {
            int room = w - PAD * 2;
            if (!tree.ordered.isEmpty()) {
                // недостающее, которое сеть AE2 сделает сама: плашки с шестерёнкой и подпись справа
                String label = orderedLabel(tree);
                float lw = Draw.labelWidth(label, 12f, Weight.REGULAR);
                Draw.label(g, label, x + w - PAD - lw, ty + TILE / 2f, 12f, Weight.REGULAR, t.c("state.order"));
                room -= (int) Math.ceil(lw) + 6;
            }
            int fits = Math.max(1, (room + TILE_GAP) / (TILE + TILE_GAP));
            int i = 0;
            for (CraftTree.Raw raw : tree.raw.values()) {
                if (i >= fits) break;
                tile(g, t, raw, x + PAD + i * (TILE + TILE_GAP), ty, false);
                i++;
            }
            for (CraftTree.Raw raw : tree.ordered.values()) {
                if (i >= fits) break;
                tile(g, t, raw, x + PAD + i * (TILE + TILE_GAP), ty, true);
                i++;
            }
        }
        int barY = y + H - 6 - 2, barW = w - PAD * 2;
        Draw.round(g, x + PAD, barY, barW, 2, 1f, t.c("slot.bg"));
        if (card.progress() > 0) Draw.round(g, x + PAD, barY, Math.max(2f, barW * Math.min(1f, card.progress())), 2, 1f, t.c("accent"));
    }

    private static void tile(GuiGraphics g, Theme t, CraftTree.Raw raw, int tx, int ty, boolean order) {
        Draw.round(g, tx, ty, TILE, TILE, 3f, t.c("slot.bg"));
        g.pose().pushPose();
        g.pose().translate(tx + 1.5f, ty + 1.5f, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        raw.stack.render(g, 0, 0);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(0, 0, 220);
        if (order) {
            Kit.indicator(g, t, tx + TILE - 5.5f, ty + 0.5f, 5f, Kit.State.ORDER, -1, -1);
        } else if (InventoryView.tracking() && InventoryView.count(raw.stack) == 0) {
            // чего нет совсем, приглушено, как в макете
            Draw.round(g, tx, ty, TILE, TILE, 3f, Draw.alpha(t.c("hud.bg"), 0.55f));
        }
        g.pose().popPose();
    }

    @Nullable
    public static Board.Goal goalAt(double mx, double my) {
        for (Spot s : SPOTS) if (mx >= s.x && mx < s.x + s.w && my >= s.y && my < s.y + H) return s.goal;
        return null;
    }

    /** Под курсором стикер «Все цели» (настройка «Стикер: сумма»). */
    public static boolean sumAt(double mx, double my) {
        for (Spot s : SPOTS) if (s.goal == null && mx >= s.x && mx < s.x + s.w && my >= s.y && my < s.y + H) return true;
        return false;
    }

    @Nullable
    public static Stack stackAt(double mx, double my) {
        Board.Goal goal = goalAt(mx, my);
        return goal == null ? null : goal.stack;
    }

    public static void forget() {
        SPOTS.clear();
    }

    /** Для автотеста: центр первого стикера. */
    @Nullable
    public static int[] firstCenter() {
        return SPOTS.isEmpty() ? null : new int[] {SPOTS.get(0).x + SPOTS.get(0).w / 2, SPOTS.get(0).y + H / 2};
    }

    static boolean any() {
        return !SPOTS.isEmpty() && Minecraft.getInstance().screen != null;
    }
}
