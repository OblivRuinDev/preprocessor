package demo;

/**
 * The test source set runs with {@code strict = true} and the BLANK output policy
 * (see build.gradle): directive and inactive lines become empty lines, and unknown
 * variables would abort the build instead of warning.
 */
public class DemoTest {

    public static String mode() {
        //#if TEST_FLAG && PLATFORM == "fabric"
        return "test-fabric";
        //#else
        return "test-other";
        //#endif
    }
}
