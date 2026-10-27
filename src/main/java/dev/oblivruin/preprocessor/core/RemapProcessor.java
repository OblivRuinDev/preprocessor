package dev.oblivruin.preprocessor.core;

import dev.oblivruin.preprocessor.api.ProcessorContext;
import dev.oblivruin.preprocessor.api.TextProcessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces every occurrence of each key with its value in all lines. This is the
 * built-in {@code remapper} processor, kept compatible with the map of the same name
 * in the ToCraft preprocessor.
 */
public final class RemapProcessor implements TextProcessor {
    private final Map<String, String> mappings;

    public RemapProcessor(Map<String, String> mappings) {
        this.mappings = new LinkedHashMap<>(mappings);
    }

    @Override
    public void process(ProcessorContext context) {
        if (mappings.isEmpty()) {
            return;
        }
        List<String> lines = context.lines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> entry : mappings.entrySet()) {
                line = replaceAllLiterally(line, entry.getKey(), entry.getValue());
            }
            lines.set(i, line);
        }
    }

    /**
     * Literal replace-all without regex. Deliberately not
     * {@link String#replace(CharSequence, CharSequence)}: on Java 8 that compiles a
     * {@link java.util.regex.Pattern} on every call, which dominates the runtime for
     * large remapper maps on large files. Empty targets are ignored (the previous
     * regex based behavior would insert the replacement between every character).
     */
    static String replaceAllLiterally(String line, String target, String replacement) {
        if (target.isEmpty() || replacement == null) {
            return line;
        }
        int idx = line.indexOf(target);
        if (idx < 0) {
            return line;
        }
        StringBuilder sb = new StringBuilder(line.length() + 16);
        int from = 0;
        while (idx >= 0) {
            sb.append(line, from, idx).append(replacement);
            from = idx + target.length();
            idx = line.indexOf(target, from);
        }
        sb.append(line, from, line.length());
        return sb.toString();
    }
}

