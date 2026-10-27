package dev.oblivruin.preprocessor.core;

/** Receives warning messages produced during preprocessing. */
public interface WarningSink {
    /** @param message the complete, already located warning message */
    void warn(String message);
}
