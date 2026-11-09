package works.repka.ryscope.ui;

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
import works.repka.ryscope.ui.theme.Theme;
import works.repka.ryscope.ui.theme.ThemeManager;

/** Настройки мода. Выглядят в текущей теме и применяются сразу. */
public final class SettingsScreen extends Screen {
    private static final int ROW = 20;
    private static final int PAD = 12;

    private record Option(String key, Supplier<String> value, Runnable onClick, @Nullable String note) {}

    @Nullable
    private final Screen parent;
    private final List<Option> options = new ArrayList<>();
    private int px, py, pw, ph;
    private int scroll;

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("ryscope.settings.title"));
        this.parent = parent;
        Config c = Config.get();
        options.add(new Option("ryscope.settings.theme", () -> ThemeManager.get().name, ThemeManager::cycle, null));
        options.add(new Option("ryscope.settings.density", () -> tr("ryscope.settings.density." + c.density), c::cycleDensity, null));
        options.add(new Option("ryscope.settings.columns", () -> c.maxColumns <= 0 ? tr("ryscope.settings.columns.auto") : Integer.toString(c.maxColumns), () -> {
            c.maxColumns = c.maxColumns <= 0 ? 5 : c.maxColumns >= 12 ? 0 : c.maxColumns + 1;
        }, null));
        options.add(new Option("ryscope.settings.energy_unit", works.repka.ryscope.runtime.Units::energyUnit,
            works.repka.ryscope.runtime.Units::cycleEnergyUnit, "ryscope.settings.energy_unit.note"));
        toggle("ryscope.settings.bookmarks", () -> c.bookmarksPanel, () -> c.bookmarksPanel = !c.bookmarksPanel, null);
        toggle("ryscope.settings.board", () -> c.boardHud, () -> c.boardHud = !c.boardHud, "ryscope.settings.board.note");
        toggle("ryscope.settings.groups", () -> c.groupItems, () -> {
            c.groupItems = !c.groupItems;
            works.repka.ryscope.runtime.SearchService.submit(works.repka.ryscope.runtime.SearchService.text());
        }, "ryscope.settings.groups.note");
        options.add(new Option("ryscope.settings.hidden", () -> Integer.toString(c.hiddenQueries.size()), () -> {
            c.hiddenQueries.clear();
            works.repka.ryscope.runtime.SearchService.submit(works.repka.ryscope.runtime.SearchService.text());
        }, "ryscope.settings.hidden.note"));
        toggle("ryscope.settings.hide_locked", () -> c.hideLocked, () -> {
            c.hideLocked = !c.hideLocked;
            works.repka.ryscope.runtime.SearchService.submit(works.repka.ryscope.runtime.SearchService.text());
        }, "ryscope.settings.hide_locked.note");
        toggle("ryscope.settings.recent", () -> c.recentPanel, () -> c.recentPanel = !c.recentPanel, null);
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
            Overlay.toast(r == null ? tr("ryscope.settings.export.failed")
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
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme theme = ThemeManager.get();
        Font font = Minecraft.getInstance().font;
        renderBackground(g, mx, my, partialTick);
        pw = Math.min(width - 12, 300);
        ph = Math.min(height - 8, PAD * 2 + 18 + options.size() * ROW + 14);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        theme.panel(g, px, py, pw, ph);
        Draw.text(g, title.getString(), px + PAD, py + PAD, theme.c("text.primary"));
        String version = "RYScope";
        Draw.textScaled(g, version, px + pw - PAD - font.width(version) * 0.75f, py + PAD + 2, 0.75f, theme.c("text.dim"));

        String note = null;
        int y = py + PAD + 18;
        int visible = Math.max(1, (ph - PAD * 2 - 18 - 12) / ROW);
        scroll = Math.max(0, Math.min(scroll, options.size() - visible));
        for (int i = scroll; i < Math.min(options.size(), scroll + visible); i++) {
            Option o = options.get(i);
            boolean hov = mx >= px + PAD - 4 && mx < px + pw - PAD + 4 && my >= y && my < y + ROW;
            if (hov) {
                Draw.round(g, px + PAD - 4, y, pw - PAD * 2 + 8, ROW - 1, theme.r("slot.radius"), Draw.alpha(theme.c("slot.hover"), 0.35f));
                note = o.note;
            }
            Draw.text(g, tr(o.key), px + PAD, y + 6, theme.c("text.body"));
            String value = o.value.get();
            int vw = font.width(value) + 12;
            int vx = px + pw - PAD - vw;
            boolean on = value.equals(tr("ryscope.settings.on"));
            theme.button(g, vx, y + 3, vw, 14, hov, on);
            Draw.text(g, value, vx + 6, y + 6, on ? theme.c("accent.text") : theme.c("text.body"));
            y += ROW;
        }
        if (options.size() > visible) {
            int trackH = visible * ROW;
            int thumbH = Math.max(10, trackH * visible / options.size());
            int thumbY = py + PAD + 18 + (trackH - thumbH) * scroll / Math.max(1, options.size() - visible);
            Draw.round(g, px + pw - 5, thumbY, 2, thumbH, 1f, theme.c("scroll.thumb"));
        }
        Overlay.renderToast(this, g);
        String footer = note != null ? tr(note) : tr("ryscope.settings.footer");
        Draw.textScaled(g, Draw.ellipsize(footer, (int) ((pw - PAD * 2) / 0.75f)), px + PAD, py + ph - 11, 0.75f, theme.c("text.dim"));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int y = py + PAD + 18;
        int visible = Math.max(1, (ph - PAD * 2 - 18 - 12) / ROW);
        for (int i = scroll; i < Math.min(options.size(), scroll + visible); i++) {
            Option o = options.get(i);
            if (mx >= px + PAD - 4 && mx < px + pw - PAD + 4 && my >= y && my < y + ROW) {
                o.onClick.run();
                Config.save();
                return true;
            }
            y += ROW;
        }
        if (mx < px || mx >= px + pw || my < py || my >= py + ph) {
            onClose();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
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
        Config.save();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
