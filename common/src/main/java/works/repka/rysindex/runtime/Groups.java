package works.repka.rysindex.runtime;

import works.repka.ryscore.ui.Draw;
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
import works.repka.rysindex.RYSIndex;
import works.repka.rysindex.model.Stack;

/**
 * Сворачиваемые группы однотипных предметов (вся шерсть, все зелья, все яйца призыва).
 * Описываются данными: {@code assets/<namespace>/rysindex_groups/<имя>.json}.
 */
public final class Groups {
    public static final String FOLDER = "rysindex_groups";

    public static final class Group {
        public final String id;
        final String name;
        final Set<String> tags = new HashSet<>();
        final Set<String> items = new HashSet<>();
        final Pattern match;
        Component autoName;

        /** Группа, найденная автоматически: у неё сразу готовое имя. */
        Group(String id, Component autoName) {
            this.id = id;
            this.name = id;
            this.autoName = autoName;
            this.match = null;
        }

        Group(String id, JsonObject json) {
            this.id = id;
            this.name = json.has("name") ? json.get("name").getAsString() : id;
            if (json.has("tags")) for (JsonElement e : json.getAsJsonArray("tags")) tags.add(e.getAsString());
            if (json.has("items")) for (JsonElement e : json.getAsJsonArray("items")) items.add(e.getAsString());
            this.match = json.has("match") ? Pattern.compile(json.get("match").getAsString()) : null;
        }

        public Component title() {
            if (autoName != null) return autoName;
            return Language.getInstance().has(name) ? Component.translatable(name) : Component.literal(name);
        }

        /** id и теги предмета считаются один раз на предмет, а не на каждое описание группы. */
        boolean accepts(String id, List<String> stackTags) {
            if (items.contains(id)) return true;
            if (match != null && match.matcher(id).matches()) return true;
            if (!tags.isEmpty()) for (String t : stackTags) if (tags.contains(t)) return true;
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
            works.repka.ryscore.theme.Theme t = works.repka.ryscore.theme.ThemeManager.get();
            g.fill(x + 3, y + 1, x + 16, y + 14, Draw.alpha(t.c("text.primary"), 0.25f));
            g.fill(x + 1, y + 3, x + 14, y + 16, Draw.alpha(t.c("text.primary"), 0.19f));
            first.render(g, x, y);
            // число в углу, как у плашек семьи
            works.repka.ryscore.ui.Kit.count(g, expanded ? "−" : Integer.toString(count), x - 2, y - 2);
        }

        @Override
        public List<Component> tooltipLines() {
            List<Component> out = new ArrayList<>();
            out.add(group.title());
            out.add(Component.translatable(expanded ? "rysindex.group.collapse" : "rysindex.group.expand", count)
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
                RYSIndex.LOG.warn("Группа {} не прочитана: {}", file, e.toString());
            }
        }
        groups = new ArrayList<>(loaded.values());
    }

    /** Раскладывает ресурсы по группам. Вызывается из потока индексации. */
    public static void assign(List<Stack> stacks) {
        List<Group> defs = groups;
        Map<Object, Group> map = new HashMap<>();
        if (!defs.isEmpty()) {
            // id и теги зависят только от самого предмета: у тысячи зелий одного предмета ответ один,
            // поэтому каждый предмет проверяется один раз
            java.util.Set<Object> seen = new HashSet<>();
            for (Stack s : stacks) {
                if (s.item().isEmpty() || !seen.add(s.key())) continue;
                String id;
                List<String> stackTags;
                try {
                    id = s.id().toString();
                    stackTags = s.tags();
                } catch (Throwable ignored) {
                    // теги чужого предмета
                    continue;
                }
                for (Group g : defs) {
                    if (g.accepts(id, stackTags)) {
                        map.put(s.key(), g);
                        break;
                    }
                }
            }
        }
        try {
            assignAutomatic(stacks, map);
        } catch (Throwable t) {
            RYSIndex.LOG.warn("Автоматические группы не построены: {}", t.toString());
        }
        byKey = map;
    }

    private static final List<String> COLORS = List.of("light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime",
        "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    /** Сколько разных цветов должно быть в семействе, чтобы его сворачивать. */
    private static final int MIN_COLORS = 4;

    /**
     * Группы без описаний: один предмет со множеством вариантов (бак с каждой жидкостью, пчела с каждым видом)
     * и семейства, отличающиеся только цветом в id. Предметы, которые уже описаны данными, не трогаем.
     */
    private static void assignAutomatic(List<Stack> stacks, Map<Object, Group> map) {
        Map<Object, List<Stack>> byItem = new LinkedHashMap<>();
        for (Stack s : stacks) {
            if (!s.item().isEmpty() && !map.containsKey(s.key())) byItem.computeIfAbsent(s.key(), k -> new ArrayList<>()).add(s);
        }
        Map<String, List<Stack>> families = new LinkedHashMap<>();
        Map<String, Set<String>> familyColors = new HashMap<>();
        for (Map.Entry<Object, List<Stack>> e : byItem.entrySet()) {
            Stack first = e.getValue().get(0);
            if (e.getValue().size() >= 3) {
                map.put(e.getKey(), new Group("auto:item:" + first.id(), first.item().getItem().getDescription()));
                continue;
            }
            String path = first.id().getPath();
            for (String color : COLORS) {
                String replaced = path.replaceFirst("(^|_)" + color + "(_|$)", "$1*$2");
                if (replaced.equals(path)) continue;
                String family = first.id().getNamespace() + ":" + replaced;
                families.computeIfAbsent(family, k -> new ArrayList<>()).add(first);
                familyColors.computeIfAbsent(family, k -> new HashSet<>()).add(color);
                break;
            }
        }
        for (Map.Entry<String, List<Stack>> e : families.entrySet()) {
            if (familyColors.get(e.getKey()).size() < MIN_COLORS) continue;
            String name = commonWords(e.getValue());
            if (name.isEmpty()) continue;
            Group group = new Group("auto:color:" + e.getKey(), Component.literal(name));
            for (Stack s : e.getValue()) map.put(s.key(), group);
        }
    }

    /** Слова, общие для всех названий семейства: цвет отличается и отпадает на любом языке. */
    private static String commonWords(List<Stack> members) {
        Set<String> common = null;
        List<String> order = new ArrayList<>();
        for (Stack s : members) {
            String[] words = s.name().getString().split("\\s+");
            Set<String> set = new HashSet<>(List.of(words));
            if (common == null) {
                common = set;
                order.addAll(List.of(words));
            } else {
                common.retainAll(set);
            }
        }
        if (common == null || common.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String word : order) {
            if (!common.contains(word)) continue;
            if (out.length() > 0) out.append(' ');
            out.append(word);
        }
        if (out.length() > 0) out.setCharAt(0, Character.toUpperCase(out.charAt(0)));
        return out.toString();
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
