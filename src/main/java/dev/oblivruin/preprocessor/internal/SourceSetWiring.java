package dev.oblivruin.preprocessor.internal;

import dev.oblivruin.preprocessor.Keywords;
import dev.oblivruin.preprocessor.LinePolicy;
import dev.oblivruin.preprocessor.PreprocessorExtension;
import dev.oblivruin.preprocessor.ProcessorRegistry;
import dev.oblivruin.preprocessor.SourceSetSettings;
import dev.oblivruin.preprocessor.api.DirectiveHandler;
import dev.oblivruin.preprocessor.api.TextProcessor;
import dev.oblivruin.preprocessor.tasks.AbstractPreprocessTask;
import dev.oblivruin.preprocessor.tasks.ApplyPreprocessTask;
import dev.oblivruin.preprocessor.tasks.PreprocessTask;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.logging.Logger;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.AbstractCompile;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wires the preprocess tasks of one source set into its compile tasks.
 *
 * <p>The wiring is deliberately free of compile time dependencies on third party
 * plugins: the kotlin sources are discovered through the {@code kotlin} extension every
 * {@link SourceSet} receives from the kotlin plugin, and the kotlin compile task is
 * configured generically through {@link AbstractCompile} with a reflection fallback.</p>
 */
public final class SourceSetWiring {
    private SourceSetWiring() {
    }

    /** Wires one source set; does nothing when the source set is disabled or empty. */
    public static void wire(Project project, PreprocessorExtension extension, SourceSet sourceSet, Set<String> wired) {
        if (!wired.add(sourceSet.getName())) {
            return;
        }
        SourceSetSettings settings = resolveSettings(extension, sourceSet.getName());
        if (settings == null || !Boolean.TRUE.equals(settings.getEnabled().get())) {
            return;
        }

        WiringOptions options = new WiringOptions();
        options.vars = mergeVariables(project, extension, settings);
        options.keywords = extension.mergedKeywords(settings);
        options.remapper = extension.getRemapper().get();
        options.includes = settings.getIncludes().get();
        options.excludes = settings.getExcludes().get();
        options.encoding = settings.getEncoding().getOrNull() != null
                ? settings.getEncoding().get() : extension.getEncoding().get();
        options.strict = settings.getStrict().getOrNull() != null
                ? settings.getStrict().get() : extension.getStrict().get();
        options.inactiveLines = settings.getInactiveLines().getOrNull() != null
                ? settings.getInactiveLines().get() : extension.getInactiveLines().get();
        options.lenient = extension.getLenient().get();
        options.directiveHandlers = extension.getDirectives().all();
        options.directiveNames = new ArrayList<String>(options.directiveHandlers.keySet());
        ProcessorRegistry.Chain chain = buildProcessorChain(extension);
        options.processors = chain.processors;
        options.processorIds = chain.ids;
        options.customLogicInputs = extension.getCustomLogicInputs();

        boolean attach = extension.resolvedAttach(settings);
        List<String> attachTo = extension.resolvedAttachTo(settings);
        boolean cacheWithCustomLogicAllowed = Boolean.TRUE.equals(extension.getCacheWithCustomLogic().get());

        // register the preprocess tasks of every enabled language first
        Map<String, LanguageEntry> languages = new LinkedHashMap<String, LanguageEntry>();

        if (Boolean.TRUE.equals(settings.getJava().get())) {
            Set<File> dirs = sourceSet.getJava().getSrcDirs();
            if (!dirs.isEmpty()) {
                languages.put("java", new LanguageEntry(registerLanguage(project, sourceSet, "java", dirs, options,
                        settings.getTaskActions("java"), cacheWithCustomLogicAllowed), dirs));
            }
        }

        Object kotlinSources = getKotlinSources(sourceSet);
        if (Boolean.TRUE.equals(settings.getKotlin().get()) && kotlinSources instanceof SourceDirectorySet) {
            Set<File> dirs = ((SourceDirectorySet) kotlinSources).getSrcDirs();
            if (!dirs.isEmpty()) {
                languages.put("kotlin", new LanguageEntry(registerLanguage(project, sourceSet, "kotlin", dirs, options,
                        settings.getTaskActions("kotlin"), cacheWithCustomLogicAllowed), dirs));
            }
        }

        if (Boolean.TRUE.equals(settings.getResources().get())) {
            Set<File> dirs = sourceSet.getResources().getSrcDirs();
            if (!dirs.isEmpty()) {
                languages.put("resources", new LanguageEntry(registerLanguage(project, sourceSet, "resources", dirs, options,
                        settings.getTaskActions("resources"), cacheWithCustomLogicAllowed), dirs));
            }
        }

        if (!attach) {
            project.getLogger().info("[preprocessor] attach is disabled for the '{}' source set; the preprocess"
                    + " tasks are registered but no compile or resource task is redirected", sourceSet.getName());
            return;
        }
        if (attachTo.isEmpty()) {
            // default: wire the standard consumer tasks of the enabled languages
            LanguageEntry java = languages.get("java");
            if (java != null) {
                wireJavaCompile(project, sourceSet, java);
            }
            LanguageEntry kotlin = languages.get("kotlin");
            if (kotlin != null) {
                wireKotlinCompile(project, sourceSet, kotlin);
            }
            LanguageEntry resources = languages.get("resources");
            if (resources != null) {
                wireProcessResources(project, sourceSet, resources);
            }
        } else {
            attachCustom(project, sourceSet, languages, attachTo);
        }
    }

    private static SourceSetSettings resolveSettings(PreprocessorExtension extension, String sourceSetName) {
        SourceSetSettings settings = extension.getSourceSets().findByName(sourceSetName);
        if (settings != null) {
            return settings;
        }
        if (Boolean.TRUE.equals(extension.getAutoConfigure().get())) {
            return extension.newDefaultSettings(sourceSetName);
        }
        return null;
    }

    private static Object getKotlinSources(SourceSet sourceSet) {
        try {
            return sourceSet.getExtensions().findByName("kotlin");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Merges the variable layers in the documented priority order "command line &gt;
     * source set &gt; global" (the task layer is applied by the task configuration
     * afterwards): the last {@code put} wins, so the command line entries must be
     * written last.
     */
    private static MapProperty<String, Object> mergeVariables(
            Project project, PreprocessorExtension extension, SourceSetSettings settings) {
        ObjectFactory objects = project.getObjects();
        MapProperty<String, Object> merged = objects.mapProperty(String.class, Object.class);
        Map<String, Object> global = extension.getVars().get();
        for (Map.Entry<String, Object> entry : global.entrySet()) {
            merged.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Object> entry : settings.getVars().get().entrySet()) {
            merged.put(entry.getKey(), entry.getValue());
        }
        // command line overrides: gradlew build -Ppreprocessor.var.NAME=value (highest priority)
        for (Map.Entry<String, ?> entry : project.getProperties().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("preprocessor.var.")) {
                merged.put(key.substring("preprocessor.var.".length()), entry.getValue());
            }
        }
        return merged;
    }

    private static TaskProvider<PreprocessTask> registerLanguage(Project project, SourceSet sourceSet, String language,
            Collection<File> sourceDirs, WiringOptions options,
            List<Action<? super AbstractPreprocessTask>> userActions, boolean cacheWithCustomLogicAllowed) {
        String ssName = sourceSet.getName();
        String target = language.substring(0, 1).toUpperCase(Locale.ROOT) + language.substring(1);
        String preprocessName = sourceSet.getTaskName("preprocess", target);
        String applyName = sourceSet.getTaskName("applyPreprocess", target);

        // custom logic handlers are live objects and cannot be part of a cache key; without
        // them the task is fully cacheable
        boolean customLogic = !options.directiveHandlers.isEmpty() || !options.processors.isEmpty();

        TaskProvider<PreprocessTask> preprocess = project.getTasks().register(preprocessName, PreprocessTask.class, task -> {
            task.setGroup("preprocessor");
            task.setDescription("Preprocesses the " + language + " sources of the '" + ssName + "' source set.");
            task.getSourceDirs().from(sourceDirs);
            task.getOutputDir().set(project.getLayout().getBuildDirectory()
                    .dir("preprocessed/" + ssName + "/" + language));
            applyOptions(task, options);
            // declared inputs of custom logic (replacement tables, ...) participate in the
            // up-to-date fingerprint and the build cache key
            task.getInputs().files(options.customLogicInputs);
            if (customLogic && !cacheWithCustomLogicAllowed) {
                task.getOutputs().doNotCacheIf("custom directives or text processors are registered; their logic"
                        + " is not part of the cache key (set 'cacheWithCustomLogic = true' to cache anyway)",
                        t -> true);
            }
        });

        TaskProvider<ApplyPreprocessTask> apply = project.getTasks().register(applyName, ApplyPreprocessTask.class, task -> {
            task.setGroup("preprocessor");
            task.setDescription("Applies preprocessing of the " + language + " sources of the '" + ssName
                    + "' source set in place (rewrites the original files, experimental).");
            task.getSourceDirs().from(sourceDirs);
            applyOptions(task, options);
            task.getInputs().files(options.customLogicInputs);
        });
        // serialize against the preprocess task (both walk the same source files) but do
        // not force it to run: applying alone must not execute the whole preprocessing
        apply.configure(task -> task.mustRunAfter(preprocess));

        // user task configuration applies to both task types, after the defaults, so
        // per task values win
        for (Action<? super AbstractPreprocessTask> action : userActions) {
            preprocess.configure(task -> action.execute(task));
            apply.configure(task -> action.execute(task));
        }
        // compose the accumulative options last: task level entries are layered over the
        // merged base (Gradle properties replace their convention entirely, so the merge
        // is done explicitly)
        preprocess.configure(task -> layerAccumulativeOptions(task, options));
        apply.configure(task -> layerAccumulativeOptions(task, options));

        return preprocess;
    }

    /**
     * Layers the task level entries of the accumulative options (vars, keywords, remapper,
     * includes, excludes) over the merged base entries; scalar options stay conventions so
     * a task level {@code set} simply overrides them.
     */
    private static void layerAccumulativeOptions(AbstractPreprocessTask task, WiringOptions options) {
        Map<String, Object> taskVars = new LinkedHashMap<String, Object>(task.getVars().get());
        Map<String, Keywords> taskKeywords = new LinkedHashMap<String, Keywords>(task.getKeywords().get());
        Map<String, String> taskRemapper = new LinkedHashMap<String, String>(task.getRemapper().get());
        List<String> taskIncludes = new ArrayList<String>(task.getIncludes().get());
        List<String> taskExcludes = new ArrayList<String>(task.getExcludes().get());

        task.getVars().set(options.vars.map(base -> {
            Map<String, Object> combined = new LinkedHashMap<String, Object>(base);
            combined.putAll(taskVars);
            return combined;
        }));

        Map<String, Keywords> keywords = new LinkedHashMap<String, Keywords>(options.keywords);
        keywords.putAll(taskKeywords);
        task.getKeywords().set(keywords);

        Map<String, String> remapper = new LinkedHashMap<String, String>(options.remapper);
        remapper.putAll(taskRemapper);
        task.getRemapper().set(remapper);

        List<String> includes = new ArrayList<String>(options.includes);
        includes.addAll(taskIncludes);
        task.getIncludes().set(includes);

        List<String> excludes = new ArrayList<String>(options.excludes);
        excludes.addAll(taskExcludes);
        task.getExcludes().set(excludes);
    }

    /**
     * Applies the scalar merged source set options as the task conventions; the
     * accumulative options (vars, keywords, remapper, includes, excludes) are layered in
     * {@link #layerAccumulativeOptions} after the per task actions so that task level
     * entries win per key without discarding the base entries.
     */
    private static void applyOptions(AbstractPreprocessTask task, WiringOptions options) {
        task.getEncoding().convention(options.encoding);
        task.getStrict().convention(options.strict);
        task.getInactiveLines().convention(options.inactiveLines);
        task.getLenient().convention(options.lenient);
        task.getDirectiveNames().set(options.directiveNames);
        task.getProcessorIds().set(options.processorIds);
        task.getDirectiveHandlers().set(options.directiveHandlers);
        task.getProcessors().set(options.processors);
    }

    private static void wireJavaCompile(Project project, SourceSet sourceSet, LanguageEntry entry) {
        project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class)
                .configure(compile -> redirectCompile(project, compile, entry.preprocess, entry.dirs));
    }

    private static void wireKotlinCompile(Project project, SourceSet sourceSet, LanguageEntry entry) {
        String compileTaskName = sourceSet.getCompileTaskName("kotlin");
        Task compileTask = project.getTasks().findByName(compileTaskName);
        if (compileTask == null) {
            project.getLogger().warn("[preprocessor] kotlin sources found but no '{}' task; "
                    + "the kotlin sources of '{}' are not preprocessed", compileTaskName, sourceSet.getName());
            return;
        }
        redirectCompile(project, compileTask, entry.preprocess, entry.dirs);
    }

    /**
     * Redirects the sources of a compile task (java, kotlin through {@link AbstractCompile}
     * with a reflection fallback, or any other compile like task) from the original
     * directories to the preprocessed output.
     */
    private static void redirectCompile(Project project, Task compileTask,
            TaskProvider<PreprocessTask> preprocess, Collection<File> originalDirs) {
        FileCollection processed = project.files(preprocess.flatMap(PreprocessTask::getOutputDir));
        FileCollection originalFiles = fileTreeOf(project, originalDirs);
        if (!setCompileSource(compileTask, originalFiles, processed, project.getLogger())) {
            project.getLogger().warn("[preprocessor] could not redirect the sources of '{}' to the "
                    + "preprocessed output", compileTask.getName());
        }
        compileTask.dependsOn(preprocess);
    }

    /** A lazy file tree over the original source directories, usable for {@code minus}. */
    private static FileCollection fileTreeOf(Project project, Collection<File> dirs) {
        FileCollection files = null;
        for (File dir : dirs) {
            FileTree tree = project.fileTree(dir);
            files = files == null ? tree : files.plus(tree);
        }
        return files == null ? project.files() : files;
    }

    private static void wireProcessResources(Project project, SourceSet sourceSet, LanguageEntry entry) {
        project.getTasks().named(sourceSet.getProcessResourcesTaskName(), ProcessResources.class)
                .configure(resources -> attachProcessResources(project, resources, entry.preprocess));
    }

    /** Attaches the preprocessed resources to a {@code processResources} style task. */
    private static void attachProcessResources(Project project, Task task, TaskProvider<PreprocessTask> preprocess) {
        task.dependsOn(preprocess);
        if (task instanceof ProcessResources) {
            ProcessResources resources = (ProcessResources) task;
            // the processed files overwrite the originals; the last spec wins with INCLUDE
            resources.from(project.files(preprocess.flatMap(PreprocessTask::getOutputDir)));
            resources.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE);
        } else {
            project.getLogger().warn("[preprocessor] task '{}' is not a '{}' task; only a task dependency was added",
                    task.getName(), "processResources");
        }
    }

    /**
     * Attaches the preprocess output only to the explicitly listed tasks. Standard
     * consumer tasks of the enabled languages ({@code compileJava}, {@code compileKotlin},
     * {@code processResources}) get their sources redirected; any other listed task only
     * gets a task dependency so the preprocess output exists when it runs.
     */
    private static void attachCustom(Project project, SourceSet sourceSet,
            Map<String, LanguageEntry> languages, List<String> targets) {
        for (String name : targets) {
            Task task = project.getTasks().findByName(name);
            if (task == null) {
                throw new InvalidUserDataException("[preprocessor] attachTo target '" + name + "' does not match any"
                        + " task in this project (attachTo is matched by exact task name within the same project;"
                        + " apply the plugin owning the task before configuring attachTo)");
            }
            String matched = null;
            for (Map.Entry<String, LanguageEntry> entry : languages.entrySet()) {
                if (isStandardTaskOf(sourceSet, entry.getKey(), name)) {
                    matched = entry.getKey();
                    break;
                }
            }
            if (matched != null) {
                LanguageEntry entry = languages.get(matched);
                if ("resources".equals(matched)) {
                    attachProcessResources(project, task, entry.preprocess);
                } else {
                    redirectCompile(project, task, entry.preprocess, entry.dirs);
                }
                continue;
            }
            // not a source consuming task of this source set: only make the preprocess output exist
            project.getLogger().warn("[preprocessor] task '{}' is not a compile or resource task of the '{}' source"
                    + " set; only a task dependency on the preprocess tasks was added", name, sourceSet.getName());
            for (LanguageEntry entry : languages.values()) {
                task.dependsOn(entry.preprocess);
            }
        }
    }

    /** Whether the given task name is the standard consumer task of the language within the source set. */
    private static boolean isStandardTaskOf(SourceSet sourceSet, String language, String taskName) {
        if ("java".equals(language)) {
            return sourceSet.getCompileJavaTaskName().equals(taskName);
        }
        if ("kotlin".equals(language)) {
            return sourceSet.getCompileTaskName("kotlin").equals(taskName);
        }
        return "resources".equals(language) && sourceSet.getProcessResourcesTaskName().equals(taskName);
    }

    /**
     * Redirects the sources of a compile task from {@code originals} to {@code processed}.
     * Uses {@link AbstractCompile} when possible and falls back to reflection so the
     * plugin works without a compile time dependency on the kotlin plugin. Two task
     * shapes are handled: the classic {@code getSource()}/{@code setSource(Object)}
     * shape (java, SourceTask based) and the Kotlin 1.9+ shape, which is a plain
     * {@code DefaultTask} with {@code getSources()} and a varargs
     * {@code setSource(Object...)}.
     */
    private static boolean setCompileSource(Task task, FileCollection originals, FileCollection processed, Logger logger) {
        if (task instanceof AbstractCompile) {
            AbstractCompile compile = (AbstractCompile) task;
            FileTree current = compile.getSource();
            if (current != null) {
                compile.setSource(current.minus(originals).plus(processed));
                return true;
            }
            compile.setSource(processed);
            return true;
        }
        try {
            // Kotlin 1.9+ shape: getSources() returns the live collection; replacing its
            // contents is the cleanest redirect
            Method getSources = method(task.getClass(), "getSources");
            if (getSources == null) {
                getSources = method(task.getClass(), "getSource");
            }
            if (getSources == null) {
                return false;
            }
            Object current = getSources.invoke(task);
            if (!(current instanceof FileCollection)) {
                return false;
            }
            if (!(current instanceof org.gradle.api.file.ConfigurableFileCollection)) {
                return invokeSetSource(task, ((FileCollection) current).minus(originals).plus(processed));
            }
            org.gradle.api.file.ConfigurableFileCollection live =
                    (org.gradle.api.file.ConfigurableFileCollection) current;
            // snapshot the current contents first: the replacement must not reference the
            // collection it replaces, or resolving it recurses forever
            FileCollection snapshot = task.getProject().files(live.getFiles());
            live.setFrom(snapshot.minus(originals).plus(processed));
            return true;
        } catch (ReflectiveOperationException e) {
            logger.debug("[preprocessor] reflection based source redirect failed for " + task.getName(), e);
            return false;
        }
    }

    private static Method method(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Finds a one argument {@code setSource} method and invokes it with the merged collection. */
    private static boolean invokeSetSource(Task task, Object merged) throws ReflectiveOperationException {
        for (Method method : task.getClass().getMethods()) {
            if (!method.getName().equals("setSource") || method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType.isArray()) {
                // the Kotlin varargs shape setSource(Object...): the (Object) cast passes
                // the wrapped array as one positional argument
                method.invoke(task, (Object) new Object[] { merged });
                return true;
            }
            // the classic setSource(FileCollection) / setSource(Object)
            if (parameterType == Object.class || parameterType == FileCollection.class) {
                method.invoke(task, merged);
                return true;
            }
        }
        return false;
    }

    private static ProcessorRegistry.Chain buildProcessorChain(PreprocessorExtension extension) {
        return extension.getProcessors().buildChain();
    }

    /** The registered preprocess task and the original source directories of one language. */
    private static final class LanguageEntry {
        final TaskProvider<PreprocessTask> preprocess;
        final Collection<File> dirs;

        LanguageEntry(TaskProvider<PreprocessTask> preprocess, Collection<File> dirs) {
            this.preprocess = preprocess;
            this.dirs = dirs;
        }
    }

    /** Aggregated configuration shared by all languages of one source set. */
    private static final class WiringOptions {
        MapProperty<String, Object> vars;
        Map<String, Keywords> keywords;
        Map<String, String> remapper;
        List<String> includes;
        List<String> excludes;
        String encoding;
        Boolean strict;
        LinePolicy inactiveLines;
        Boolean lenient;
        Map<String, DirectiveHandler> directiveHandlers;
        List<String> directiveNames;
        List<TextProcessor> processors;
        List<String> processorIds;
        FileCollection customLogicInputs;
    }

    /**
     * Warns (once) about custom logic handlers that are not {@link Serializable} when
     * the configuration cache is enabled: Gradle would fail to store the task state
     * with a cryptic message naming the handler type; this warning names the offending
     * registrations and how to fix them. The check is skipped when the configuration
     * cache is not in use, since serializability has no consequence there.
     */
    public static void warnAboutNonSerializableLogic(Project project, PreprocessorExtension extension) {
        if (!isConfigurationCacheRequested(project)) {
            return;
        }
        List<String> directives = new ArrayList<String>();
        for (Map.Entry<String, DirectiveHandler> entry : extension.getDirectives().all().entrySet()) {
            if (!(entry.getValue() instanceof Serializable)) {
                directives.add(entry.getKey());
            }
        }
        List<String> processors = new ArrayList<String>();
        for (Map.Entry<String, TextProcessor> entry : extension.getProcessors().all().entrySet()) {
            if (!(entry.getValue() instanceof Serializable)) {
                processors.add(entry.getKey());
            }
        }
        if (directives.isEmpty() && processors.isEmpty()) {
            return;
        }
        project.getLogger().warn("[preprocessor] custom logic that is not java.io.Serializable will fail"
                + " configuration cache storage: directives {}, processors {}."
                + " Make each handler implement Serializable (e.g. a buildSrc class implementing both"
                + " its interface and Serializable) or register a Groovy closure instead."
                + " See the README section 'Configuration Cache'.", directives, processors);
    }

    private static boolean isConfigurationCacheRequested(Project project) {
        try {
            return project.getGradle().getStartParameter().isConfigurationCacheRequested();
        } catch (Throwable ignored) {
            // the API may move in future Gradle versions - skipping the check is safer
            // than breaking the configuration phase of every build
            return false;
        }
    }
}
