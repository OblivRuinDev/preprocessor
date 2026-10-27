package dev.oblivruin.preprocessor.tasks;

import dev.oblivruin.preprocessor.internal.FileUtils;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Preprocesses every file below the configured source directories into the output
 * directory. The task is cacheable and participates in up-to-date checks; the output
 * directory always mirrors the input files, stale outputs are removed.
 *
 * <p>Files are written while they are processed; only the relative paths of the outputs
 * are held in memory (for the stale cleanup), so the memory footprint is proportional
 * to a single file instead of the whole source set.</p>
 */
@CacheableTask
public class PreprocessTask extends AbstractPreprocessTask {
    private final DirectoryProperty outputDir;

    @Inject
    public PreprocessTask(ObjectFactory objects) {
        super(objects);
        this.outputDir = objects.directoryProperty();
    }

    /** The directory the processed files are written to. */
    @OutputDirectory
    public DirectoryProperty getOutputDir() {
        return outputDir;
    }

    @TaskAction
    public void execute() throws IOException {
        List<String> warnings = new ArrayList<String>();
        Set<String> keep = new LinkedHashSet<String>();
        Path root = getOutputDir().get().getAsFile().toPath();
        Files.createDirectories(root);

        forEachSourceFile(new SourceFileHandler() {
            @Override
            public void untouched(Path file, String relative) throws IOException {
                note(relative);
                // binary / filtered out: streamed copy, constant memory, no rewrite when equal
                FileUtils.copyIfChanged(file, root.resolve(relative));
            }

            @Override
            public void preprocessed(Path file, String relative, byte[] processed, boolean bom,
                    String original, String processedContent) throws IOException {
                note(relative);
                FileUtils.writeIfChanged(root.resolve(relative), bom ? FileUtils.withBom(processed) : processed);
            }

            private void note(String relative) {
                if (!keep.add(relative)) {
                    warnings.add("[preprocessor] duplicate relative path '" + relative
                            + "' across source directories; the later source directory wins");
                }
            }
        }, warnings);

        FileUtils.deleteStale(root, keep);
        reportWarnings(warnings);
    }
}
