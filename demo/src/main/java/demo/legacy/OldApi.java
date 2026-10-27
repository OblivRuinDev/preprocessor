package demo.legacy;

/**
 * This file lives below {@code demo/legacy/}, which the build script excludes - it is
 * copied verbatim into the output. That is visible in the result: the //#tag directive
 * below is NOT executed and stays a plain comment line.
 */
public final class OldApi {

    //#tag excluded

    private OldApi() {
    }

    public static String name() {
        return "legacy api";
    }
}
