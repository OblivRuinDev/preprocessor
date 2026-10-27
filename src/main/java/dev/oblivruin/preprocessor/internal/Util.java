package dev.oblivruin.preprocessor.internal;

/** Runtime JVM utilities. */
public final class Util {
    /** The feature version of the running JVM, e.g. {@code 8}, {@code 17}, {@code 25}. */
    public static final int JAVA = detectJavaVersion();

    private Util() {
    }

    private static int detectJavaVersion() {
        // parsed from a system property on purpose: Runtime.version() only exists on Java 9+,
        // and javac rejects references to it (and to other newer APIs) when compiling with
        // --release 8 - runtime guards do not help, the reference itself fails to compile
        String version = System.getProperty("java.version", "8");
        try {
            if (version.startsWith("1.")) {
                // legacy numbering, e.g. 1.8.0_492
                int end = version.indexOf('.', 2);
                return Integer.parseInt(end > 0 ? version.substring(2, end) : version.substring(2));
            }
            int end = version.indexOf('.');
            return Integer.parseInt(end > 0 ? version.substring(0, end) : version);
        } catch (NumberFormatException e) {
            return 8;
        }
    }
}
