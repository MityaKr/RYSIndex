package works.repka.ryscope.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

/** Закладки игрока. Предметы переживают перезаход; ресурсы чужих типов живут до выхода из игры. */
public final class Bookmarks {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Stack> LIST = new ArrayList<>();
    private static boolean loaded;

    private Bookmarks() {}

    public static List<Stack> list() {
        ensureLoaded();
        return LIST;
    }

    public static boolean contains(Stack stack) {
        ensureLoaded();
        for (Stack s : LIST) if (s.same(stack)) return true;
        return false;
    }

    /** @return true, если закладка добавлена; false, если снята. */
    public static boolean toggle(Stack stack) {
        ensureLoaded();
        for (int i = 0; i < LIST.size(); i++) {
            if (LIST.get(i).same(stack)) {
                LIST.remove(i);
                save();
                return false;
            }
        }
        LIST.add(stack.withAmount(1));
        save();
        return true;
    }

    public static void move(int from, int to) {
        ensureLoaded();
        if (from < 0 || from >= LIST.size() || to < 0 || to >= LIST.size() || from == to) return;
        LIST.add(to, LIST.remove(from));
        save();
    }

    public static void reset() {
        LIST.clear();
        loaded = false;
    }

    private static Path file() {
        return Config.dir().resolve(Profiles.file("bookmarks"));
    }

    private static void ensureLoaded() {
        Minecraft mc = Minecraft.getInstance();
        if (loaded || mc.level == null) return;
        loaded = true;
        LIST.clear();
        Path file = file();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonArray array = GSON.fromJson(r, JsonArray.class);
            DynamicOps<JsonElement> ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            for (JsonElement e : array) {
                ItemStack.CODEC.parse(ops, e).result().ifPresent(s -> {
                    if (!s.isEmpty()) LIST.add(new ItemRes(s));
                });
            }
        } catch (Exception e) {
            RYScope.LOG.warn("Не удалось прочитать закладки: {}", e.toString());
        }
    }

    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            DynamicOps<JsonElement> ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            JsonArray array = new JsonArray();
            for (Stack s : LIST) {
                ItemStack item = s.item();
                if (item.isEmpty()) continue;
                ItemStack.CODEC.encodeStart(ops, item).result().ifPresent(array::add);
            }
            Files.createDirectories(Config.dir());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(array, w);
            }
        } catch (Exception e) {
            RYScope.LOG.warn("Не удалось сохранить закладки: {}", e.toString());
        }
    }
}
