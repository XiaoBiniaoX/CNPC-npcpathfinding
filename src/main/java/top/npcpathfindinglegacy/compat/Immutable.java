package top.npcpathfindinglegacy.compat;

import java.util.*;

/** Java 8 equivalents of immutable collection factories used by frozen inputs. */
public final class Immutable {
    private Immutable() {}
    @SafeVarargs public static <T> List<T> list(T... items) { return copyList(Arrays.asList(items)); }
    @SafeVarargs public static <T> Set<T> set(T... items) { return copySet(Arrays.asList(items)); }
    public static <T> List<T> copyList(Collection<? extends T> items) {
        return Collections.unmodifiableList(new ArrayList<T>(items));
    }
    public static <T> Set<T> copySet(Collection<? extends T> items) {
        return Collections.unmodifiableSet(new HashSet<T>(items));
    }
}
