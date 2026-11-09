package works.repka.ryscope.runtime;

import java.util.ArrayList;
import java.util.List;
import works.repka.ryscope.model.Stack;

/** Недавно просмотренные ресурсы: последние сверху, до конца игровой сессии. */
public final class History {
    private static final int LIMIT = 18;
    private static final List<Stack> LIST = new ArrayList<>();

    private History() {}

    public static List<Stack> list() {
        return LIST;
    }

    public static void add(Stack stack) {
        LIST.removeIf(s -> s.same(stack));
        LIST.add(0, stack.withAmount(1));
        while (LIST.size() > LIMIT) LIST.remove(LIST.size() - 1);
    }

    public static void clear() {
        LIST.clear();
    }
}
