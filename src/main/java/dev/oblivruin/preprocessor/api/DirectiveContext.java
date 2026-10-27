package dev.oblivruin.preprocessor.api;

import java.util.Map;

/**
 * The execution context a {@link DirectiveHandler} receives for a single directive line.
 */
public interface DirectiveContext {
    /** @return the directive word as written in the file (without the prefix) */
    String directive();

    /** @return the text after the directive word, trimmed */
    String argument();

    /** @return the path of the file currently being processed (relative to its source directory) */
    String fileName();

    /** @return the 1-based line number of the directive line */
    int lineNumber();

    /** @return {@code true} if the directive was reached from an active branch */
    boolean active();

    /** @return the live active state, re-evaluated after block mutations done by the handler */
    boolean currentlyActive();

    /** @return read/write access to the preprocessing variables */
    Variables variables();

    /**
     * Emits a line into the output. Lines emitted while {@link #currentlyActive()} is
     * {@code false} are discarded.
     */
    void emit(String line);

    /** Replaces the directive line in the output with the given text. */
    void replaceLine(String line);

    /** Removes the directive line from the output entirely. */
    void dropLine();

    /** @return control over conditional blocks, usable for custom block directives */
    BlockControl blocks();

    /** @return a per-file scratch area, shared with text processors */
    Map<String, String> meta();

    /** Reports a non-fatal warning; it is surfaced through the Gradle logger. */
    void warn(String message);

    /** Aborts preprocessing of the file with an error that includes file and line. */
    void fail(String message);
}
