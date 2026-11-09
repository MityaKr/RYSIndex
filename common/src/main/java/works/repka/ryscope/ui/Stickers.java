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
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Type.Weight;

/** Стикеры целей из макета: название, сколько есть, сырьё и полоса. На экранах стоят рядом внизу слева. */
public final class Stickers {
    private static final int H = 44;
    private static final int GAP = 6;
    private static final int TILE = 15;
    private static final int TILE_GAP = 3;
    private static final int PAD = 7;

    private record Spot(Board.Goal goal, int x, int y, int w) {}

    private static final List<Spot> SPOTS = new ArrayList<>();

    private Stickers() {}

    private static int widthOf(Board.Goal goal, CraftTree tree) {
        long have = Math.min(goal.amount, InventoryView.count(goal.stack));
        String title = title(goal), count = have + " / " + goal.amount;
        float text = Draw.labelWidth(title, 12.5f, Weight.SEMIBOLD) + 8 + Draw.labelWidth(count, 12.5f, Weight.REGULAR);
        int tiles = Math.min(4, Math.max(1, tree.raw.size()));
        return Math.max(tiles >= 4 ? 110 : 90, (int) Math.ceil(text) + PAD * 2);
    }

    private static String title(Board.Goal goal) {
        String name = goal.stack.name().getString();
        return goal.amount > 1 ? name + " ×" + goal.amount : name;
    }

    /** Ряд стикеров. {@code bottom} это нижний край, {@code limit} правая граница, {@code avoid} окно, которое нельзя перекрывать. */
    public static void drawRow(GuiGraphics g, Theme t, int x, int bottom, int limit, @Nullable int[] avoid) {
        SPOTS.clear();
        int y = bottom - H, cx = x;
        for (Board.Goal goal : Board.goals()) {
            CraftTree tree;
            try {
                tree = goal.tree();
            } catch (Throwable th) {
                continue;
            }
            int w = widthOf(goal, tree);
            if (cx + w > limit) break;
            if (avoid != null && cx + w > avoid[0] && cx < avoid[0] + avoid[2] && y < avoid[1] + avoid[3] && y + H > avoid[1]) break;
            draw(g, t, goal, tree, cx, y, w);
            SPOTS.add(new Spot(goal, cx, y, w));
            cx += w + GAP;
        }
    }

    /** Столбец стикеров поверх мира: внизу слева там чат. */
    public static void drawColumn(GuiGraphics g, Theme t, int x, int top, int count) {
        int y = top;
        int shown = 0;
        for (Board.Goal goal : Board.goals()) {
            if (shown++ >= count) break;
            CraftTree tree;
            try {
                tree = goal.tree();
            } catch (Throwable th) {
                continue;
            }
            draw(g, t, goal, tree, x, y, widthOf(goal, tree));
            y += H + GAP;
        }
    }

    private static void draw(GuiGraphics g, Theme t, Board.Goal goal, CraftTree tree, int x, int y, int w) {
        Draw.roundBox(g, x, y, w, H, t.beveled() ? 0 : 6f, Draw.alpha(t.c("hud.bg"), 0.92f), t.c("hud.border"));
        int textColor = t.beveled() ? 0xFFFFFFFF : t.c("text.primary");
        int mutedColor = t.beveled() ? 0xFFC0C0C0 : t.c("text.muted");
        long have = Math.min(goal.amount, InventoryView.count(goal.stack));
        String count = have + " / " + goal.amount;
        float countW = Draw.labelWidth(count, 12.5f, Weight.REGULAR);
        float cy = y + 6 + 3.75f;
        Draw.label(g, Draw.ellipsize(title(goal), w - PAD * 2 - countW - 8, 12.5f, Weight.SEMIBOLD), x + PAD, cy, 12.5f, Weight.SEMIBOLD, textColor);
        Draw.label(g, count, x + w - PAD - countW, cy, 12.5f, Weight.REGULAR, mutedColor);
        int ty = y + 6 + 8 + 4;
        if (tree.raw.isEmpty()) {
            Draw.label(g, Component.translatable("ryscope.board.ready").getString(), x + PAD, ty + TILE / 2f, 12f, Weight.REGULAR, t.c("state.ok"));
        } else {
            int fits = Math.max(1, (w - PAD * 2 + TILE_GAP) / (TILE + TILE_GAP));
            int i = 0;
            for (CraftTree.Raw raw : tree.raw.values()) {
                if (i >= fits) break;
                int tx = x + PAD + i * (TILE + TILE_GAP);
                Draw.round(g, tx, ty, TILE, TILE, 3f, t.c("slot.bg"));
                g.pose().pushPose();
                g.pose().translate(tx + 1.5f, ty + 1.5f, 0);
                g.pose().scale(0.75f, 0.75f, 1f);
                raw.stack.render(g, 0, 0);
                g.pose().popPose();
                if (InventoryView.tracking() && InventoryView.count(raw.stack) == 0) {
                    // чего нет совсем, приглушено, как в макете
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 220);
                    Draw.round(g, tx, ty, TILE, TILE, 3f, Draw.alpha(t.c("hud.bg"), 0.55f));
                    g.pose().popPose();
                }
                i++;
            }
        }
        int barY = y + H - 6 - 2, barW = w - PAD * 2;
        Draw.round(g, x + PAD, barY, barW, 2, 1f, t.c("slot.bg"));
        if (have > 0) Draw.round(g, x + PAD, barY, Math.max(2f, (float) barW * have / goal.amount), 2, 1f, t.c("accent"));
    }

    @Nullable
    public static Board.Goal goalAt(double mx, double my) {
        for (Spot s : SPOTS) if (mx >= s.x && mx < s.x + s.w && my >= s.y && my < s.y + H) return s.goal;
        return null;
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
