package dev.oblivruin.preprocessor;

import dev.oblivruin.preprocessor.api.TextProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for custom file level {@link TextProcessor}s.
 *
 * <p>The built-in chain consists of the directive scan (always first, not replaceable)
 * and the built-in {@code remapper} string replacement (driven by
 * {@link PreprocessorExtension#getRemapper()}). Custom processors run after the
 * remapper by default and can be ordered relative to each other and to the synthetic
 * {@code remapper} anchor:</p>
 *
 * <pre>
 * preprocessor {
 *     processors.register('myProcessor', new MyProcessor())                  // after the remapper
 *     processors.registerBefore('early', 'remapper', new MyEarlyProcessor()) // before all custom processors
 * }
 * </pre>
 */
public class ProcessorRegistry {
    /** The id of the built-in string replacement processor. */
    public static final String BUILTIN_REMAPPER = "remapper";

    private static final String[] RESERVED_IDS = {BUILTIN_REMAPPER};

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

    /** Registers a processor that runs after all built-in processors. */
    public void register(String id, TextProcessor processor) {
        add(id, processor, Mode.APPEND, null);
    }

    /** Registers a processor that runs immediately before the processor with the given anchor id. */
    public void registerBefore(String id, String anchorId, TextProcessor processor) {
        add(id, processor, Mode.BEFORE, anchorId);
    }

    /** Registers a processor that runs immediately after the processor with the given anchor id. */
    public void registerAfter(String id, String anchorId, TextProcessor processor) {
        add(id, processor, Mode.AFTER, anchorId);
    }

    /** Removes a previously registered custom processor (built-ins cannot be removed). */
    public void remove(String id) {
        for (String reserved : RESERVED_IDS) {
            if (reserved.equals(id)) {
                throw new IllegalArgumentException("the built-in processor '" + id + "' cannot be removed");
            }
        }
        entries.remove(id);
    }

    private void add(String id, TextProcessor processor, Mode mode, String anchorId) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("processor id must not be empty");
        }
        for (String reserved : RESERVED_IDS) {
            if (reserved.equals(id)) {
                throw new IllegalArgumentException("processor id '" + id + "' is reserved");
            }
        }
        if (entries.containsKey(id)) {
            throw new IllegalArgumentException("processor '" + id + "' is already registered");
        }
        if (processor == null) {
            throw new IllegalArgumentException("processor '" + id + "' must not be null");
        }
        entries.put(id, new Entry(id, processor, mode, anchorId));
    }

    /** @return an unmodifiable snapshot of all registered processors by id */
    public Map<String, TextProcessor> all() {
        Map<String, TextProcessor> snapshot = new LinkedHashMap<String, TextProcessor>();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().processor);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Builds the ordered custom processor chain. The built-in remapper is executed by
     * the engine itself before this chain; the synthetic id {@link #BUILTIN_REMAPPER}
     * can still be used as an anchor to position custom processors relative to each
     * other.
     *
     * @return the chain of custom processors
     */
    public Chain buildChain() {
        List<TextProcessor> processors = new ArrayList<TextProcessor>();
        List<String> ids = new ArrayList<String>();
        for (Entry entry : entries.values()) {
            int index;
            switch (entry.mode) {
                case BEFORE: {
                    index = indexOfAnchor(ids, entry.anchorId, entry.id);
                    break;
                }
                case AFTER: {
                    index = indexOfAnchor(ids, entry.anchorId, entry.id);
                    index = index < 0 ? -1 : index + 1;
                    break;
                }
                default:
                    index = -1;
                    break;
            }
            if (index < 0) {
                processors.add(entry.processor);
                ids.add(entry.id);
            } else {
                processors.add(index, entry.processor);
                ids.add(index, entry.id);
            }
        }
        return new Chain(processors, ids);
    }

    private int indexOfAnchor(List<String> ids, String anchorId, String forId) {
        // the synthetic 'remapper' anchor resolves to the front of the custom chain
        if (BUILTIN_REMAPPER.equals(anchorId)) {
            return 0;
        }
        int index = ids.indexOf(anchorId);
        if (index < 0) {
            throw new IllegalArgumentException("unknown processor anchor '" + anchorId + "' for processor '"
                    + forId + "'; anchors must reference the built-in 'remapper' or another registered processor");
        }
        return index;
    }

    /** The ordered processor chain built by {@link #buildChain()}. */
    public static final class Chain {
        /** The processors in execution order (after the built-in remapper). */
        public final List<TextProcessor> processors;
        /** The ids in execution order, used for up-to-date fingerprints. */
        public final List<String> ids;

        Chain(List<TextProcessor> processors, List<String> ids) {
            this.processors = processors;
            this.ids = ids;
        }
    }

    private enum Mode {APPEND, BEFORE, AFTER}

    private static final class Entry {
        final String id;
        final TextProcessor processor;
        final Mode mode;
        final String anchorId;

        Entry(String id, TextProcessor processor, Mode mode, String anchorId) {
            this.id = id;
            this.processor = processor;
            this.mode = mode;
            this.anchorId = anchorId;
        }
    }
}
