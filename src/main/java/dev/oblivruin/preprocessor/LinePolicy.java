package dev.oblivruin.preprocessor;

/**
 * Controls what happens to directive lines and to lines inside inactive branches
 * in the processed output.
 */
public enum LinePolicy {
    /**
     * Keep directive lines as comments and comment out inactive lines using the eval
     * marker (e.g. {@code //$$}). Line numbers are preserved and the output stays
     * readable - this matches the behaviour of the ReplayMod / ToCraft preprocessor
     * and is the default.
     */
    COMMENT,
    /**
     * Replace directive lines and inactive lines with empty lines. Line numbers are
     * preserved but the output contains no trace of the preprocessing.
     */
    BLANK,
    /**
     * Remove directive lines and inactive lines entirely. Produces the cleanest
     * output, but line numbers are <em>not</em> preserved.
     */
    REMOVE
}
