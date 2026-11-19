package works.repka.rysindex.runtime;

import net.minecraft.client.Minecraft;

/**
 * Профили: закладки и доска целей могут быть общими на все миры или своими для каждого мира и сервера.
 */
public final class Profiles {
    private Profiles() {}

    /** Имя файла данных с учётом выбранной области. */
    public static String file(String base) {
        if (!"world".equals(Config.get().profileScope)) return base + ".json";
        return base + "-" + key() + ".json";
    }

    /** Имя файла данных, своего для каждого мира и сервера при любой области профиля. */
    public static String worldFile(String base) {
        return base + "-" + key() + ".json";
    }

    private static String key() {
        Minecraft mc = Minecraft.getInstance();
        String raw;
        if (mc.getSingleplayerServer() != null) raw = "world-" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else if (mc.getCurrentServer() != null) raw = "server-" + mc.getCurrentServer().ip;
        else raw = "global";
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '.' ? c : '_');
        return sb.length() > 60 ? sb.substring(0, 60) : sb.toString();
    }

    public static void cycleScope() {
        Config.get().profileScope = "world".equals(Config.get().profileScope) ? "global" : "world";
        Bookmarks.reset();
        Board.reset();
        RecipeDefaults.reset();
    }
}
