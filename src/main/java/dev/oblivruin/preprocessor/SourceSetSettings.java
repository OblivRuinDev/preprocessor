package dev.oblivruin.preprocessor;

import dev.oblivruin.preprocessor.tasks.AbstractPreprocessTask;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per source set preprocessing configuration. Entries are created either explicitly
 * inside the {@code preprocessor.sourceSets} container or implicitly with defaults for
 * every source set when {@link PreprocessorExtension#getAutoConfigure()} is enabled.
 *
 * <p>Example (Groovy DSL):</p>
 * <pre>
 * preprocessor {
 *     sourceSets {
 *         main {
 *             onlyJava()               // process only the java sources of main
 *             attachTo 'compileJava'   // redirect only the listed tasks (default: all standard consumers)
 *             vars.put('MAIN_FLAG', '1')
 *             exclude 'legacy/&#42;&#42;/&#42;.java'
 *         }
 *         test {
 *             enabled = false          // or: detach() to keep the tasks but wire nothing
 *         }
 *     }
 * }
 * </pre>
 */
public class SourceSetSettings {
    private final String name;
    private final Property<Boolean> enabled;
    private final Property<Boolean> java;
    private final Property<Boolean> kotlin;
    private final Property<Boolean> resources;
    private final Property<Boolean> attach;
    private final ListProperty<String> attachTo;
    private final MapProperty<String, Object> vars;
    private final MapProperty<String, Keywords> keywords;
    private final ListProperty<String> includes;
    private final ListProperty<String> excludes;
    private final Property<LinePolicy> inactiveLines;
    private final Property<Boolean> strict;
    private final Property<String> encoding;
    private final Map<String, List<Action<? super AbstractPreprocessTask>>> taskActions =
            new LinkedHashMap<>();

    /**
     * Creates a settings instance; the conventions are applied immediately so the
     * instance is usable right away.
     */
    public SourceSetSettings(String name, ObjectFactory objects) {
        this.name = name;
        this.enabled = objects.property(Boolean.class);
        this.enabled.convention(true);
        this.java = objects.property(Boolean.class);
        this.java.convention(true);
        this.kotlin = objects.property(Boolean.class);
        this.kotlin.convention(true);
        this.resources = objects.property(Boolean.class);
        this.resources.convention(true);
        // no convention here: unset means "inherit from the global attach setting"
        this.attach = objects.property(Boolean.class);
        this.attachTo = objects.listProperty(String.class);
        this.vars = objects.mapProperty(String.class, Object.class);
        this.keywords = objects.mapProperty(String.class, Keywords.class);
        this.includes = objects.listProperty(String.class);
        this.excludes = objects.listProperty(String.class);
        this.inactiveLines = objects.property(LinePolicy.class);
        this.strict = objects.property(Boolean.class);
        this.encoding = objects.property(String.class);
    }

    /** Creates a settings instance with the default conventions applied. */
    public static SourceSetSettings create(ObjectFactory objects, String name) {
        return new SourceSetSettings(name, objects);
    }

    /** @return the name of the source set this configuration belongs to */
    public String getName() {
        return name;
    }

    /** Whether this source set is preprocessed at all. */
    public Property<Boolean> getEnabled() {
        return enabled;
    }

    /** DSL setter so that {@code enabled = false} works in Groovy container closures. */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    /** Whether the java sources of this source set are preprocessed. */
    public Property<Boolean> getJava() {
        return java;
    }

    /** DSL setter so that {@code java = false} works in Groovy container closures. */
    public void setJava(boolean java) {
        this.java.set(java);
    }

    /** Whether the kotlin sources of this source set are preprocessed. */
    public Property<Boolean> getKotlin() {
        return kotlin;
    }

    /** DSL setter so that {@code kotlin = false} works in Groovy container closures. */
    public void setKotlin(boolean kotlin) {
        this.kotlin.set(kotlin);
    }

    /** Whether the resources of this source set are preprocessed. */
    public Property<Boolean> getResources() {
        return resources;
    }

    /** DSL setter so that {@code resources = false} works in Groovy container closures. */
    public void setResources(boolean resources) {
        this.resources.set(resources);
    }

    /**
     * Whether the preprocess output is attached to the consumer tasks (the compile and
     * process resources tasks). Default: inherited from the global {@code attach} setting
     * (which defaults to {@code true}). When {@code false}, the {@code preprocess*} tasks
     * are still registered but nothing in the regular build consumes them; the processed
     * output in {@code build/preprocessed/...} can then be wired manually.
     */
    public Property<Boolean> getAttach() {
        return attach;
    }

    /** DSL setter so that {@code attach = false} works in Groovy container closures. */
    public void setAttach(boolean attach) {
        this.attach.set(attach);
    }

    /**
     * Explicit allowlist of task names the preprocess output is attached to. When empty
     * (default), the standard consumer tasks of the enabled languages are used
     * ({@code compileJava}, {@code compileKotlin}, {@code processResources}). When
     * non-empty, only the listed tasks are wired (by exact task name, this project only);
     * compile like tasks get their sources redirected, other tasks only get a task
     * dependency so the preprocess output exists.
     */
    public ListProperty<String> getAttachTo() {
        return attachTo;
    }

    /** DSL helper: attach the preprocess output only to the given tasks (see {@link #getAttachTo()}). */
    public void attachTo(String... taskNames) {
        attachTo.set(Arrays.asList(taskNames));
    }

    /** DSL helper: register the preprocess tasks but attach them to nothing. */
    public void detach() {
        attach.set(false);
    }

    /**
     * DSL helper: configures the {@code preprocess<SourceSet>Java} and
     * {@code applyPreprocess<SourceSet>Java} tasks of this source set directly, e.g.
     * {@code javaTask { vars.put('MODE', 'java') }}. Values configured here override the
     * command line, source set and global settings for these tasks; everything else is
     * inherited.
     */
    public void javaTask(Action<? super AbstractPreprocessTask> action) {
        addTaskAction("java", action);
    }

    /** Groovy DSL variant of {@link #javaTask(Action)} (the closure delegate is the task). */
    public void javaTask(Closure<?> closure) {
        addTaskAction("java", wrap(closure));
    }

    /** DSL helper: like {@link #javaTask(Action)} for the kotlin tasks. */
    public void kotlinTask(Action<? super AbstractPreprocessTask> action) {
        addTaskAction("kotlin", action);
    }

    /** Groovy DSL variant of {@link #kotlinTask(Action)}. */
    public void kotlinTask(Closure<?> closure) {
        addTaskAction("kotlin", wrap(closure));
    }

    /** DSL helper: like {@link #javaTask(Action)} for the resources tasks. */
    public void resourcesTask(Action<? super AbstractPreprocessTask> action) {
        addTaskAction("resources", action);
    }

    /** Groovy DSL variant of {@link #resourcesTask(Action)}. */
    public void resourcesTask(Closure<?> closure) {
        addTaskAction("resources", wrap(closure));
    }

    /** @return the per task actions registered for the language (never {@code null}); internal use. */
    public List<Action<? super AbstractPreprocessTask>> getTaskActions(String language) {
        List<Action<? super AbstractPreprocessTask>> actions = taskActions.get(language);
        return actions != null ? actions : Collections.emptyList();
    }

    private void addTaskAction(String language, Action<? super AbstractPreprocessTask> action) {
        taskActions.computeIfAbsent(language, k -> new ArrayList<>()).add(action);
    }

    private static Action<? super AbstractPreprocessTask> wrap(final Closure<?> closure) {
        return (Action<AbstractPreprocessTask>) options -> {
            // delegate first: the enclosing source set closure also exposes a 'vars'
            // property, which would otherwise capture the call
            closure.setResolveStrategy(Closure.DELEGATE_FIRST);
            closure.setDelegate(options);
            closure.call(options);
        };
    }

    /** Additional variables, only visible to this source set. */
    public MapProperty<String, Object> getVars() {
        return vars;
    }

    /** Suffix based keyword overrides (e.g. {@code json}), merged over the global map. */
    public MapProperty<String, Keywords> getKeywords() {
        return keywords;
    }

    /** Ant style patterns; only matching files are preprocessed, others are copied verbatim. */
    public ListProperty<String> getIncludes() {
        return includes;
    }

    /** Ant style patterns; matching files are copied verbatim instead of preprocessed. */
    public ListProperty<String> getExcludes() {
        return excludes;
    }

    /** Overrides the global {@link LinePolicy} for this source set when set. */
    public Property<LinePolicy> getInactiveLines() {
        return inactiveLines;
    }

    /** DSL setter so that {@code inactiveLines = ...} works in Groovy container closures. */
    public void setInactiveLines(LinePolicy policy) {
        this.inactiveLines.set(policy);
    }

    /** Overrides the global strict mode for this source set when set. */
    public Property<Boolean> getStrict() {
        return strict;
    }

    /** DSL setter so that {@code strict = true} works in Groovy container closures. */
    public void setStrict(boolean strict) {
        this.strict.set(strict);
    }

    /** Overrides the global file encoding for this source set when set. */
    public Property<String> getEncoding() {
        return encoding;
    }

    /** DSL setter so that {@code encoding = 'ISO-8859-1'} works in Groovy container closures. */
    public void setEncoding(String encoding) {
        this.encoding.set(encoding);
    }

    /** DSL helper: process only the java sources of this source set. */
    public void onlyJava() {
        kotlin.set(false);
        resources.set(false);
    }

    /** DSL helper: process only the kotlin sources of this source set. */
    public void onlyKotlin() {
        java.set(false);
        resources.set(false);
    }

    /** DSL helper: process only the resources of this source set. */
    public void onlyResources() {
        java.set(false);
        kotlin.set(false);
    }

    /** DSL helper: process java, kotlin and resources (the default). */
    public void allLanguages() {
        java.set(true);
        kotlin.set(true);
        resources.set(true);
    }

    /**
     * DSL helper: adds include patterns; only matching files are preprocessed, the rest
     * is copied verbatim, e.g. {@code include 'platform/&#42;&#42;/&#42;'}.
     */
    public void include(String... patterns) {
        includes.addAll(Arrays.asList(patterns));
    }

    /** DSL helper: adds exclude patterns; matching files are copied verbatim, e.g. {@code exclude 'legacy/&#42;&#42;/&#42;'}. */
    public void exclude(String... patterns) {
        excludes.addAll(Arrays.asList(patterns));
    }
}
