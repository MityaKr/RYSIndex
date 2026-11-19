package works.repka.rysindex.core.search;

/** Строки одного элемента списка для поиска. Всё в нижнем регистре. */
public final class SearchEntry {
    public final int index;
    public final String name;
    public final String[] altNames;
    public final String id;
    public final String namespace;
    public final String modName;
    /**
     * Мод для поиска через @ в сжатом виде ({@link Query#squash}): id мода, его название и псевдонимы по отдельности,
     * без пробелов и пунктуации, чтобы «@applied energistics 2» и «@ae2» находили одно и то же.
     */
    public final String[] modKeys;
    public final String[] tags;
    /** Тултип считается лениво: null значит «ещё не посчитан». */
    public volatile String tooltip;

    public SearchEntry(int index, String name, String[] altNames, String id, String namespace, String modName, String[] tags) {
        this(index, name, altNames, id, namespace, modName, new String[] {Query.squash(namespace), Query.squash(modName)}, tags);
    }

    public SearchEntry(int index, String name, String[] altNames, String id, String namespace, String modName, String[] modKeys, String[] tags) {
        this.modKeys = modKeys;
        this.index = index;
        this.name = name;
        this.altNames = altNames;
        this.id = id;
        this.namespace = namespace;
        this.modName = modName;
        this.tags = tags;
    }
}
