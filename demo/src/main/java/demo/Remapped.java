package demo;

/**
 * The remapper rewrites the {@code demo.legacy.OldApi} reference to
 * {@code demo.api.NewApi} in the processed output (see build.gradle).
 */
public final class Remapped {

    private Remapped() {
    }

    public static String api() {
        return demo.legacy.OldApi.name();
    }
}
