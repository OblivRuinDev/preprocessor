package dev.oblivruin.preprocessor.tasks;

import dev.oblivruin.preprocessor.Keywords;
import dev.oblivruin.preprocessor.LinePolicy;
import dev.oblivruin.preprocessor.api.DirectiveHandler;
import dev.oblivruin.preprocessor.api.TextProcessor;
import dev.oblivruin.preprocessor.core.Preprocessor;
import dev.oblivruin.preprocessor.internal.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Common base of {@link PreprocessTask} and {@link ApplyPreprocessTask}. The input model
 * (sources, variables, remapper, keywords, filters, policies), the engine setup and the
 * file walk (binary detection, UTF-8 BOM handling, include/exclude filters, decoding) live
 * here; the subclasses only decide what happens with the processed files (write to the
 * output directory vs. rewrite in place).
 *
 * <p>Values configured directly on a task override everything else for that task:
 * task &gt; command line ({@code -Ppreprocessor.var.NAME}) &gt; source set &gt; global.
 * Values that are not overridden are inherited from the source set and global
 * configuration. The accumulative options ({@code vars}, {@code remapper}, {@code keywords},
 * {@code includes}, {@code excludes}) are merged per key; the scalar options are overridden
 * as a whole.</p>
 */
@DisableCachingByDefault(because = "it is the abstract base and declares no behavior of its own; "
        + "the concrete PreprocessTask opts into caching with @CacheableTask and the concrete "
        + "ApplyPreprocessTask rewrites its inputs in place, so it must never be cached")
public abstract class AbstractPreprocessTask extends DefaultTask {
    private final ConfigurableFileCollection sourceDirs;
    private final MapProperty<String, Object> vars;
    private final MapProperty<String, String> remapper;
    private final MapProperty<String, Keywords> keywords;
    private final ListProperty<String> includes;
    private final ListProperty<String> excludes;
    private final Property<String> encoding;
    private final Property<Boolean> strict;
    private final Property<LinePolicy> inactiveLines;
    private final Property<Boolean> lenient;
    private final ListProperty<String> directiveNames;
    private final ListProperty<String> processorIds;
    private final MapProperty<String, DirectiveHandler> directiveHandlers;
    private final ListProperty<TextProcessor> processors;

    /** Initializes the shared properties; called by the subclass constructors. */
    protected AbstractPreprocessTask(ObjectFactory objects) {
        this.sourceDirs = objects.fileCollection();
        this.vars = objects.mapProperty(String.class, Object.class);
        this.remapper = objects.mapProperty(String.class, String.class);
        this.keywords = objects.mapProperty(String.class, Keywords.class);
        this.includes = objects.listProperty(String.class);
        this.excludes = objects.listProperty(String.class);
        this.encoding = objects.property(String.class);
        this.encoding.convention("UTF-8");
        this.strict = objects.property(Boolean.class);
        this.strict.convention(false);
        this.inactiveLines = objects.property(LinePolicy.class);
        this.inactiveLines.convention(LinePolicy.COMMENT);
        this.lenient = objects.property(Boolean.class);
        this.lenient.convention(true);
        this.directiveNames = objects.listProperty(String.class);
        this.processorIds = objects.listProperty(String.class);
        this.directiveHandlers = objects.mapProperty(String.class, DirectiveHandler.class);
        this.processors = objects.listProperty(TextProcessor.class);
    }

    /**
     * The source directories that are processed, in order. Files with the same relative
     * path in multiple directories are resolved with later-directories-win semantics
     * (compatible with the ToCraft preprocessor).
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public ConfigurableFileCollection getSourceDirs() {
        return sourceDirs;
    }

    /** The variables visible to conditions. */
    @Input
    public MapProperty<String, Object> getVars() {
        return vars;
    }

    /** The string replacement map applied to every processed line. */
    @Input
    public MapProperty<String, String> getRemapper() {
        return remapper;
    }

    /** Suffix based keyword overrides. */
    @Input
    public MapProperty<String, Keywords> getKeywords() {
        return keywords;
    }

    /** Include patterns for files that are preprocessed; non-matching files are copied verbatim or left untouched. */
    @Input
    public ListProperty<String> getIncludes() {
        return includes;
    }

    /** Exclude patterns; matching files are copied verbatim or left untouched. */
    @Input
    public ListProperty<String> getExcludes() {
        return excludes;
    }

    /** The charset name used for text files. */
    @Input
    public Property<String> getEncoding() {
        return encoding;
    }

    /** Whether unknown variables are an error instead of a warning. */
    @Input
    public Property<Boolean> getStrict() {
        return strict;
    }

    /** What happens to directive and inactive lines. */
    @Input
    public Property<LinePolicy> getInactiveLines() {
        return inactiveLines;
    }

    /** Whether single-file problems are warnings ({@code true}) or errors ({@code false}). */
    @Input
    public Property<Boolean> getLenient() {
        return lenient;
    }

    /** Names of the registered custom directives; part of the up-to-date fingerprint. */
    @Input
    public ListProperty<String> getDirectiveNames() {
        return directiveNames;
    }

    /** Ids of the registered text processors; part of the up-to-date fingerprint. */
    @Input
    public ListProperty<String> getProcessorIds() {
        return processorIds;
    }

    /** Version of the preprocessing engine; part of the up-to-date fingerprint. */
    @Input
    public String getEngineVersion() {
        return Preprocessor.ENGINE_VERSION;
    }

    /** The registered custom directive handlers (live objects, not fingerprinted directly). */
    @Internal
    public MapProperty<String, DirectiveHandler> getDirectiveHandlers() {
        return directiveHandlers;
    }

    /** The registered text processors (live objects, not fingerprinted directly). */
    @Internal
    public ListProperty<TextProcessor> getProcessors() {
        return processors;
    }

    /**
     * Walks every file below the source directories (later directories win for duplicate
     * relative paths) and hands each file to the handler: text files that match the
     * filters are preprocessed with the engine, everything else is reported as untouched.
     * Filtered out files are not read at all; typical binary files are detected from
     * their first {@link FileUtils#BINARY_SCAN_BYTES} bytes and never read completely,
     * so untouched files are handed to the handler as paths and can be copied as
     * streams with constant memory.
     */
    protected void forEachSourceFile(SourceFileHandler handler, List<String> warnings) throws IOException {
        Preprocessor engine = createEngine();
        Charset charset = resolveCharset();
        List<String> includePatterns = includes.get();
        List<String> excludePatterns = excludes.get();
        boolean lenientMode = lenient.get();

        for (File dir : getSourceDirs().getFiles()) {
            Path base = dir.toPath();
            if (!Files.isDirectory(base)) {
                String message = "source directory does not exist: " + base;
                if (lenientMode) {
                    warnings.add("[preprocessor] " + message);
                } else {
                    throw new IOException(message);
                }
                continue;
            }
            for (Path file : FileUtils.walkFiles(base)) {
                String relative = base.relativize(file).toString().replace('\\', '/');
                if (!FileUtils.matchesFilters(includePatterns, excludePatterns, relative)) {
                    handler.untouched(file, relative); // excluded: no file content is read
                    continue;
                }
                byte[] head = FileUtils.readHead(file, FileUtils.BINARY_SCAN_BYTES);
                if (FileUtils.isBinary(head)) {
                    handler.untouched(file, relative); // binary: copied as a stream, never fully read
                    continue;
                }
                byte[] raw = Files.readAllBytes(file);
                // second NUL check over the complete content: the head probe covers the
                // common case cheaply, this restores the "a NUL byte anywhere means
                // binary" semantics for files with a late zero byte (and closes the race
                // between the head probe and this read)
                if (FileUtils.isBinary(raw)) {
                    handler.untouched(file, relative);
                    continue;
                }
                boolean bom = FileUtils.hasUtf8Bom(raw);
                byte[] body = bom ? Arrays.copyOfRange(raw, 3, raw.length) : raw;
                String content = new String(body, charset);
                warnAboutReplacementChars(content, relative, charset, warnings);
                String processed = engine.processContent(relative, relative, content, warnings);
                handler.preprocessed(file, relative, processed.getBytes(charset), bom, content, processed);
            }
        }
    }

    private Charset resolveCharset() {
        String name = encoding.get();
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            // IllegalCharsetNameException / UnsupportedCharsetException / UnsupportedOperationException
            throw new IllegalArgumentException("invalid encoding '" + name + "' configured for task '"
                    + getName() + "': " + e);
        }
    }

    /**
     * The platform decoder silently replaces malformed input with U+FFFD; warn once per
     * file so an encoding misconfiguration (e.g. a GBK file read as UTF-8) becomes
     * visible instead of quietly corrupting the build input.
     */
    private static void warnAboutReplacementChars(String content, String relative, Charset charset,
            List<String> warnings) {
        int idx = content.indexOf('\uFFFD');
        if (idx < 0) {
            return;
        }
        int line = 1;
        for (int i = 0; i < idx; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        warnings.add("[preprocessor] '" + relative + "' contains malformed input for the configured encoding '"
                + charset.name() + "' (first replacement character at line " + line
                + "); check the encoding setting");
    }

    /** Reports the collected warnings through the task logger. */
    protected void reportWarnings(List<String> warnings) {
        for (String warning : warnings) {
            getLogger().warn(warning);
        }
    }

    private Preprocessor createEngine() {
        Map<String, Keywords> suffixMap = new LinkedHashMap<>();
        for (Map.Entry<String, Keywords> entry : keywords.get().entrySet()) {
            suffixMap.put(FileUtils.normalizeSuffix(entry.getKey()), entry.getValue());
        }
        Map<String, DirectiveHandler> handlers = new LinkedHashMap<>();
        for (Map.Entry<String, DirectiveHandler> entry : directiveHandlers.get().entrySet()) {
            handlers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return Preprocessor.builder()
                .defaultKeywords(Keywords.DEFAULT)
                .keywordsBySuffix(suffixMap)
                .variables(vars.get())
                .remapper(remapper.get())
                .directives(handlers)
                .processors(processors.get())
                .policy(inactiveLines.get())
                .strict(strict.get())
                .build();
    }

    /** Handles the outcome of one source file during the walk. */
    protected interface SourceFileHandler {

        /**
         * A binary file or a file filtered out by include/exclude. Only the path is
         * provided - the content was never read completely; copy it as a stream when
         * needed.
         */
        void untouched(Path file, String relative) throws IOException;

        /**
         * A text file that was preprocessed. {@code processed} holds the encoded result
         * (without the BOM), {@code original} and {@code processedContent} the decoded
         * texts before and after preprocessing.
         */
        void preprocessed(Path file, String relative, byte[] processed, boolean bom,
                String original, String processedContent) throws IOException;
    }
}
