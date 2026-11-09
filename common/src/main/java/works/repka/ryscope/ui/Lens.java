package works.repka.ryscope.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscope.client.Keys;
import works.repka.ryscope.content.LootContent;
import works.repka.ryscope.content.SourceView;
import works.repka.ryscope.model.Category;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.RecipeView;
import works.repka.ryscope.model.Stack;
import works.repka.ryscope.platform.Platform;
import works.repka.ryscope.runtime.Bookmarks;
import works.repka.ryscope.runtime.Config;
import works.repka.ryscope.runtime.InventoryView;
import works.repka.ryscope.runtime.Lookup;
import works.repka.ryscope.ui.theme.Theme;
import works.repka.ryscope.ui.theme.ThemeManager;

/**
 * Линза: зажал клавишу и навёл на что угодно. В интерфейсе показывает карточку ресурса под курсором,
 * в мире — блока или существа под прицелом.
 */
public final class Lens {
    private static final int PAD = 7;

    private record Info(Stack stack, int recipes, int uses, @Nullable RecipeView preview, @Nullable Category previewCategory,
                        List<String> tags, List<String> facts, @Nullable String subject) {}

    private static Info cached;
    private static Object cachedKey;
    private static long cachedAt;

    private Lens() {}

    public static boolean held() {
        return Keys.isHeld(Keys.LENS);
    }

    /** Нужно ли прятать обычную подсказку игры: линза её заменяет. */
    public static boolean suppressTooltip() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen != null && Overlay.active(mc.screen) && held();
    }

    private static Info info(Stack stack, List<String> facts, @Nullable String subject) {
        return info(stack, facts, subject, null, false);
    }

    /**
     * @param source источник (дроп моба или блока), если цель им является
     * @param preferSource показывать источник вместо рецепта (для существ)
     */
    private static Info info(Stack stack, List<String> facts, @Nullable String subject, @Nullable SourceView source, boolean preferSource) {
        long now = System.currentTimeMillis();
        if (cached != null && cached.stack.same(stack) && now - cachedAt < 2000 && java.util.Objects.equals(cachedKey, subject)) return cached;
        int recipes = 0, uses = 0;
        RecipeView preview = null;
        Category previewCategory = null;
        try {
            List<Lookup.Group> r = Lookup.find(stack, Lookup.Mode.RECIPES);
            for (Lookup.Group g : r) recipes += g.recipes().size();
            if (!r.isEmpty() && !r.get(0).recipes().isEmpty()) {
                preview = r.get(0).recipes().get(0);
                previewCategory = r.get(0).category();
                if (preview.width() > 190 || preview.height() > 90) preview = null;
            }
            for (Lookup.Group g : Lookup.find(stack, Lookup.Mode.USES)) uses += g.recipes().size();
        } catch (Throwable ignored) {
            // линза не должна ломаться из-за чужого источника
        }
        if (source != null && (preferSource || preview == null) && source.height() <= 110) {
            preview = source;
            previewCategory = null;
        }
        List<String> tags = stack.tags();
        cached = new Info(stack, recipes, uses, preview, previewCategory, tags, facts, subject);
        cachedKey = subject;
        cachedAt = now;
        return cached;
    }

    /** Карточка ресурса рядом с курсором. */
    public static void renderCard(GuiGraphics g, Theme theme, Stack stack, int mx, int my, int sw, int sh) {
        Info info = info(stack, List.of(), null);
        int[] size = measure(info);
        int x = mx + 12, y = my - 12;
        if (x + size[0] > sw - 4) x = mx - 12 - size[0];
        if (x < 4) x = 4;
        if (y + size[1] > sh - 4) y = sh - 4 - size[1];
        if (y < 4) y = 4;
        g.pose().pushPose();
        g.pose().translate(0, 0, 350);
        draw(g, theme, info, x, y, size[0], size[1], mx, my);
        g.pose().popPose();
    }

    private static int[] measure(Info info) {
        Font font = Minecraft.getInstance().font;
        int w = Math.max(150, font.width(info.stack.name()) + 22 + PAD * 2);
        if (info.subject != null) w = Math.max(w, font.width(info.subject) + PAD * 2);
        int h = PAD + 20;
        if (info.subject != null) h += 11;
        if (!info.tags.isEmpty()) h += 12;
        if (!info.facts.isEmpty()) h += 12;
        if (info.preview != null) {
            int plate = info.preview.needsPlate() ? 3 : 0;
            w = Math.max(w, info.preview.width() + plate * 2 + PAD * 2);
            h += 4 + 9 + info.preview.height() + plate * 2 + 4;
        }
        h += 11 + 9 + PAD;
        w = Math.min(w, 230);
        return new int[] {w, h};
    }

    private static void draw(GuiGraphics g, Theme theme, Info info, int x, int y, int w, int h, int mx, int my) {
        Font font = Minecraft.getInstance().font;
        theme.tooltipBox(g, x, y, w, h);
        int cy = y + PAD;
        int inner = w - PAD * 2;
        if (info.subject != null) {
            Draw.text(g, Draw.ellipsize(info.subject, inner), x + PAD, cy, theme.c("accent"));
            cy += 11;
        }
        info.stack.render(g, x + PAD, cy);
        Draw.text(g, Draw.ellipsize(info.stack.name().getString(), inner - 22), x + PAD + 21, cy, theme.c("text.primary"));
        String mod = Platform.get().modName(info.stack.namespace());
        String id = info.stack.id().toString();
        Draw.textScaled(g, Draw.ellipsize(mod + " · " + id, (int) ((inner - 22) / 0.75f)), x + PAD + 21, cy + 10, 0.75f, theme.c("text.muted"));
        cy += 20;

        if (!info.tags.isEmpty()) {
            int tx = x + PAD;
            int shown = 0;
            for (String tag : info.tags) {
                String label = "#" + tag;
                int tw = Math.round(font.width(label) * 0.75f) + 8;
                if (tx + tw > x + w - PAD - 16) {
                    if (shown > 0) break;
                    // даже один длинный тег лучше показать обрезанным, чем не показать
                    label = Draw.ellipsize(label, (int) ((w - PAD * 2 - 26) / 0.75f));
                    tw = Math.round(font.width(label) * 0.75f) + 8;
                }
                theme.chip(g, tx, cy, tw, 10, "tag");
                Draw.textScaled(g, label, tx + 4, cy + 2, 0.75f, theme.c("chip.tag.fg"));
                tx += tw + 3;
                shown++;
            }
            if (shown < info.tags.size()) {
                Draw.textScaled(g, "+" + (info.tags.size() - shown), tx + 1, cy + 2, 0.75f, theme.c("text.dim"));
            }
            cy += 12;
        }
        if (!info.facts.isEmpty()) {
            int tx = x + PAD;
            for (String fact : info.facts) {
                int tw = Math.round(font.width(fact) * 0.75f) + 8;
                if (tx + tw > x + w - PAD) break;
                theme.chip(g, tx, cy, tw, 10, "fn");
                Draw.textScaled(g, fact, tx + 4, cy + 2, 0.75f, theme.c("chip.fn.fg"));
                tx += tw + 3;
            }
            cy += 12;
        }

        if (info.preview != null) {
            cy += 4;
            String title = info.previewCategory != null ? info.previewCategory.title.getString()
                : info.preview instanceof SourceView ? Component.translatable("ryscope.lens.drops").getString() : "";
            Draw.textScaled(g, title, x + PAD, cy, 0.75f, theme.c("text.muted"));
            cy += 9;
            int plate = info.preview.needsPlate() ? 3 : 0;
            int px = x + (w - info.preview.width()) / 2, py = cy + plate;
            if (plate > 0) theme.plate(g, px - plate, py - plate, info.preview.width() + plate * 2, info.preview.height() + plate * 2);
            try {
                info.preview.render(g, theme, px, py, -1000, -1000, 0);
            } catch (Throwable ignored) {
                // чужой рецепт
            }
            cy += info.preview.height() + plate * 2 + 4;
        }

        String counts = Component.translatable("ryscope.lens.counts", info.recipes, info.uses).getString();
        if (InventoryView.tracking()) {
            int have = InventoryView.count(info.stack);
            if (have > 0) counts += " · " + Component.translatable("ryscope.lens.have", have).getString();
            long stored = info.stack.key() instanceof net.minecraft.world.item.Item item ? works.repka.ryscope.runtime.Storages.count(item) : 0;
            if (stored > 0) counts += " · " + Component.translatable("ryscope.lens.stored", stored).getString();
        }
        Draw.text(g, Draw.ellipsize(counts, inner), x + PAD, cy, theme.c("text.body"));
        cy += 11;
        String hint = Component.translatable("ryscope.lens.hint", Keys.label(Keys.RECIPES), Keys.label(Keys.USES), Keys.label(Keys.BOOKMARK)).getString();
        Draw.textScaled(g, Draw.ellipsize(hint, (int) (inner / 0.75f)), x + PAD, cy, 0.75f, theme.c("text.dim"));
    }

    // --- мир ---

    private record Target(Stack stack, List<String> facts, @Nullable String subject, @Nullable SourceView source, boolean preferSource) {}

    @Nullable
    private static Target worldTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        HitResult hit = pick(mc);
        if (hit == null) return null;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            BlockState state = mc.level.getBlockState(pos);
            ItemStack pick = state.getBlock().getCloneItemStack(mc.level, pos, state);
            if (pick.isEmpty()) pick = new ItemStack(state.getBlock().asItem());
            if (pick.isEmpty()) return null;
            return new Target(new ItemRes(pick), blockFacts(state, pos), null, LootContent.forBlock(state.getBlock()), false);
        }
        if (hit instanceof EntityHitResult ehr) {
            Entity entity = ehr.getEntity();
            ItemStack pick;
            if (entity instanceof ItemEntity item) pick = item.getItem();
            else if (entity instanceof ItemFrame frame && !frame.getItem().isEmpty()) pick = frame.getItem();
            else pick = entity.getPickResult();
            SourceView drops = LootContent.forEntity(entity.getType());
            if (pick == null || pick.isEmpty()) {
                // у существа нет предмета-представителя: показываем хотя бы то, что из него выпадает
                if (drops == null || drops.drops().isEmpty()) return null;
                pick = drops.drops().get(0).stack().item();
                if (pick.isEmpty()) return null;
            }
            return new Target(new ItemRes(pick), List.of(), entity.getDisplayName().getString(), drops, true);
        }
        return null;
    }

    /** Линза смотрит дальше руки: до 24 блоков, чтобы не подходить к мобу или руде вплотную. */
    @Nullable
    private static HitResult pick(Minecraft mc) {
        Entity cam = mc.getCameraEntity();
        if (cam == null) return mc.hitResult;
        double range = 24;
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(true);
        HitResult block = cam.pick(range, pt, false);
        Vec3 eye = cam.getEyePosition(pt);
        Vec3 look = cam.getViewVector(pt);
        Vec3 end = eye.add(look.scale(range));
        double limit = block.getType() == HitResult.Type.MISS ? range * range : block.getLocation().distanceToSqr(eye);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(cam, eye, end,
            cam.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0), e -> !e.isSpectator() && e.isPickable(), limit);
        return entity != null ? entity : block;
    }

    /** Чем добывается блок: то, что игрок обычно выясняет методом проб. */
    private static List<String> blockFacts(BlockState state, BlockPos pos) {
        List<String> out = new ArrayList<>();
        String tool = null;
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) tool = "ryscope.lens.tool.pickaxe";
        else if (state.is(BlockTags.MINEABLE_WITH_AXE)) tool = "ryscope.lens.tool.axe";
        else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) tool = "ryscope.lens.tool.shovel";
        else if (state.is(BlockTags.MINEABLE_WITH_HOE)) tool = "ryscope.lens.tool.hoe";
        if (tool != null) out.add(Component.translatable(tool).getString());
        String tier = null;
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) tier = "ryscope.lens.tier.diamond";
        else if (state.is(BlockTags.NEEDS_IRON_TOOL)) tier = "ryscope.lens.tier.iron";
        else if (state.is(BlockTags.NEEDS_STONE_TOOL)) tier = "ryscope.lens.tier.stone";
        if (tier != null) out.add(Component.translatable(tier).getString());
        Minecraft mc = Minecraft.getInstance();
        float hardness = state.getDestroySpeed(mc.level, pos);
        if (hardness < 0) out.add(Component.translatable("ryscope.lens.unbreakable").getString());
        return out;
    }

    /** Карточка цели в мире. Вызывается из слоя HUD. */
    public static void renderHud(GuiGraphics g, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui || !Config.get().lensInWorld || !held()) return;
        Target target = worldTarget();
        if (target == null) return;
        Theme theme = ThemeManager.get();
        Info info = info(target.stack, target.facts, target.subject == null ? "" : target.subject, target.source, target.preferSource);
        Info shown = new Info(info.stack, info.recipes, info.uses, info.preview, info.previewCategory, info.tags, target.facts, target.subject);
        int[] size = measure(shown);
        int sw = g.guiWidth(), sh = g.guiHeight();
        int x = sw / 2 + 14;
        int y = Math.max(4, Math.min(sh - size[1] - 4, sh / 2 - size[1] / 2));
        if (x + size[0] > sw - 4) x = sw - 4 - size[0];
        draw(g, theme, shown, x, y, size[0], size[1], -1000, -1000);
    }

    /** Клавиши рецептов и закладки по цели в мире, пока зажата линза. */
    public static boolean onWorldKey(int key, int scan) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || !Config.get().lensInWorld || !held()) return false;
        Target target = worldTarget();
        if (target == null) return false;
        if (Keys.matchesRaw(Keys.RECIPES, key, scan)) return RecipeScreen.open(target.stack, Lookup.Mode.RECIPES);
        if (Keys.matchesRaw(Keys.USES, key, scan)) return RecipeScreen.open(target.stack, Lookup.Mode.USES);
        if (Keys.matchesRaw(Keys.BOOKMARK, key, scan)) {
            Bookmarks.toggle(target.stack);
            return true;
        }
        return false;
    }
}
