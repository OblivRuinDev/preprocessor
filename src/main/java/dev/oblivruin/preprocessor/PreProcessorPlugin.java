package dev.oblivruin.preprocessor;

import dev.oblivruin.preprocessor.internal.SourceSetWiring;
import dev.oblivruin.preprocessor.tasks.ApplyPreprocessTask;
import dev.oblivruin.preprocessor.tasks.PreprocessTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.util.HashSet;
import java.util.Set;

/**
 * The {@code dev.oblivruin.preprocessor} plugin.
 *
 * <p>Apply it after the {@code java} / {@code kotlin} plugins. The plugin creates the
 * {@code preprocessor} extension and, per source set, tasks of the form
 * {@code preprocess<SourceSet><Language>} (e.g. {@code preprocessMainJava}) that write
 * preprocessed sources into {@code build/preprocessed/...}. The corresponding compile
 * and resource tasks are redirected to the processed output, so the original files are
 * never modified by a regular build.</p>
 */
public class PreProcessorPlugin implements Plugin<Project> {
    /** The plugin id. */
    public static final String PLUGIN_ID = "dev.oblivruin.preprocessor";
    /** The extension name. */
    public static final String EXTENSION_NAME = "preprocessor";
    /** The group all plugin tasks are registered in. */
    public static final String TASK_GROUP = "preprocessor";

    @Override
    public void apply(Project project) {
        project.getExtensions().create(EXTENSION_NAME, PreprocessorExtension.class, project.getObjects());

        project.getTasks().register("preprocessAll", task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Runs every preprocess* task of this project.");
            task.dependsOn(project.getTasks().withType(PreprocessTask.class));
        });
        project.getTasks().register("applyPreprocess", task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Applies preprocessing to the original source files in place (experimental).");
            task.dependsOn(project.getTasks().withType(ApplyPreprocessTask.class));
        });

        project.afterEvaluate(p -> {
            SourceSetContainer sourceSets = p.getExtensions().findByType(SourceSetContainer.class);
            if (sourceSets == null) {
                p.getLogger().warn("[preprocessor] no source sets found; apply the 'java' (or 'kotlin')"
                        + " plugin together with this plugin to enable preprocessing");
                return;
            }
            SourceSetWiring.warnAboutNonSerializableLogic(
                    p, (PreprocessorExtension) p.getExtensions().getByName(EXTENSION_NAME));
            Set<String> wired = new HashSet<String>();
            for (SourceSet sourceSet : sourceSets) {
                SourceSetWiring.wire(p, (PreprocessorExtension) p.getExtensions().getByName(EXTENSION_NAME),
                        sourceSet, wired);
            }
        });
    }
}
