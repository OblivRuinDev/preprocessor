package dev.oblivruin.preprocessor;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests running the plugin inside a real Gradle build via the TestKit.
 */
class PreprocessFunctionalTest {

    @TempDir
    File projectDir;

    @BeforeEach
    void prepareProject() throws IOException {
        write("settings.gradle", "rootProject.name = 'test-project'\n");
    }

    private void write(String path, String content) throws IOException {
        Path file = new File(projectDir, path).toPath();
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private BuildResult run(String... arguments) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDir)
                .withArguments(Arrays.asList(arguments))
                .build();
    }

    private BuildResult runAndFail(String... arguments) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDir)
                .withArguments(Arrays.asList(arguments))
                .buildAndFail();
    }

    @Test
    void preprocessJavaTaskProducesProcessedOutput() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('flag', '1')",
                "}"));
        write("src/main/java/demo/Hello.java", String.join("\n",
                "package demo;",
                "public class Hello {",
                "    public static String value() {",
                "        //#if flag == 1",
                "        return \"yes\";",
                "        //#else",
                "        return \"no\";",
                "        //#endif",
                "    }",
                "}"));

        BuildResult result = run("preprocessJava");

        assertEquals(TaskOutcome.SUCCESS, result.task(":preprocessJava").getOutcome());
        Path output = new File(projectDir, "build/preprocessed/main/java/demo/Hello.java").toPath();
        assertTrue(Files.exists(output), "processed file should exist");
        String processed = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertTrue(processed.contains("return \"yes\";"));
        assertTrue(processed.contains("        //$$ return \"no\";"));
        assertFalse(processed.contains("\n        return \"no\";"));
    }

    @Test
    void compileJavaUsesPreprocessedSources() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('mode', 'fast')",
                "}"));
        write("src/main/java/demo/Hello.java", String.join("\n",
                "package demo;",
                "public class Hello {",
                "    //#if mode == \"fast\"",
                "    public static int value() { return 42; }",
                "    //#else",
                "    //$$ public static int value() { return -1; }",
                "    //#endif",
                "}"));

        BuildResult result = run("compileJava");

        assertNotNull(result.task(":preprocessJava"), "preprocessJava must run before compileJava");
        assertNotNull(result.task(":compileJava"));
        assertTrue(new File(projectDir, "build/classes/java/main/demo/Hello.class").exists(),
                "the preprocessed source must compile");
        Path processed = new File(projectDir, "build/preprocessed/main/java/demo/Hello.java").toPath();
        String text = new String(Files.readAllBytes(processed), StandardCharsets.UTF_8);
        assertTrue(text.contains("public static int value() { return 42; }"));
    }

    @Test
    void processResourcesUsesPreprocessedResources() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('enabled', 'true')",
                "}"));
        write("src/main/resources/demo/config.txt", String.join("\n",
                "//#if enabled",
                "value=on",
                "//#else",
                "value=off",
                "//#endif"));

        run("processResources");

        Path output = new File(projectDir, "build/preprocessed/main/resources/demo/config.txt").toPath();
        assertTrue(Files.exists(output), "processed resource should exist");
        Path copied = new File(projectDir, "build/resources/main/demo/config.txt").toPath();
        assertTrue(Files.exists(copied), "processed resource must be part of the processed resources");
        String text = new String(Files.readAllBytes(copied), StandardCharsets.UTF_8);
        assertTrue(text.contains("value=on"));
    }

    @Test
    void upToDateWhenNothingChanged() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('flag', '1')",
                "}"));
        write("src/main/java/demo/Hello.java", "package demo;\npublic class Hello {}\n");

        run("preprocessJava");
        BuildResult second = run("preprocessJava");

        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":preprocessJava").getOutcome());
    }

    @Test
    void taskRerunsWhenVariableChanges() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('flag', project.hasProperty('flip') ? '0' : '1')",
                "}"));
        write("src/main/java/demo/Hello.java", String.join("\n",
                "package demo;",
                "public class Hello {",
                "    //#if flag",
                "    public static int value() { return 1; }",
                "    //#else",
                "    public static int value() { return 2; }",
                "    //#endif",
                "}"));

        run("preprocessJava");
        BuildResult flipped = run("preprocessJava", "-Pflip=true");

        assertEquals(TaskOutcome.SUCCESS, flipped.task(":preprocessJava").getOutcome());
        Path output = new File(projectDir, "build/preprocessed/main/java/demo/Hello.java").toPath();
        String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertTrue(text.contains("public static int value() { return 2; }"));
    }

    @Test
    void commandLinePropertyOverridesVariables() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('flag', '0')",
                "    sourceSets {",
                "        main { vars.put('flag', '0') }", // the command line must still win
                "    }",
                "}"));
        write("src/main/java/demo/Hello.java", String.join("\n",
                "package demo;",
                "public class Hello {",
                "    //#if flag",
                "    public static int value() { return 1; }",
                "    //#else",
                "    public static int value() { return 2; }",
                "    //#endif",
                "}"));

        BuildResult result = run("preprocessJava", "-Ppreprocessor.var.flag=1");

        Path output = new File(projectDir, "build/preprocessed/main/java/demo/Hello.java").toPath();
        String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertTrue(text.contains("public static int value() { return 1; }"));
    }

    @Test
    void includeExcludeDslFiltersFiles() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('flag', '0')",
                "    sourceSets {",
                "        main {",
                "            include 'demo/Kept.java'",
                "        }",
                "    }",
                "}"));
        String content = String.join("\n",
                "package demo;",
                "public class FILENAME {",
                "    //#if flag",
                "    public static int value() { return 1; }",
                "    //#endif",
                "}");
        write("src/main/java/demo/Kept.java", content.replace("FILENAME", "Kept"));
        write("src/main/java/demo/Skipped.java", content.replace("FILENAME", "Skipped"));

        run("preprocessJava");

        Path kept = new File(projectDir, "build/preprocessed/main/java/demo/Kept.java").toPath();
        String keptText = new String(Files.readAllBytes(kept), StandardCharsets.UTF_8);
        assertTrue(keptText.contains("//$$ public static int value()"), "the included file must be preprocessed");
        Path skipped = new File(projectDir, "build/preprocessed/main/java/demo/Skipped.java").toPath();
        String skippedText = new String(Files.readAllBytes(skipped), StandardCharsets.UTF_8);
        assertTrue(skippedText.contains("public static int value() { return 1; }"),
                "the excluded file must be copied verbatim (still live code)");
        assertTrue(skippedText.contains("//#if flag"), "the excluded file keeps its directive lines");
    }

    @Test
    void malformedEncodingIsReportedAsWarning() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('flag', '1')",
                "}"));
        // an ISO-8859-1 encoded file read as UTF-8 produces replacement characters
        String content = String.join("\n",
                "package demo;",
                "public class Bad {",
                "    String text = \"caf\u00e9\";",
                "}");
        write("src/main/java/demo/Bad.java", content); // ensures the directories exist
        Files.write(new File(projectDir, "src/main/java/demo/Bad.java").toPath(),
                content.getBytes(StandardCharsets.ISO_8859_1));

        BuildResult result = run("preprocessJava");

        assertEquals(TaskOutcome.SUCCESS, result.task(":preprocessJava").getOutcome());
        assertTrue(result.getOutput().contains("malformed input for the configured encoding"),
                "the encoding problem must be reported as a warning:\n" + result.getOutput());
    }

    @Test
    void customLogicInputsDriveReprocessing() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    customLogicInputs.from('logic/table.txt')",
                "    // the handler reads the declared input file at execution time; only a",
                "    // String path is captured so the closure stays Serializable",
                "    def tablePath = project.file('logic/table.txt').absolutePath",
                "    directives.register('map') { ctx ->",
                "        def replacement = new File(tablePath).text.trim()",
                "        ctx.replaceLine('// ' + ctx.argument() + ' -> ' + replacement)",
                "    }",
                "}"));
        write("logic/table.txt", "alpha\n");
        write("src/main/java/demo/Mapped.java", String.join("\n",
                "package demo;",
                "public class Mapped {",
                "    //#map hello",
                "}"));

        BuildResult first = run("preprocessJava");
        assertEquals(TaskOutcome.SUCCESS, first.task(":preprocessJava").getOutcome());
        Path output = new File(projectDir, "build/preprocessed/main/java/demo/Mapped.java").toPath();
        assertTrue(new String(Files.readAllBytes(output), StandardCharsets.UTF_8).contains("// hello -> alpha"));

        BuildResult unchanged = run("preprocessJava");
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":preprocessJava").getOutcome());

        // changing the declared input file must rerun the task and change the result
        write("logic/table.txt", "beta\n");
        BuildResult changed = run("preprocessJava");
        assertEquals(TaskOutcome.SUCCESS, changed.task(":preprocessJava").getOutcome());
        assertTrue(new String(Files.readAllBytes(output), StandardCharsets.UTF_8).contains("// hello -> beta"));
    }

    @Test
    void perSourceSetGranularity() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    autoConfigure = false",
                "    sourceSets {",
                "        main { onlyJava() }",
                "    }",
                "}"));
        write("src/main/java/demo/Hello.java", "package demo;\npublic class Hello {}\n");
        write("src/main/resources/demo/config.txt", "value=1\n");
        write("src/test/java/demo/Bye.java", "package demo;\npublic class Bye {}\n");

        run("assemble");

        assertTrue(new File(projectDir, "build/preprocessed/main/java/demo/Hello.java").exists(),
                "main java must be preprocessed");
        assertFalse(new File(projectDir, "build/preprocessed/main/resources/demo/config.txt").exists(),
                "resources are excluded by onlyJava()");
        assertFalse(new File(projectDir, "build/preprocessed/test/java/demo/Bye.java").exists(),
                "the test source set is not configured and must not be preprocessed");
    }

    @Test
    void customDirectiveFromBuildScript() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    autoConfigure = false",
                "    sourceSets { main { } }",
                "    directives.register('say') { ctx ->",
                "        ctx.replaceLine('// [say] ' + ctx.argument())",
                "    }",
                "}"));
        write("src/main/java/demo/Hello.java", String.join("\n",
                "package demo;",
                "public class Hello {",
                "    //#say hello world",
                "}"));

        run("preprocessJava");

        Path output = new File(projectDir, "build/preprocessed/main/java/demo/Hello.java").toPath();
        String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertTrue(text.contains("// [say] hello world"));
    }

    @Test
    void customKeywordsPerSuffix() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('a', '1')",
                "    keywords.put('json', new dev.oblivruin.preprocessor.Keywords('//@if', '//@elseif', '//@else', '//@endif', '//$$'))",
                "}"));
        write("src/main/resources/demo/mixins.json", String.join("\n",
                "{",
                "  //@if a",
                "  \"a\": true,",
                "  //@else",
                "  \"a\": false,",
                "  //@endif",
                "  \"b\": true",
                "}"));

        run("preprocessResources");

        Path output = new File(projectDir, "build/preprocessed/main/resources/demo/mixins.json").toPath();
        String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"a\": true"));
    }

    @Test
    void applyPreprocessCommentsInactiveLinesInPlace() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('flag', '1')",
                "}"));
        write("src/main/java/demo/Hello.java", String.join("\n",
                "package demo;",
                "public class Hello {",
                "    //#if flag",
                "    int a = 1;",
                "    //#else",
                "    int a = 2;",
                "    //#endif",
                "}"));

        run("applyPreprocess");

        Path source = new File(projectDir, "src/main/java/demo/Hello.java").toPath();
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue(text.contains("    int a = 1;"));
        assertTrue(text.contains("    //$$ int a = 2;"));
        // running it again must be idempotent
        run("applyPreprocess");
        String again = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertEquals(text, again);
    }

    @Test
    void nestedConditionsCompileInBothConfigurations() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('major', '1')",
                "    vars.put('minor', '1')",
                "}"));
        write("src/main/java/demo/Nested.java", String.join("\n",
                "package demo;",
                "public class Nested {",
                "    public static String mode() {",
                "        //#if major == 1",
                "        return \"one\";",
                "        //#elseif major == 2",
                "        return \"two\";",
                "        //#else",
                "        return \"other\";",
                "        //#endif",
                "    }",
                "",
                "    public static int deep() {",
                "        //#if major == 1",
                "        //#if minor",
                "        return 11;",
                "        //#else",
                "        return 10;",
                "        //#endif",
                "        //#elseif major == 2",
                "        return 2;",
                "        //#else",
                "        return 0;",
                "        //#endif",
                "    }",
                "}"));

        // configuration 1: major=1, minor=1 (from the build script)
        run("compileJava");
        Path output = new File(projectDir, "build/preprocessed/main/java/demo/Nested.java").toPath();
        String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertTrue(text.contains("return \"one\";"));
        assertTrue(text.contains("        return 11;"));
        assertTrue(text.contains("        //$$ return 10;"));
        assertTrue(text.contains("        //$$ return 2;"));
        assertFalse(text.contains("\n        return 10;"));
        assertTrue(new File(projectDir, "build/classes/java/main/demo/Nested.class").exists(),
                "the nested preprocessed source must compile");

        // configuration 2: major=2 through command line properties
        BuildResult flipped = run("preprocessJava", "-Ppreprocessor.var.major=2", "-Ppreprocessor.var.minor=0");
        assertEquals(TaskOutcome.SUCCESS, flipped.task(":preprocessJava").getOutcome());
        String flippedText = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertTrue(flippedText.contains("return \"two\";"));
        assertTrue(flippedText.contains("        return 2;"));
        assertTrue(flippedText.contains("        //$$ return 11;"));
        // the nested elseif chain inside the dead major==1 branch must stay commented
        assertTrue(flippedText.contains("        //$$ return 10;"));

        // the reprocessed variant must also be valid java
        run("compileJava");
        assertTrue(new File(projectDir, "build/classes/java/main/demo/Nested.class").exists());
    }

    @Test
    void detachKeepsConsumersOnOriginalSources() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    sourceSets {",
                "        main { detach() }",
                "    }",
                "}"));
        // with PRE=1 the preprocessed file compiles while the original is invalid
        // (both return statements -> unreachable code), so a failure proves that
        // compileJava still consumes the original sources
        write("src/main/java/demo/Guarded.java", String.join("\n",
                "package demo;",
                "public class Guarded {",
                "    public static String value() {",
                "        //#if PRE",
                "        return \"pre\";",
                "        //#else",
                "        return \"orig\";",
                "        //#endif",
                "    }",
                "}"));

        BuildResult result = runAndFail("compileJava", "-Ppreprocessor.var.PRE=1");
        assertEquals(TaskOutcome.FAILED, result.task(":compileJava").getOutcome());
        assertFalse(new File(projectDir, "build/preprocessed").exists(),
                "nothing may depend on the preprocess tasks when detached");
    }

    @Test
    void attachToRestrictsAttachmentToSpecifiedTasks() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    inactiveLines = dev.oblivruin.preprocessor.LinePolicy.REMOVE",
                "    sourceSets {",
                "        main { attachTo 'compileJava' }",
                "    }",
                "}"));
        write("src/main/java/demo/Guarded.java", String.join("\n",
                "package demo;",
                "public class Guarded {",
                "    public static String value() {",
                "        //#if PRE",
                "        return \"pre\";",
                "        //#else",
                "        return \"orig\";",
                "        //#endif",
                "    }",
                "}"));
        write("src/main/resources/demo/r.txt", String.join("\n",
                "original",
                "//#if PRE",
                "replaced",
                "//#endif",
                ""));

        // compileJava is attached: the preprocessed (valid) source is compiled
        // processResources is NOT attached: the original resource is copied verbatim
        BuildResult result = run("compileJava", "processResources", "-Ppreprocessor.var.PRE=1");
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":processResources").getOutcome());

        String resource = new String(Files.readAllBytes(
                new File(projectDir, "build/resources/main/demo/r.txt").toPath()), StandardCharsets.UTF_8);
        assertTrue(resource.contains("//#if PRE"),
                "processResources must keep the original resource when it is not attached");
        assertTrue(new File(projectDir, "build/classes/java/main/demo/Guarded.class").exists());
    }

    @Test
    void attachToCustomTaskGetsDependencyOnly() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "tasks.register('consumeSources', Copy) {",
                "    from 'src/main/java'",
                "    into 'build/consumed'",
                "}",
                "preprocessor {",
                "    sourceSets {",
                "        main { attachTo 'consumeSources' }",
                "    }",
                "}"));
        write("src/main/java/demo/Plain.java", String.join("\n",
                "package demo;",
                "public class Plain {",
                "    //#if PRE",
                "    static final int MARK = 1;",
                "    //#endif",
                "}"));

        BuildResult result = run("consumeSources");
        // the custom task only gets a dependency: the preprocess task ran, but the
        // Copy task still consumed the original file
        assertEquals(TaskOutcome.SUCCESS, result.task(":preprocessJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":consumeSources").getOutcome());
        String copied = new String(Files.readAllBytes(
                new File(projectDir, "build/consumed/demo/Plain.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(copied.contains("//#if PRE"), "a non compiling task must keep consuming the original files");
    }

    @Test
    void attachToUnknownTaskFailsFast() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    sourceSets {",
                "        main { attachTo 'noSuchTask' }",
                "    }",
                "}"));

        BuildResult result = runAndFail("help");
        assertTrue(result.getOutput().contains("'noSuchTask'"),
                "an unknown attachTo target must be reported immediately");
    }

    @Test
    void taskLevelVarsDifferPerLanguage() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('SHARED', '1')",
                "    sourceSets {",
                "        main {",
                "            javaTask { vars.put('MODE', 'java') }",
                "            resourcesTask { vars.put('MODE', 'res') }",
                "        }",
                "    }",
                "}"));
        write("src/main/java/pertask/Tag.java", String.join("\n",
                "package pertask;",
                "public class Tag {",
                "    //#if SHARED",
                "    static final boolean SHARED_OK = true;",
                "    //#endif",
                "    public static String mode() {",
                "        //#if MODE == 'java'",
                "        return \"java\";",
                "        //#elseif MODE == 'res'",
                "        return \"res\";",
                "        //#else",
                "        return \"base\";",
                "        //#endif",
                "    }",
                "}"));
        write("src/main/resources/pertask/r.txt", String.join("\n",
                "//#if MODE == 'res'",
                "mode=res",
                "//#elseif MODE == 'java'",
                "mode=java",
                "//#else",
                "mode=base",
                "//#endif",
                ""));

        BuildResult result = run("preprocessAll");
        assertEquals(TaskOutcome.SUCCESS, result.task(":preprocessJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":preprocessResources").getOutcome());

        String java = new String(Files.readAllBytes(
                new File(projectDir, "build/preprocessed/main/java/pertask/Tag.java").toPath()), StandardCharsets.UTF_8);
        // the global variable is still inherited by the task ...
        assertTrue(java.contains("static final boolean SHARED_OK = true;"));
        // ... while the task level value overrides MODE for this task only
        assertTrue(java.contains("        return \"java\";"), () -> "JAVA OUTPUT WAS:\n" + java);
        assertTrue(java.contains("        //$$ return \"res\";"));
        assertFalse(java.contains("\n        return \"res\";"));

        String resources = new String(Files.readAllBytes(
                new File(projectDir, "build/preprocessed/main/resources/pertask/r.txt").toPath()), StandardCharsets.UTF_8);
        assertTrue(resources.contains("mode=res"));
        assertTrue(resources.contains("//$$ mode=java"));
        assertFalse(resources.contains("\nmode=java"));
    }

    @Test
    void preprocessTasksAreCacheableWithoutCustomLogic() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('CACHE_CHECK', 'cachetest-9157')",
                "}"));
        write("src/main/java/cachecheck/Cached.java", String.join("\n",
                "package cachecheck;",
                "public class Cached {",
                "    public static final String ID = \"cachecheck-unique-9157\";",
                "    //#if CACHE_CHECK",
                "    static final boolean MARKED = true;",
                "    //#endif",
                "}"));

        BuildResult first = run("preprocessJava", "--build-cache");
        assertEquals(TaskOutcome.SUCCESS, first.task(":preprocessJava").getOutcome());

        // the outputs are gone after clean, so the second run can only come from the build cache
        BuildResult second = run("clean", "preprocessJava", "--build-cache");
        assertEquals(TaskOutcome.FROM_CACHE, second.task(":preprocessJava").getOutcome());
    }

    @Test
    void customLogicDisablesCachingByDefault() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('CACHE_CHECK', 'customlogic-4211')",
                "    directives.register('mark') { ctx -> ctx.replaceLine('// [mark] ' + ctx.argument()) }",
                "}"));
        write("src/main/java/cachecheck/Marked.java", String.join("\n",
                "package cachecheck;",
                "public class Marked {",
                "    //#mark hello-4211",
                "}"));

        BuildResult first = run("preprocessJava", "--build-cache");
        assertEquals(TaskOutcome.SUCCESS, first.task(":preprocessJava").getOutcome());

        // custom logic is not part of the cache key, so the task must be executed again
        // (never silently served from the cache with stale logic)
        BuildResult second = run("clean", "preprocessJava", "--build-cache");
        assertEquals(TaskOutcome.SUCCESS, second.task(":preprocessJava").getOutcome());
        String processed = new String(Files.readAllBytes(
                new File(projectDir, "build/preprocessed/main/java/cachecheck/Marked.java").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(processed.contains("// [mark] hello-4211"));
    }

    @Test
    void configurationCacheRoundTripWithoutCustomLogic() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('CC_CHECK', 'cachetest-5150')",
                "}"));
        write("src/main/java/cachecheck/Configured.java", String.join("\n",
                "package cachecheck;",
                "public class Configured {",
                "    public static final String ID = \"configuration-cache-unique-5150\";",
                "    //#if CC_CHECK",
                "    static final boolean MARKED = true;",
                "    //#endif",
                "}"));

        BuildResult first = run("preprocessJava", "compileJava", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, first.task(":preprocessJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileJava").getOutcome());
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                "the first run must store a configuration cache entry:\n" + first.getOutput());

        BuildResult second = run("preprocessJava", "compileJava", "--configuration-cache");
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":preprocessJava").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":compileJava").getOutcome());
        assertTrue(second.getOutput().contains("Reusing configuration cache"),
                "the second run must reuse the stored configuration cache entry:\n" + second.getOutput());
    }

    @Test
    void configurationCacheRoundTripWithGroovyClosureDirective() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'java'",
                "    id 'dev.oblivruin.preprocessor'",
                "}",
                "preprocessor {",
                "    vars.put('CC_CHECK', 'cachetest-5151')",
                "    directives.register('mark') { ctx -> ctx.replaceLine('// [mark] ' + ctx.argument()) }",
                "}"));
        write("src/main/java/cachecheck/Marked.java", String.join("\n",
                "package cachecheck;",
                "public class Marked {",
                "    //#mark closure-5151",
                "}"));

        BuildResult first = run("preprocessJava", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, first.task(":preprocessJava").getOutcome());
        assertFalse(first.getOutput().contains("custom logic that is not java.io.Serializable"),
                "a Groovy closure is Serializable and must not be reported:\n" + first.getOutput());

        BuildResult second = run("preprocessJava", "--configuration-cache");
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":preprocessJava").getOutcome());
        assertTrue(second.getOutput().contains("Reusing configuration cache"),
                "the configuration cache entry with the groovy closure directive must be reusable:\n"
                        + second.getOutput());
    }

    @Test
    void configurationCacheRejectsNonSerializableKotlinLambdaDirectives() throws IOException {
        // Kotlin DSL SAM lambdas are not Serializable, so Gradle cannot store the task
        // state in the configuration cache; Groovy closures (Serializable) work, see the
        // round trip test above
        write("build.gradle.kts", String.join("\n",
                "plugins {",
                "    java",
                "    id(\"dev.oblivruin.preprocessor\")",
                "}",
                "preprocessor {",
                "    directives.register(\"mark\") { it.replaceLine(\"// [mark] \" + it.argument()) }",
                "}"));
        write("src/main/java/probe/P.java", String.join("\n",
                "package probe;",
                "public class P {",
                "    //#mark hi",
                "}"));

        BuildResult result = runAndFail("preprocessJava", "--configuration-cache");
        assertTrue(result.getOutput().contains("DirectiveHandler"),
                "the configuration cache must report the unserializable handler type:\n" + result.getOutput());
        assertTrue(result.getOutput().contains("custom logic that is not java.io.Serializable"),
                "the plugin must warn about the offending handler before Gradle's cryptic error:\n"
                        + result.getOutput());
    }

    @Test
    void missingJavaPluginIsReported() throws IOException {
        write("build.gradle", String.join("\n",
                "plugins {",
                "    id 'dev.oblivruin.preprocessor'",
                "}"));

        BuildResult result = run("help");

        assertNotNull(result);
    }
}
