package demo.legacy;

/** The legacy API class; the remapper rewrites references to {@code demo.api.NewApi}. */
public final class OldApi {

    private OldApi() {
    }

    public static String name() {
        return "legacy api";
    }
}
