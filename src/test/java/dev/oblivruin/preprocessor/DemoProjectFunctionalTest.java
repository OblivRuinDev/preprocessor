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
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests against the complete demo project ({@code demo/}, see its README).
 * The project is copied to a temporary directory, so the checked in sources stay clean.
 */
class DemoProjectFunctionalTest {

    @TempDir
    File workDir;

    private File demoDir;

    @BeforeEach
    void copyDemoProject() throws IOException {
        Path source = locateDemoDirectory();
        demoDir = new File(workDir, "demo");
        copyTree(source, demoDir.toPath());
    }

    /** The test JVM working directory is normally the project directory; be tolerant anyway. */
    private static Path locateDemoDirectory() {
        Path candidate = new File(System.getProperty("user.dir"), "demo").toPath();
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        candidate = new File(System.getProperty("user.dir"), "../demo").toPath().normalize();
        assertTrue(Files.isDirectory(candidate), "demo project not found relative to "
                + System.getProperty("user.dir"));
        return candidate;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs) {
                // skip build outputs of a previous local demo run
                return "build".equals(dir.getFileName().toString()) || ".gradle".equals(dir.getFileName().toString())
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                        : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Files.createDirectories(target.resolve(relative).getParent());
                Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private BuildResult run(String... arguments) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(demoDir)
                .withArguments(arguments)
                .build();
    }

    private static String text(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    void demoBuildProducesProcessedSourcesResourcesAndJar() throws IOException {
        BuildResult result = run("build");
        assertEquals(TaskOutcome.SUCCESS, result.task(":build").getOutcome());

        // java: live fabric/modern branches, commented forge branch, custom directive,
        // remapper and the per task JAVA_ONLY variable
        Path platform = new File(demoDir, "build/preprocessed/main/java/demo/Platform.java").toPath();
        String platformText = text(platform);
        assertTrue(platformText.contains("return \"fabric\";"));
        assertTrue(platformText.contains("//$$ return \"forge\";"));
        assertTrue(platformText.contains("//$$ return \"unknown\";"));
        assertTrue(platformText.contains("// tag[release] = stable"), "custom directive output");
        assertTrue(platformText.contains("return \"java\";"), "JAVA_ONLY from javaTask { }");
        assertTrue(platformText.contains("return platform + \" (modern)\";"), "nested condition");

        Path remapped = new File(demoDir, "build/preprocessed/main/java/demo/Remapped.java").toPath();
        String remappedText = text(remapped);
        assertTrue(remappedText.contains("demo.api.NewApi.name()"), "remapper rewrote the legacy reference");
        assertFalse(remappedText.contains("demo.legacy.OldApi"), "the legacy reference must be gone");

        // kotlin: wired through AbstractCompile without a compile time dependency
        Path greeting = new File(demoDir, "build/preprocessed/main/kotlin/demo/Greeting.kt").toPath();
        assertTrue(text(greeting).contains("return \"hello from kotlin\""));
        assertTrue(text(greeting).contains("//$$ return \"hello\""));

        // resources: json suffix uses the //@ keyword set
        Path features = new File(demoDir, "build/preprocessed/main/resources/demo/features.json").toPath();
        String featuresText = text(features);
        assertTrue(featuresText.contains("\"loader\": \"fabric\""));
        assertTrue(featuresText.contains("//$$ \"loader\": \"universal\","));

        // the jar contains the compiled classes and the processed resource
        File jar = new File(demoDir, "build/libs/preprocessor-demo.jar");
        assertTrue(jar.isFile(), "the jar must exist:\n" + result.getOutput());
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry platformClass = zip.getEntry("demo/Platform.class");
            assertTrue(platformClass != null, "compiled java must be packaged");
            ZipEntry json = zip.getEntry("demo/features.json");
            assertTrue(json != null, "processed resource must be packaged");
            assertTrue(new String(readAll(zip.getInputStream(json)), StandardCharsets.UTF_8)
                    .contains("\"loader\": \"fabric\""));
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
        }
        in.close();
        return out.toByteArray();
    }

    @Test
    void demoUpToDateCheckAndCommandLineOverride() throws IOException {
        assertEquals(TaskOutcome.SUCCESS, run("preprocessJava").task(":preprocessJava").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, run("preprocessJava").task(":preprocessJava").getOutcome());

        // a command line variable wins over the source set and global variables
        BuildResult flipped = run("preprocessJava", "-Ppreprocessor.var.PLATFORM=forge");
        assertEquals(TaskOutcome.SUCCESS, flipped.task(":preprocessJava").getOutcome());
        Path platform = new File(demoDir, "build/preprocessed/main/java/demo/Platform.java").toPath();
        String platformText = text(platform);
        assertTrue(platformText.contains("return \"forge\";"));
        assertTrue(platformText.contains("//$$ return \"fabric\";"));

        // the flipped configuration must also compile (exercises the else branches)
        BuildResult compiled = run("build", "-Ppreprocessor.var.PLATFORM=forge");
        assertEquals(TaskOutcome.SUCCESS, compiled.task(":build").getOutcome());
    }

    @Test
    void demoConfigurationCacheRoundTrip() throws IOException {
        BuildResult first = run("preprocessJava", "compileJava", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, first.task(":preprocessJava").getOutcome());
        assertFalse(first.getOutput().contains("custom logic that is not java.io.Serializable"),
                "the demo directive is a Groovy closure and must not be reported:\n" + first.getOutput());

        BuildResult second = run("preprocessJava", "compileJava", "--configuration-cache");
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":preprocessJava").getOutcome());
        assertTrue(second.getOutput().contains("Reusing configuration cache"),
                "the demo project must reuse the configuration cache entry:\n" + second.getOutput());
    }
}
