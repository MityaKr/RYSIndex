package works.repka.ryscope.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.model.ItemRes;
import works.repka.ryscope.model.Stack;

/**
 * Закладки игрока, разложенные по папкам. Одна папка открыта, все действия идут с ней.
 * Предметы переживают перезаход; ресурсы чужих типов живут до выхода из игры.
 */
public final class Bookmarks {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int MAX_FOLDERS = 32;

    /** Папка закладок. Пустое имя значит «без названия»: показывается общий заголовок. */
    public static final class Folder {
        public String name = "";
        public final List<Stack> items = new ArrayList<>();
    }

    private static final List<Folder> FOLDERS = new ArrayList<>();
    private static int active;
    private static boolean loaded;

    private Bookmarks() {}

    /** Закладки открытой папки. */
    public static List<Stack> list() {
        return current().items;
    }

    public static List<Folder> folders() {
        ensureLoaded();
        return FOLDERS;
    }

    public static int active() {
        ensureLoaded();
        return active;
    }

    public static Folder current() {
        ensureLoaded();
        if (FOLDERS.isEmpty()) FOLDERS.add(new Folder());
        if (active < 0 || active >= FOLDERS.size()) active = 0;
        return FOLDERS.get(active);
    }

    /** Есть ли хоть одна закладка в какой-нибудь папке. */
    public static boolean any() {
        ensureLoaded();
        for (Folder f : FOLDERS) if (!f.items.isEmpty()) return true;
        return false;
    }

    public static void setActive(int index) {
        ensureLoaded();
        if (FOLDERS.isEmpty()) return;
        active = Math.floorMod(index, FOLDERS.size());
        save();
    }

    /** Новая папка сразу становится открытой. @return false, если папок уже слишком много. */
    public static boolean addFolder(String name) {
        ensureLoaded();
        if (FOLDERS.size() >= MAX_FOLDERS) return false;
        current();
        Folder folder = new Folder();
        folder.name = name == null ? "" : name.trim();
        FOLDERS.add(folder);
        active = FOLDERS.size() - 1;
        save();
        return true;
    }

    public static void renameActive(String name) {
        current().name = name == null ? "" : name.trim();
        save();
    }

    /** Удаляет открытую папку вместе с закладками. Последняя папка не удаляется, а очищается. */
    public static void removeActive() {
        Folder folder = current();
        if (FOLDERS.size() <= 1) {
            folder.items.clear();
            folder.name = "";
        } else {
            FOLDERS.remove(active);
            if (active >= FOLDERS.size()) active = FOLDERS.size() - 1;
        }
        save();
    }

    public static boolean contains(Stack stack) {
        for (Stack s : list()) if (s.same(stack)) return true;
        return false;
    }

    /** @return true, если закладка добавлена; false, если снята. */
    public static boolean toggle(Stack stack) {
        List<Stack> list = list();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).same(stack)) {
                list.remove(i);
                save();
                return false;
            }
        }
        list.add(stack.withAmount(1));
        save();
        return true;
    }

    public static void move(int from, int to) {
        List<Stack> list = list();
        if (from < 0 || from >= list.size() || to < 0 || to >= list.size() || from == to) return;
        list.add(to, list.remove(from));
        save();
    }

    public static void reset() {
        FOLDERS.clear();
        active = 0;
        loaded = false;
    }

    private static Path file() {
        return Config.dir().resolve(Profiles.file("bookmarks"));
    }

    private static void ensureLoaded() {
        Minecraft mc = Minecraft.getInstance();
        if (loaded || mc.level == null) return;
        loaded = true;
        FOLDERS.clear();
        active = 0;
        Path file = file();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(r, JsonElement.class);
            DynamicOps<JsonElement> ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            if (root != null && root.isJsonArray()) {
                // прежний формат: один список без папок
                Folder folder = new Folder();
                readItems(root.getAsJsonArray(), ops, folder);
                FOLDERS.add(folder);
            } else if (root != null && root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("folders")) {
                    for (JsonElement fe : obj.getAsJsonArray("folders")) {
                        JsonObject fo = fe.getAsJsonObject();
                        Folder folder = new Folder();
                        if (fo.has("name")) folder.name = fo.get("name").getAsString();
                        if (fo.has("items")) readItems(fo.getAsJsonArray("items"), ops, folder);
                        FOLDERS.add(folder);
                    }
                }
                if (obj.has("active")) active = obj.get("active").getAsInt();
            }
        } catch (Exception e) {
            RYScope.LOG.warn("Не удалось прочитать закладки: {}", e.toString());
        }
    }

    private static void readItems(JsonArray array, DynamicOps<JsonElement> ops, Folder folder) {
        for (JsonElement e : array) {
            ItemStack.CODEC.parse(ops, e).result().ifPresent(s -> {
                if (!s.isEmpty()) folder.items.add(new ItemRes(s));
            });
        }
    }

    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            DynamicOps<JsonElement> ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            JsonArray folders = new JsonArray();
            for (Folder folder : FOLDERS) {
                JsonObject fo = new JsonObject();
                fo.addProperty("name", folder.name);
                JsonArray items = new JsonArray();
                for (Stack s : folder.items) {
                    ItemStack item = s.item();
                    if (item.isEmpty()) continue;
                    ItemStack.CODEC.encodeStart(ops, item).result().ifPresent(items::add);
                }
                fo.add("items", items);
                folders.add(fo);
            }
            JsonObject root = new JsonObject();
            root.addProperty("active", active);
            root.add("folders", folders);
            Files.createDirectories(Config.dir());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Exception e) {
            RYScope.LOG.warn("Не удалось сохранить закладки: {}", e.toString());
        }
    }
}
