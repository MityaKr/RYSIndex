package works.repka.ryscope.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.Stack;

/**
 * Сворачиваемые группы однотипных предметов (вся шерсть, все зелья, все яйца призыва).
 * Описываются данными: {@code assets/<namespace>/ryscope_groups/<имя>.json}.
 */
public final class Groups {
    public static final String FOLDER = "ryscope_groups";

    public static final class Group {
        public final String id;
        final String name;
        final Set<String> tags = new HashSet<>();
        final Set<String> items = new HashSet<>();
        final Pattern match;

        Group(String id, JsonObject json) {
            this.id = id;
            this.name = json.has("name") ? json.get("name").getAsString() : id;
            if (json.has("tags")) for (JsonElement e : json.getAsJsonArray("tags")) tags.add(e.getAsString());
            if (json.has("items")) for (JsonElement e : json.getAsJsonArray("items")) items.add(e.getAsString());
            this.match = json.has("match") ? Pattern.compile(json.get("match").getAsString()) : null;
        }

        public Component title() {
            return Language.getInstance().has(name) ? Component.translatable(name) : Component.literal(name);
        }

        boolean accepts(Stack stack) {
            String id = stack.id().toString();
            if (items.contains(id)) return true;
            if (match != null && match.matcher(id).matches()) return true;
            if (!tags.isEmpty()) for (String t : stack.tags()) if (tags.contains(t)) return true;
            return false;
        }
    }

    /** Ячейка списка, за которой стоит свёрнутая или развёрнутая группа. Клик по ней переключает группу. */
    public static final class GroupStack extends Stack {
        public final Group group;
        public final Stack first;
        public int count;
        public final boolean expanded;

        GroupStack(Group group, Stack first, boolean expanded) {
            this.group = group;
            this.first = first;
            this.expanded = expanded;
        }

        @Override
        public Object key() {
            return first.key();
        }

        @Override
        public String type() {
            return first.type();
        }

        @Override
        public ResourceLocation id() {
            return first.id();
        }

        @Override
        public Component name() {
            return group.title();
        }

        @Override
        public void render(GuiGraphics g, int x, int y) {
            // «стопка карточек» за иконкой: видно, что внутри несколько предметов
            g.fill(x + 3, y + 1, x + 16, y + 14, 0x40FFFFFF);
            g.fill(x + 1, y + 3, x + 14, y + 16, 0x30FFFFFF);
            first.render(g, x, y);
            Font font = Minecraft.getInstance().font;
            String text = expanded ? "−" : Integer.toString(count);
            g.pose().pushPose();
            g.pose().translate(x + 16 - font.width(text) * 0.6f, y + 10, 200);
            g.pose().scale(0.6f, 0.6f, 1f);
            g.fill(-1, -1, font.width(text) + 1, 9, 0xD0000000);
            g.drawString(font, text, 0, 0, 0xFFFFD166, false);
            g.pose().popPose();
        }

        @Override
        public List<Component> tooltipLines() {
            List<Component> out = new ArrayList<>();
            out.add(group.title());
            out.add(Component.translatable(expanded ? "ryscope.group.collapse" : "ryscope.group.expand", count)
                .withStyle(net.minecraft.ChatFormatting.GRAY));
            return out;
        }

        @Override
        public List<String> tags() {
            return first.tags();
        }

        @Override
        public boolean same(Stack other) {
            return other instanceof GroupStack o && o.group == group;
        }

        @Override
        public Stack copy() {
            return first.copy();
        }

        @Override
        public ItemStack item() {
            return first.item();
        }
    }

    private static volatile List<Group> groups = List.of();
    private static volatile Map<Object, Group> byKey = Map.of();

    private Groups() {}

    public static void reload(ResourceManager manager) {
        Map<String, Group> loaded = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> found = manager.listResources(FOLDER, p -> p.getPath().endsWith(".json"));
        List<ResourceLocation> files = new ArrayList<>(found.keySet());
        files.sort(ResourceLocation::compareTo);
        for (ResourceLocation file : files) {
            String path = file.getPath();
            String id = file.getNamespace() + ":" + path.substring(FOLDER.length() + 1, path.length() - 5);
            try (Reader reader = found.get(file).openAsReader()) {
                loaded.put(id, new Group(id, JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception e) {
                RYScope.LOG.warn("Группа {} не прочитана: {}", file, e.toString());
            }
        }
        groups = new ArrayList<>(loaded.values());
    }

    /** Раскладывает ресурсы по группам. Вызывается из потока индексации. */
    public static void assign(List<Stack> stacks) {
        List<Group> defs = groups;
        Map<Object, Group> map = new HashMap<>();
        if (!defs.isEmpty()) {
            for (Stack s : stacks) {
                if (s.item().isEmpty()) continue;
                for (Group g : defs) {
                    try {
                        if (g.accepts(s)) {
                            map.put(s.key(), g);
                            break;
                        }
                    } catch (Throwable ignored) {
                        // теги чужого предмета
                    }
                }
            }
        }
        byKey = map;
    }

    /** Сворачивает группы в списке: от каждой свёрнутой остаётся одна ячейка со счётчиком. */
    public static List<Stack> collapse(List<Stack> list) {
        Map<Object, Group> map = byKey;
        if (map.isEmpty()) return list;
        Set<String> expanded = new HashSet<>(Config.get().expandedGroups);
        Map<Group, Integer> sizes = new HashMap<>();
        for (Stack s : list) {
            Group g = map.get(s.key());
            if (g != null) sizes.merge(g, 1, Integer::sum);
        }
        Map<Group, GroupStack> heads = new HashMap<>();
        List<Stack> out = new ArrayList<>(list.size());
        for (Stack s : list) {
            Group g = map.get(s.key());
            if (g == null || sizes.get(g) < 3) {
                out.add(s);
                continue;
            }
            GroupStack head = heads.get(g);
            boolean open = expanded.contains(g.id);
            if (head == null) {
                head = new GroupStack(g, s, open);
                head.count = sizes.get(g);
                heads.put(g, head);
                out.add(head);
            }
            if (open) out.add(s);
        }
        return out;
    }

    public static void toggle(Group group) {
        List<String> expanded = Config.get().expandedGroups;
        if (!expanded.remove(group.id)) expanded.add(group.id);
        Config.save();
    }
}
