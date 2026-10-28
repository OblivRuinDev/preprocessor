package dev.oblivruin.preprocessor;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import groovy.lang.Closure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code preprocessor} extension: global configuration of the plugin.
 *
 * <p>Example (Groovy DSL):</p>
 * <pre>
 * preprocessor {
 *     vars.put('MC', '1.20.1')
 *     vars.put('FABRIC', providers.provider { System.getenv('CI') != null })
 *     sourceSets {
 *         main { onlyJava() }
 *     }
 *     directives.register('say') { ctx -&gt; ctx.replaceLine('// ' + ctx.argument()) }
 * }
 * </pre>
 */
public class PreprocessorExtension {
    private final ObjectFactory objects;
    private final MapProperty<String, Object> vars;
    private final MapProperty<String, String> remapper;
    private final MapProperty<String, Keywords> keywords;
    private final Property<Boolean> strict;
    private final Property<LinePolicy> inactiveLines;
    private final Property<String> encoding;
    private final Property<Boolean> autoConfigure;
    private final Property<Boolean> lenient;
    private final Property<Boolean> attach;
    private final ListProperty<String> attachTo;
    private final Property<Boolean> cacheWithCustomLogic;
    private final ConfigurableFileCollection customLogicInputs;
    private final NamedDomainObjectContainer<SourceSetSettings> sourceSets;
    private final DirectiveRegistry directives = new DirectiveRegistry();
    private final ProcessorRegistry processors = new ProcessorRegistry();

    public PreprocessorExtension(ObjectFactory objects) {
        this.objects = objects;
        this.vars = objects.mapProperty(String.class, Object.class);
        this.remapper = objects.mapProperty(String.class, String.class);
        this.keywords = objects.mapProperty(String.class, Keywords.class);
        this.strict = objects.property(Boolean.class);
        this.strict.convention(false);
        this.inactiveLines = objects.property(LinePolicy.class);
        this.inactiveLines.convention(LinePolicy.COMMENT);
        this.encoding = objects.property(String.class);
        this.encoding.convention("UTF-8");
        this.autoConfigure = objects.property(Boolean.class);
        this.autoConfigure.convention(true);
        this.lenient = objects.property(Boolean.class);
        this.lenient.convention(true);
        this.attach = objects.property(Boolean.class);
        this.attach.convention(true);
        this.attachTo = objects.listProperty(String.class);
        this.cacheWithCustomLogic = objects.property(Boolean.class);
        this.cacheWithCustomLogic.convention(false);
        this.customLogicInputs = objects.fileCollection();
        this.sourceSets = objects.domainObjectContainer(SourceSetSettings.class,
                name -> SourceSetSettings.create(objects, name));
    }

    /**
     * The variables visible to every condition. Values may be objects or
     * {@link org.gradle.api.provider.Provider}s (e.g. {@code providers.provider { ... }}),
     * which are resolved lazily.
     */
    public MapProperty<String, Object> getVars() {
        return vars;
    }

    /**
     * A map whose keys are replaced with the values in every processed line
     * (compatible with the ToCraft preprocessor's {@code remapper}).
     */
    public MapProperty<String, String> getRemapper() {
        return remapper;
    }

    /**
     * Suffix based keyword overrides; the key is matched against the end of the file
     * name (e.g. {@code json} for {@code mixins.json}), the value replaces the
     * {@link Keywords} for such files.
     */
    public MapProperty<String, Keywords> getKeywords() {
        return keywords;
    }

    /** Strict mode: unknown variables in conditions abort preprocessing instead of warning. */
    public Property<Boolean> getStrict() {
        return strict;
    }

    /** DSL setter so that {@code strict = true} works in Groovy DSL. */
    public void setStrict(boolean strict) {
        this.strict.set(strict);
    }

    /** What happens to directive and inactive lines in the output (default {@link LinePolicy#COMMENT}). */
    public Property<LinePolicy> getInactiveLines() {
        return inactiveLines;
    }

    /** DSL setter so that {@code inactiveLines = ...} works in Groovy DSL. */
    public void setInactiveLines(LinePolicy policy) {
        this.inactiveLines.set(policy);
    }

    /** The file encoding used when reading and writing (default {@code UTF-8}). */
    public Property<String> getEncoding() {
        return encoding;
    }

    /** DSL setter so that {@code encoding = 'ISO-8859-1'} works in Groovy DSL. */
    public void setEncoding(String encoding) {
        this.encoding.set(encoding);
    }

    /**
     * When {@code true} (default) every source set of the project is preprocessed with
     * default settings. Set to {@code false} to preprocess only the source sets that
     * are configured in {@link #getSourceSets()}.
     */
    public Property<Boolean> getAutoConfigure() {
        return autoConfigure;
    }

    /** DSL setter so that {@code autoConfigure = false} works in Groovy DSL. */
    public void setAutoConfigure(boolean autoConfigure) {
        this.autoConfigure.set(autoConfigure);
    }

    /**
     * When {@code true} (default) problems that only affect single files (missing
     * source directories, binary files) are reported as warnings instead of failing
     * the build.
     */
    public Property<Boolean> getLenient() {
        return lenient;
    }

    /** DSL setter so that {@code lenient = false} works in Groovy DSL. */
    public void setLenient(boolean lenient) {
        this.lenient.set(lenient);
    }

    /**
     * Global default of {@link SourceSetSettings#getAttach()}: whether the preprocess
     * output is attached to the consumer tasks (compile / process resources tasks) of
     * every source set. Default {@code true}. When {@code false} (or detached per source
     * set), the {@code preprocess*} tasks are registered but the regular build keeps
     * consuming the original sources.
     */
    public Property<Boolean> getAttach() {
        return attach;
    }

    /** DSL setter so that {@code attach = false} works in Groovy DSL. */
    public void setAttach(boolean attach) {
        this.attach.set(attach);
    }

    /**
     * Global default of {@link SourceSetSettings#getAttachTo()}: when non-empty, only the
     * listed task names are wired (this project only). Per source set values override the
     * global list. When empty, the standard consumer tasks of the enabled languages are used.
     */
    public ListProperty<String> getAttachTo() {
        return attachTo;
    }

    /** DSL setter so that {@code attachTo = ['compileJava']} works in Groovy DSL. */
    public void setAttachTo(java.util.List<String> taskNames) {
        this.attachTo.set(taskNames);
    }

    /**
     * Whether the {@code preprocess*} tasks may be stored in and loaded from the build
     * cache even though custom directives or text processors are registered (default
     * {@code false}). Without custom logic the tasks are always cacheable. Custom logic
     * is not part of the cache key (the handlers are live objects), so a changed handler
     * would silently keep producing cached results - therefore caching is disabled for
     * such tasks unless this flag is set. Up-to-date checks still apply in both cases;
     * after changing custom logic, invalidate reliably by bumping a variable, e.g.
     * {@code -Ppreprocessor.var.logicRev=2}.
     */
    public Property<Boolean> getCacheWithCustomLogic() {
        return cacheWithCustomLogic;
    }

    /** DSL setter so that {@code cacheWithCustomLogic = true} works in Groovy DSL. */
    public void setCacheWithCustomLogic(boolean cacheWithCustomLogic) {
        this.cacheWithCustomLogic.set(cacheWithCustomLogic);
    }

    /**
     * Files that the registered custom directives and text processors read (replacement
     * tables, mappings, ...). Everything declared here is added as an input to every
     * {@code preprocess*} and {@code applyPreprocess*} task, so a changed file reliably
     * reruns the tasks and changes the build cache key. Example (Groovy DSL):
     *
     * <pre>
     * preprocessor {
     *     customLogicInputs.from('config/replacements.txt')
     *     directives.register('map') { ctx -&gt; ... }
     * }
     * </pre>
     *
     * <p>Note: declaring the inputs makes external files participate in up-to-date
     * checks and cache keys, but the <em>logic</em> of a handler still is not part of
     * the key - after changing the handler itself, force reprocessing by bumping a
     * variable (see {@link #getCacheWithCustomLogic()}).</p>
     */
    public ConfigurableFileCollection getCustomLogicInputs() {
        return customLogicInputs;
    }

    /** Resolves the effective attach flag for the given source set (per source set value wins). */
    public boolean resolvedAttach(SourceSetSettings settings) {
        Boolean own = settings != null ? settings.getAttach().getOrNull() : null;
        return own != null ? own : Boolean.TRUE.equals(attach.get());
    }

    /** Resolves the effective attachTo list for the given source set (per source set value wins). */
    public List<String> resolvedAttachTo(SourceSetSettings settings) {
        List<String> own = settings != null ? settings.getAttachTo().getOrNull() : null;
        if (own != null && !own.isEmpty()) {
            return own;
        }
        return attachTo.get();
    }

    /** Per source set configuration; entries not listed follow the global defaults. */
    public NamedDomainObjectContainer<SourceSetSettings> getSourceSets() {
        return sourceSets;
    }

    /** Registry for custom directives, see {@link DirectiveRegistry}. */
    public DirectiveRegistry getDirectives() {
        return directives;
    }

    /** Registry for custom text processors, see {@link ProcessorRegistry}. */
    public ProcessorRegistry getProcessors() {
        return processors;
    }

    /** Convenience: gets or creates the configuration for the given source set. */
    public SourceSetSettings sourceSet(String name) {
        return sourceSets.maybeCreate(name);
    }

    /** Convenience: gets or creates and configures the configuration for the given source set. */
    public SourceSetSettings sourceSet(String name, Action<? super SourceSetSettings> action) {
        SourceSetSettings settings = sourceSet(name);
        action.execute(settings);
        return settings;
    }

    /** Groovy DSL variant of {@link #sourceSet(String, Action)} (the closure delegate is the settings). */
    public SourceSetSettings sourceSet(String name, Closure<?> closure) {
        SourceSetSettings settings = sourceSet(name);
        closure.setResolveStrategy(Closure.DELEGATE_FIRST);
        closure.setDelegate(settings);
        closure.call(settings);
        return settings;
    }

    /** Creates a default (container independent) settings instance for the given source set name. */
    public SourceSetSettings newDefaultSettings(String name) {
        return SourceSetSettings.create(objects, name);
    }

    /** Merges the global keyword map with the given source set overrides. */
    public Map<String, Keywords> mergedKeywords(SourceSetSettings settings) {
        Map<String, Keywords> merged = new LinkedHashMap<String, Keywords>(keywords.get());
        if (settings != null) {
            merged.putAll(settings.getKeywords().get());
        }
        return merged;
    }
}
