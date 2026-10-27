package dev.oblivruin.preprocessor.api;

import dev.oblivruin.preprocessor.Keywords;

import java.util.List;
import java.util.Map;

/**
 * The context a {@link TextProcessor} receives for a single file.
 */
public interface ProcessorContext {
    /** @return the path of the file relative to its source directory */
    String fileName();

    /** @return the path of the file relative to its source directory (same as {@link #fileName()}) */
    String relativePath();

    /** @return the lowercased file extension without the dot, or an empty string */
    String suffix();

    /** @return the keywords in effect for this file */
    Keywords keywords();

    /** @return the mutable current lines of the file */
    List<String> lines();

    /** @return read/write access to the preprocessing variables (including file-local overrides) */
    Variables variables();

    /** @return a per-file scratch area, shared with custom directives */
    Map<String, String> meta();

    /** Reports a non-fatal warning; it is surfaced through the Gradle logger. */
    void warn(String message);
}
