package dev.oblivruin.preprocessor.api;

/**
 * A file level transformation registered with
 * {@code preprocessor.processors.register(id, processor)}.
 *
 * <p>Processors run <em>after</em> the directive scan. The built-in chain contains the
 * directive scan itself (always first) and the {@code remapper} processor; custom
 * processors can be ordered relative to those anchors.</p>
 */
@FunctionalInterface
public interface TextProcessor {
    /**
     * Transforms the current lines of the file. The list is mutable; changes are
     * written to the processed output.
     *
     * @param context information about the file and its variables
     */
    void process(ProcessorContext context);
}
