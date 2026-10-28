package demo;

/**
 * The test source set overrides the encoding to ISO-8859-1 (see build.gradle) and uses
 * the BLANK output policy; compileTestJava is configured with the matching -encoding.
 * This file contains a non ASCII character in ISO-8859-1 encoding (see the string
 * literal below) - a nice check that per source set encodings round trip.
 */
public class DemoTest {

    public static String mode() {
        //#if TEST_FLAG && PLATFORM == "fabric"
        return "test-fabric café";
        //#else
        return "test-other";
        //#endif
    }
}