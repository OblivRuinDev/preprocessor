package demo.integration;

/**
 * The integration source set is declared in the preprocessor configuration with
 * {@code enabled = false} (see build.gradle): no preprocessIntegrationJava task
 * exists, nothing is attached, and this ORIGINAL file is compiled verbatim - which
 * is why it contains no live preprocessor directives.
 */
public final class Integration {

    private Integration() {
    }

    public static String name() {
        return "integration (never preprocessed)";
    }
}
