package works.repka.ryscope.ui;

import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Type.Weight;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscope.runtime.Config;
import works.repka.ryscope.runtime.Indexer;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;

/** Настройки мода. Выглядят в текущей теме и применяются сразу. */
public final class SettingsScreen extends ScaledScreen {
    private static final int ROW = 20;
    private static final int PAD = Kit.PAD;

    /** @param back правая кнопка листает значение назад; null, если назад листать нечем */
    private record Option(String key, Supplier<String> value, Runnable onClick, @Nullable Runnable back, @Nullable String note) {
        Option(String key, Supplier<String> value, Runnable onClick, @Nullable String note) {
            this(key, value, onClick, null, note);
        }
    }

    /** Вместо настроек показан список скрытых запросов. */
    private boolean hiddenList;
    /** До этого момента второй клик по «Вернуть все» возвращает все запросы. */
    private long restoreArmed;

    @Nullable
    private final Screen parent;
    private final List<Option> options = new ArrayList<>();
    private int px, py, pw, ph;
    private int scroll;
    private final Kit.Hits hits = new Kit.Hits();

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("ryscope.settings.title"));
        this.parent = parent;
        Config c = Config.get();
        options.add(new Option("ryscope.settings.theme", () -> ThemeManager.get().name, ThemeManager::cycle, null));
        options.add(new Option("ryscope.settings.ui_scale", () -> works.repka.ryscore.ui.UiScale.setting() == works.repka.ryscore.ui.UiScale.AUTO
            ? tr("ryscope.settings.ui_scale.auto") : works.repka.ryscore.ui.UiScale.setting() + " %", works.repka.ryscore.ui.UiScale::cycle, "ryscope.settings.ui_scale.note"));
        options.add(new Option("ryscope.settings.density", () -> tr("ryscope.settings.density." + c.density), c::cycleDensity, () -> {
            // три значения по кругу: два шага вперёд это шаг назад
            c.cycleDensity();
            c.cycleDensity();
        }, null));
        // до 20 столбцов, как у «Авто» на широком экране
        options.add(new Option("ryscope.settings.columns", () -> c.maxColumns <= 0 ? tr("ryscope.settings.columns.auto") : Integer.toString(c.maxColumns), () -> {
            c.maxColumns = c.maxColumns <= 0 ? 5 : c.maxColumns >= 20 ? 0 : c.maxColumns + 1;
        }, () -> {
            c.maxColumns = c.maxColumns <= 0 ? 20 : c.maxColumns <= 5 ? 0 : c.maxColumns - 1;
        }, null));
        options.add(new Option("ryscope.settings.energy_unit", works.repka.ryscope.runtime.Units::energyUnit,
            works.repka.ryscope.runtime.Units::cycleEnergyUnit, "ryscope.settings.energy_unit.note"));
        toggle("ryscope.settings.bookmarks", () -> c.bookmarksPanel, () -> c.bookmarksPanel = !c.bookmarksPanel, null);
        if (Dock.available()) {
            toggle("ryscope.settings.ftb_dock", () -> c.replaceFtbSidebar, () -> c.replaceFtbSidebar = !c.replaceFtbSidebar, "ryscope.settings.ftb_dock.note");
        }
        toggle("ryscope.settings.board", () -> c.boardHud, () -> c.boardHud = !c.boardHud, "ryscope.settings.board.note");
        toggle("ryscope.settings.storages", () -> c.countStorages, () -> c.countStorages = !c.countStorages, "ryscope.settings.storages.note");
        toggle("ryscope.settings.groups", () -> c.groupItems, () -> {
            c.groupItems = !c.groupItems;
            works.repka.ryscope.runtime.SearchService.submit(works.repka.ryscope.runtime.SearchService.text());
        }, "ryscope.settings.groups.note");
        options.add(new Option("ryscope.settings.hidden", () -> Integer.toString(c.hiddenQueries.size()), () -> {
            if (c.hiddenQueries.isEmpty()) {
                Overlay.toast(tr("ryscope.settings.hidden.none"));
                return;
            }
            hiddenList = true;
            restoreArmed = 0;
            scroll = 0;
        }, "ryscope.settings.hidden.note"));
        toggle("ryscope.settings.show_hidden", () -> c.showHidden, () -> {
            c.showHidden = !c.showHidden;
            works.repka.ryscope.runtime.SearchService.submit(works.repka.ryscope.runtime.SearchService.text());
        }, "ryscope.settings.show_hidden.note");
        toggle("ryscope.settings.hide_locked", () -> c.hideLocked, () -> {
            c.hideLocked = !c.hideLocked;
            works.repka.ryscope.runtime.SearchService.submit(works.repka.ryscope.runtime.SearchService.text());
        }, "ryscope.settings.hide_locked.note");
        options.add(new Option("ryscope.settings.sort", () -> tr("ryscope.settings.sort." + works.repka.ryscope.runtime.ListSort.current()),
            works.repka.ryscope.runtime.ListSort::cycle, "ryscope.settings.sort.note"));
        toggle("ryscope.settings.mod_name", () -> c.showModName, () -> c.showModName = !c.showModName, "ryscope.settings.mod_name.note");
        toggle("ryscope.settings.search_bottom", () -> c.searchBottom, () -> c.searchBottom = !c.searchBottom, null);
        toggle("ryscope.settings.list_paged", () -> c.listPaged, () -> c.listPaged = !c.listPaged, null);
        toggle("ryscope.settings.restyle", () -> c.restyleRecipes, () -> c.restyleRecipes = !c.restyleRecipes, "ryscope.settings.restyle.note");
        toggle("ryscope.settings.animations", () -> c.animations, () -> c.animations = !c.animations, null);
        toggle("ryscope.settings.lens_world", () -> c.lensInWorld, () -> c.lensInWorld = !c.lensInWorld, null);
        toggle("ryscope.settings.fuzzy", () -> c.fuzzySearch, () -> c.fuzzySearch = !c.fuzzySearch, "ryscope.settings.fuzzy.note");
        options.add(new Option("ryscope.settings.languages", () -> tr(c.searchLanguages.contains("*") ? "ryscope.settings.languages.all" : "ryscope.settings.languages.en"), () -> {
            if (c.searchLanguages.contains("*")) {
                c.searchLanguages = new ArrayList<>(List.of("en_us"));
            } else {
                c.searchLanguages = new ArrayList<>(List.of("*"));
            }
            Indexer.invalidateLanguages();
            Indexer.request("языки поиска");
        }, "ryscope.settings.languages.note"));
        toggle("ryscope.settings.keep_search", () -> c.keepSearch, () -> c.keepSearch = !c.keepSearch, null);
        toggle("ryscope.settings.cheat", () -> c.cheatMode, () -> c.cheatMode = !c.cheatMode, "ryscope.settings.cheat.note");
        options.add(new Option("ryscope.settings.profile", () -> tr("ryscope.settings.profile." + ("world".equals(c.profileScope) ? "world" : "global")),
            works.repka.ryscope.runtime.Profiles::cycleScope, "ryscope.settings.profile.note"));
        options.add(new Option("ryscope.settings.reports", () -> tr("ryscope.settings.reports.run"), () -> {
            works.repka.ryscope.runtime.DevReports.Result r = works.repka.ryscope.runtime.DevReports.run();
            Overlay.toast(r == null ? tr("ryscope.settings.reports.failed")
                : Component.translatable("ryscope.settings.reports.done", r.conflicts(), r.duplicates(), r.unobtainable()).getString());
        }, "ryscope.settings.reports.note"));
        options.add(new Option("ryscope.settings.export", () -> tr("ryscope.settings.export.run"), () -> {
            java.nio.file.Path dir = works.repka.ryscope.runtime.Export.run();
            Overlay.toast(tr(dir == null ? "ryscope.settings.export.failed" : "ryscope.settings.export.done"));
        }, "ryscope.settings.export.note"));
        toggle("ryscope.settings.async_jei", () -> c.asyncJei && !c.asyncJeiUnsafe, () -> {
            if (c.asyncJeiUnsafe) {
                c.asyncJeiUnsafe = false;
                c.asyncJei = true;
            } else {
                c.asyncJei = !c.asyncJei;
            }
        }, c.asyncJeiUnsafe ? "ryscope.settings.async_jei.failed" : "ryscope.settings.async_jei.note");
        toggle("ryscope.settings.replace_jei", () -> c.replaceJeiInterface, () -> c.replaceJeiInterface = !c.replaceJeiInterface, "ryscope.settings.replace_jei.note");
    }

    private void toggle(String key, Supplier<Boolean> get, Runnable flip, @Nullable String note) {
        options.add(new Option(key, () -> tr(get.get() ? "ryscope.settings.on" : "ryscope.settings.off"), flip, note));
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, ThemeManager.get().c("overlay"));
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float partialTick) {
        Theme t = ThemeManager.get();
        hits.clear();
        renderBackground(g, mx, my, partialTick);
        if (hiddenList && Config.get().hiddenQueries.isEmpty()) hiddenList = false;
        int rows = hiddenList ? Config.get().hiddenQueries.size() + 1 : options.size();
        pw = Math.min(width - 12, 320);
        py = Kit.panelTop(height);
        ph = Math.min(height - py - 4, Kit.HEADER_H + 8 + rows * ROW + 8);
        px = (width - pw) / 2;
        t.panel(g, px, py, pw, ph);
        int iconX = px + PAD, iconY = py + 12, closeX = px + pw - PAD - 18;
        Kit.iconTile(g, t, iconX, iconY);
        Icons.draw(g, Icons.Shape.GEAR, iconX + 5.5f, iconY + 5.5f, 12f, t.c("text.muted"));
        Kit.close(g, t, hits, closeX, py + 14, mx, my, this::onClose);
        float textX = iconX + 23 + 7;
        String subtitle = tr(hiddenList ? "ryscope.settings.hidden.title" : "ryscope.settings.footer");
        Kit.headerText(g, t, hiddenList ? tr("ryscope.settings.hidden") : title.getString(), subtitle, textX, py, closeX - 7 - textX);
        Kit.divider(g, t, px + 0.5f, py + Kit.HEADER_H - 0.5f, pw - 1);

        int top = py + Kit.HEADER_H + 4;
        int visible = Math.max(1, (ph - Kit.HEADER_H - 8) / ROW);
        if (hiddenList) {
            drawHidden(g, t, mx, my, top, visible);
            Overlay.renderToast(this, g);
            return;
        }
        scroll = Math.max(0, Math.min(scroll, options.size() - visible));
        String note = null;
        int y = top;
        for (int i = scroll; i < Math.min(options.size(), scroll + visible); i++) {
            Option o = options.get(i);
            boolean hov = mx >= px + 5 && mx < px + pw - 5 && my >= y && my < y + ROW;
            if (hov) {
                Draw.round(g, px + 5, y, pw - 10, ROW - 1, 4.5f, Draw.alpha(t.c("slot.hover"), 0.5f));
                note = o.note;
            }
            float cy = y + ROW / 2f;
            String label = tr(o.key);
            Draw.label(g, label, px + PAD + 2, cy, 13, Weight.REGULAR, t.c("text.body"));
            if (o.note != null) {
                float lw = Draw.labelWidth(label, 13, Weight.REGULAR);
                Draw.info(g, (int) (px + PAD + 2 + lw + 5), y + 5, t.c(hov ? "accent" : "text.dim"));
            }
            String value = o.value.get();
            boolean on = value.equals(tr("ryscope.settings.on")), off = value.equals(tr("ryscope.settings.off"));
            int right = px + pw - PAD - 2;
            if (on || off) {
                Kit.toggle(g, t, right - 18, y + 5, on, hov);
            } else {
                float vw = Draw.labelWidth(value, 12, Weight.REGULAR) + 12;
                int bx = (int) (right - vw);
                Draw.roundBox(g, bx, y + 3, vw, 14, 4f, hov ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), t.c("button.border"));
                Draw.label(g, value, bx + 6, cy, 12, Weight.REGULAR, t.c("button.fg"));
            }
            hits.add(px + 5, y, pw - 10, ROW, b -> {
                // левая кнопка листает вперёд, правая назад; остальные кнопки ничего не меняют
                if (b == 0) o.onClick.run();
                else if (b == 1 && o.back != null) o.back.run();
                else return;
                Config.save();
            });
            y += ROW;
        }
        if (options.size() > visible) {
            int trackH = visible * ROW;
            int thumbH = Math.max(10, trackH * visible / options.size());
            int thumbY = top + (trackH - thumbH) * scroll / Math.max(1, options.size() - visible);
            Draw.round(g, px + pw - 4, thumbY, 2, thumbH, 1f, t.c("scroll.thumb"));
        }
        Overlay.renderToast(this, g);
        if (note != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            Kit.tooltip(g, t, List.of(tr(note)), mx, my);
            g.pose().popPose();
        }
    }

    /** Скрытые запросы по одному: крестик возвращает запрос в список, внизу «Вернуть все» со вторым кликом. */
    private void drawHidden(GuiGraphics g, Theme t, int mx, int my, int top, int visible) {
        Config c = Config.get();
        List<String> queries = c.hiddenQueries;
        int total = queries.size() + 1;
        scroll = Math.max(0, Math.min(scroll, total - visible));
        int y = top;
        for (int i = scroll; i < Math.min(total, scroll + visible); i++) {
            float cy = y + ROW / 2f;
            int right = px + pw - PAD - 2;
            if (i == queries.size()) {
                String label = tr("ryscope.settings.hidden.restore_all");
                int bw = (int) Math.ceil(Kit.buttonWidth(label, 12));
                Kit.button(g, t, hits, right - bw, y + 2, bw, ROW - 4, label, false, mx, my, () -> {
                    long now = System.currentTimeMillis();
                    if (now < restoreArmed) {
                        c.hiddenQueries.clear();
                        changed();
                    } else {
                        restoreArmed = now + 3000;
                        Overlay.toast(Component.translatable("ryscope.settings.hidden.confirm", c.hiddenQueries.size()).getString(), 3000);
                    }
                });
                break;
            }
            String query = queries.get(i);
            boolean hov = mx >= px + 5 && mx < px + pw - 5 && my >= y && my < y + ROW;
            if (hov) Draw.round(g, px + 5, y, pw - 10, ROW - 1, 4.5f, Draw.alpha(t.c("slot.hover"), 0.5f));
            int bx = right - 14, by = y + 3;
            boolean hot = Kit.over(mx, my, bx, by, 14, 14);
            Kit.iconButton(g, t, bx, by, 14, hot);
            Icons.draw(g, Icons.Shape.CROSS, bx + 3.5f, by + 3.5f, 7f, hot ? t.c("accent") : t.c("button.fg"));
            Draw.label(g, Draw.ellipsize(query, bx - 6 - (px + PAD + 2), 13, Weight.REGULAR), px + PAD + 2, cy, 13, Weight.REGULAR, t.c("text.body"));
            hits.add(bx, by, 14, 14, b -> {
                if (b != 0) return;
                c.hiddenQueries.remove(query);
                changed();
                Overlay.toast(Component.translatable("ryscope.hidden.undone", query).getString());
            });
            y += ROW;
        }
    }

    private static void changed() {
        Config.save();
        works.repka.ryscope.runtime.SearchService.submit(works.repka.ryscope.runtime.SearchService.text());
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (hits.click(mx, my, button)) return true;
        if (mx < px || mx >= px + pw || my < py || my >= py + ph) onClose();
        return true;
    }

    @Override
    protected boolean onScroll(double mx, double my, double scrollX, double scrollY) {
        scroll += scrollY < 0 ? 1 : -1;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE || Minecraft.getInstance().options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        if (hiddenList) {
            // из списка скрытых запросов крестик и Esc возвращают к настройкам
            hiddenList = false;
            scroll = 0;
            return;
        }
        Config.save();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
