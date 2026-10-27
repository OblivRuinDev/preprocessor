package dev.oblivruin.preprocessor.api;

import java.util.Map;

/**
 * Read/write access to the preprocessing variables of a single file.
 *
 * <p>Reads resolve through the scope chain (file-local overrides, then task variables),
 * writes always go to the file-local overlay that {@code //#var} and custom directives
 * create.</p>
 */
public interface Variables {
    /** @return {@code true} if a value (including an empty string) is bound to the name */
    boolean defined(String name);

    /** @return the value bound to the name, or {@code null} if the variable is undefined */
    Object get(String name);

    /** @return the value bound to the name, or {@code defaultValue} if the variable is undefined */
    Object getOrDefault(String name, Object defaultValue);

    /** Binds a file-local value that shadows task level variables from this point on. */
    void set(String name, Object value);

    /** Removes the file-local binding (task level variables become visible again). */
    void unset(String name);

    /** @return a snapshot of all effective variables (task level merged with file-local) */
    Map<String, Object> asMap();
}
