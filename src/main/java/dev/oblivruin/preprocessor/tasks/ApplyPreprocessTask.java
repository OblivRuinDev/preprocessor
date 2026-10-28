package dev.oblivruin.preprocessor.tasks;

import dev.oblivruin.preprocessor.internal.FileUtils;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Experimental task that applies preprocessing to the <em>original</em> source files:
 * inactive lines are commented out with the eval marker (e.g. {@code //$$}) so the
 * sources remain compilable and readable in the IDE.
 *
 * <p>This task rewrites the source files in place; it is never up to date and is not
 * wired into the build graph automatically. Run it manually, e.g. with
 * {@code gradlew applyPreprocess}. The transformation is idempotent and can be undone
 * with your version control system.</p>
 */
@DisableCachingByDefault(because = "it rewrites its own input files in place")
public class ApplyPreprocessTask extends AbstractPreprocessTask {

    @Inject
    public ApplyPreprocessTask(ObjectFactory objects) {
        super(objects);
        // the task rewrites its own input files, so it can never be up to date
        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    public void execute() throws IOException {
        List<String> warnings = new ArrayList<>();
        int[] changed = new int[1];
        forEachSourceFile(new SourceFileHandler() {
            @Override
            public void untouched(Path file, String relative) {
                // left untouched: binary or filtered out
            }

            @Override
            public void preprocessed(Path file, String relative, byte[] processed, boolean bom,
                    String original, String processedContent) throws IOException {
                if (!processedContent.equals(original)) {
                    FileUtils.writeIfChanged(file, bom ? FileUtils.withBom(processed) : processed);
                    changed[0]++;
                }
            }
        }, warnings);

        getLogger().lifecycle("[preprocessor] applied preprocessing to {} file(s)", changed[0]);
        reportWarnings(warnings);
    }
}
