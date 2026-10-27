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
        Path source = locateDemoDirectory("demo");
        demoDir = new File(workDir, "demo");
        copyTree(source, demoDir.toPath());
    }

    /** The test JVM working directory is normally the project directory; be tolerant anyway. */
    static Path locateDemoDirectory(String name) {
        Path candidate = new File(System.getProperty("user.dir"), name).toPath();
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        candidate = new File(System.getProperty("user.dir"), "../" + name).toPath().normalize();
        assertTrue(Files.isDirectory(candidate), "demo project '" + name + "' not found relative to "
                + System.getProperty("user.dir"));
        return candidate;
    }

    static void copyTree(Path source, Path target) throws IOException {
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

        // java: text processors ran (header + TODO stripping), live fabric/modern
        // branches, commented forge branch, custom directive and the per task variable
        Path platform = new File(demoDir, "build/preprocessed/main/java/demo/Platform.java").toPath();
        String platformText = text(platform);
        assertTrue(platformText.startsWith("// preprocessed with the preprocessor demo"),
                "the demo header processor must prepend a line:\n" + platformText);
        assertFalse(platformText.contains("// TODO"), "stripTodo processor must remove TODO lines");
        assertTrue(platformText.contains("return \"fabric\";"));
        assertTrue(platformText.contains("//$$ return \"forge\";"));
        assertTrue(platformText.contains("//$$ return \"unknown\";"));
        assertTrue(platformText.contains("// tag[release] = stable"), "custom directive output");
        assertTrue(platformText.contains("return \"java\";"), "JAVA_ONLY from javaTask { }");
        assertTrue(platformText.contains("return platform + \" (modern)\";"), "nested condition");
        assertTrue(platformText.contains("return \"experimental\";"), "defined X is true for value \"0\"");
        assertTrue(platformText.contains("return \"local\";"), "CI provider variable is never \"definitely-not-set\"");
        assertTrue(platformText.contains("return \"minor five, modern\";"), "//#var assignment + numeric comparison");
        assertTrue(platformText.contains("return \"unset worked\";"), "//#unset + !defined");
        assertTrue(platformText.contains("return \"fabric two word\";"), "//#else if two word form");
        assertTrue(platformText.contains("//#error unsupported platform"),
                "the error directive of an inactive branch must stay inert");
        assertTrue(platformText.contains("return \"supported\";"));
        assertFalse(platformText.contains("\n        return \"never\";"),
                "the dead branch body stays commented (the //$$ line keeps the text)");

        // the //#warning of the live branch surfaces in the build log
        assertTrue(result.getOutput().contains("this shows in the build log"),
                "warning directive output:\n" + result.getOutput());

        // remapper rewrote the legacy reference in the processed output
        Path remapped = new File(demoDir, "build/preprocessed/main/java/demo/Remapped.java").toPath();
        String remappedText = text(remapped);
        assertTrue(remappedText.contains("demo.api.NewApi.name()"), "remapper rewrote the legacy reference");
        assertFalse(remappedText.contains("demo.legacy.OldApi"), "the legacy reference must be gone");

        // the excluded legacy directory is copied verbatim: its //#tag line was NOT executed
        Path oldApi = new File(demoDir, "build/preprocessed/main/java/demo/legacy/OldApi.java").toPath();
        assertTrue(text(oldApi).contains("//#tag excluded"),
                "excluded files must keep their directive lines verbatim");

        // kotlin: wired through AbstractCompile without a compile time dependency
        Path greeting = new File(demoDir, "build/preprocessed/main/kotlin/demo/Greeting.kt").toPath();
        assertTrue(text(greeting).contains("return \"hello from kotlin\""));
        assertTrue(text(greeting).contains("//$$ return \"hello\""));

        // resources: json suffix uses the //@ keyword set and the RES_ONLY task variable
        Path features = new File(demoDir, "build/preprocessed/main/resources/demo/features.json").toPath();
        String featuresText = text(features);
        assertTrue(featuresText.contains("\"loader\": \"fabric\""));
        assertTrue(featuresText.contains("//$$ \"loader\": \"universal\","));

        // test source set: onlyJava + strict + BLANK policy + attachTo compileTestJava
        Path demoTest = new File(demoDir, "build/preprocessed/test/java/demo/DemoTest.java").toPath();
        String testText = text(demoTest);
        assertTrue(testText.contains("return \"test-fabric\";"));
        assertFalse(testText.contains("test-other"), "the BLANK policy removes the inactive body");
        assertFalse(testText.contains("//#else"), "the BLANK policy blanks directive lines");

        // the jar contains the compiled classes and the processed resource
        File jar = new File(demoDir, "build/libs/preprocessor-demo.jar");
        assertTrue(jar.isFile(), "the jar must exist:\n" + result.getOutput());
        try (ZipFile zip = new ZipFile(jar)) {
            assertTrue(zip.getEntry("demo/Platform.class") != null, "compiled java must be packaged");
            ZipEntry json = zip.getEntry("demo/features.json");
            assertTrue(json != null, "processed resource must be packaged");
            assertTrue(new String(readAll(zip.getInputStream(json)), StandardCharsets.UTF_8)
                    .contains("\"loader\": \"fabric\""));
        }
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

        // the flipped configuration must also compile (exercises the else branches of the
        // main and the test source set)
        BuildResult compiled = run("compileJava", "compileTestJava", "-Ppreprocessor.var.PLATFORM=forge");
        assertEquals(TaskOutcome.SUCCESS, compiled.task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, compiled.task(":compileTestJava").getOutcome());
    }

    @Test
    void demoConfigurationCacheRoundTrip() throws IOException {
        BuildResult first = run("preprocessJava", "compileJava", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, first.task(":preprocessJava").getOutcome());
        assertFalse(first.getOutput().contains("custom logic that is not java.io.Serializable"),
                "the demo directive and processors are Serializable and must not be reported:\n"
                        + first.getOutput());

        BuildResult second = run("preprocessJava", "compileJava", "--configuration-cache");
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":preprocessJava").getOutcome());
        assertTrue(second.getOutput().contains("Reusing configuration cache"),
                "the demo project must reuse the configuration cache entry:\n" + second.getOutput());
    }

    @Test
    void demoBuildCacheRoundTrip() throws IOException {
        // the demo opts in via cacheWithCustomLogic = true although custom logic is
        // registered, so the task is fully cacheable
        run("preprocessJava", "--build-cache");
        BuildResult cached = run("clean", "preprocessJava", "--build-cache");
        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":preprocessJava").getOutcome(),
                "the cached task must be restored from the build cache:\n" + cached.getOutput());
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
}
