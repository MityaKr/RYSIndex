package works.repka.ryscope.core.search;

/** Строки одного элемента списка для поиска. Всё в нижнем регистре. */
public final class SearchEntry {
    public final int index;
    public final String name;
    public final String[] altNames;
    public final String id;
    public final String namespace;
    public final String modName;
    public final String[] tags;
    /** Тултип считается лениво: null значит «ещё не посчитан». */
    public volatile String tooltip;

    public SearchEntry(int index, String name, String[] altNames, String id, String namespace, String modName, String[] tags) {
        this.index = index;
        this.name = name;
        this.altNames = altNames;
        this.id = id;
        this.namespace = namespace;
        this.modName = modName;
        this.tags = tags;
    }
}
