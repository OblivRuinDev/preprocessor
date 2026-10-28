package dev.oblivruin.preprocessor;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
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

    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;

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
        return demoRunner(demoDir, arguments).build();
    }

    private BuildResult runAndFail(String... arguments) {
        return demoRunner(demoDir, arguments).buildAndFail();
    }

    /**
     * Builds the runner for the demo builds. When the optional Gradle version matrix is
     * active ({@code gradlew test -Pdemo.gradle.version=9.8.2 --tests "...Demo..."}), the
     * demo projects run under the given Gradle distribution via TestKit instead of the
     * wrapper version. Gradle 9 enables the configuration cache by default, which script
     * defined handlers cannot be stored in (see the README), so it is disabled for the
     * regular runs; the dedicated configuration cache tests pass the flag explicitly.
     */
    static GradleRunner demoRunner(File projectDir, String... arguments) {
        java.util.List<String> args = new java.util.ArrayList<String>(java.util.Arrays.asList(arguments));
        String version = System.getProperty("demo.gradle.version", "");
        if (!version.isEmpty() && version.startsWith("9.") && !args.contains("--configuration-cache")) {
            args.add("--no-configuration-cache");
        }
        GradleRunner runner = GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDir)
                .withArguments(args);
        if (!version.isEmpty()) {
            runner = runner.withGradleVersion(version);
        }
        return runner;
    }

    private static String text(Path path, Charset charset) throws IOException {
        return new String(Files.readAllBytes(path), charset);
    }

    @Test
    void demoBuildProducesProcessedSourcesResourcesAndJar() throws IOException {
        BuildResult result = run("build");
        assertEquals(TaskOutcome.SUCCESS, result.task(":build").getOutcome());

        // java: text processors ran (header + TODO stripping), live fabric/modern
        // branches, commented forge branch, custom directive and the per task variable
        Path platform = new File(demoDir, "build/preprocessed/main/java/demo/Platform.java").toPath();
        String platformText = text(platform, StandardCharsets.UTF_8);
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
        String remappedText = text(remapped, StandardCharsets.UTF_8);
        assertTrue(remappedText.contains("demo.api.NewApi.name()"), "remapper rewrote the legacy reference");
        assertFalse(remappedText.contains("demo.legacy.OldApi"), "the legacy reference must be gone");

        // the excluded legacy directory is copied verbatim: its //#tag line was NOT executed
        Path oldApi = new File(demoDir, "build/preprocessed/main/java/demo/legacy/OldApi.java").toPath();
        assertTrue(text(oldApi, StandardCharsets.UTF_8).contains("//#tag excluded"),
                "excluded files must keep their directive lines verbatim");

        // kotlin: wired through AbstractCompile without a compile time dependency
        Path greeting = new File(demoDir, "build/preprocessed/main/kotlin/demo/Greeting.kt").toPath();
        assertTrue(text(greeting, StandardCharsets.UTF_8).contains("return \"hello from kotlin\""));
        assertTrue(text(greeting, StandardCharsets.UTF_8).contains("//$$ return \"hello\""));

        // resources: json suffix uses the //@ keyword set and the RES_ONLY task variable
        Path features = new File(demoDir, "build/preprocessed/main/resources/demo/features.json").toPath();
        String featuresText = text(features, StandardCharsets.UTF_8);
        assertTrue(featuresText.contains("\"loader\": \"fabric\""));
        assertTrue(featuresText.contains("//$$ \"loader\": \"universal\","));

        // config source set: onlyResources + per source set keyword override (//@ for txt)
        // + the REMOVE policy (directives deleted, plain properties file remains). It runs
        // during the build because attachTo 'check' wired a task dependency
        Path metadata = new File(demoDir, "build/preprocessed/config/resources/demo/metadata.txt").toPath();
        String metadataText = text(metadata, StandardCharsets.UTF_8);
        assertTrue(metadataText.contains("mode=config-only"), "CONFIG_ONLY branch is live:\n" + metadataText);
        assertFalse(metadataText.contains("//@if"), "REMOVE policy deletes the directive lines");
        assertFalse(metadataText.contains("mode=universal"), "REMOVE policy deletes the inactive lines");

        // test source set: onlyJava + BLANK policy + per source set ISO-8859-1 encoding +
        // attachTo compileTestJava (the demo build compiles it with the matching -encoding)
        Path demoTest = new File(demoDir, "build/preprocessed/test/java/demo/DemoTest.java").toPath();
        String testText = text(demoTest, LATIN1);
        assertTrue(testText.contains("return \"test-fabric caf\u00e9\";"),
                "the ISO-8859-1 encoded non ASCII character must round trip:\n" + testText);
        assertFalse(testText.contains("test-other"), "the BLANK policy removes the inactive body");
        assertFalse(testText.contains("//#else"), "the BLANK policy blanks directive lines");

        // samples source set: detached, so its preprocess output is NOT produced by a
        // regular build (verified in demoPreprocessAllAndApplyPreprocess); the original
        // kotlin file still compiles because the build depends on compileSamplesKotlin
        assertFalse(Files.exists(new File(demoDir, "build/preprocessed/samples").toPath()),
                "detached source sets produce nothing during a regular build");
        assertFalse(Files.exists(new File(demoDir, "build/preprocessed/integration").toPath()),
                "disabled source sets produce nothing at all");

        // the jar contains the compiled main classes and the processed resource, but
        // neither the detached samples nor the disabled integration classes
        File jar = new File(demoDir, "build/libs/preprocessor-demo.jar");
        assertTrue(jar.isFile(), "the jar must exist:\n" + result.getOutput());
        try (ZipFile zip = new ZipFile(jar)) {
            assertTrue(zip.getEntry("demo/Platform.class") != null, "compiled java must be packaged");
            ZipEntry json = zip.getEntry("demo/features.json");
            assertTrue(json != null, "processed resource must be packaged");
            assertTrue(new String(readAll(zip.getInputStream(json)), StandardCharsets.UTF_8)
                    .contains("\"loader\": \"fabric\""));
            assertTrue(zip.getEntry("demo/samples/Sample.class") == null,
                    "detached source sets must not end up in the jar");
            assertTrue(zip.getEntry("demo/integration/Integration.class") == null,
                    "disabled source sets must not end up in the jar");
        }

        // the disabled source set has no preprocess task at all
        BuildResult missing = runAndFail("preprocessIntegrationJava");
        assertTrue(missing.getOutput().contains("not found"),
                "a disabled source set must have no preprocess task:\n" + missing.getOutput());
    }

    @Test
    void demoPreprocessAllAndApplyPreprocess() throws IOException {
        // preprocessAll runs every preprocess task of every enabled source set, including
        // the detached samples source set (which a regular build does not run)
        BuildResult all = run("preprocessAll");
        assertEquals(TaskOutcome.SUCCESS, all.task(":preprocessAll").getOutcome());

        Path sample = new File(demoDir, "build/preprocessed/samples/kotlin/demo/samples/Sample.kt").toPath();
        String sampleText = text(sample, StandardCharsets.UTF_8);
        assertTrue(sampleText.contains("fun base(): String = \"sample base\""), "the live branch body:\n" + sampleText);
        assertTrue(sampleText.contains("//$$ fun experimental(): String = \"experimental\""),
                "the unknown variable branch stays commented (per source set strict override)");
        assertTrue(all.getOutput().contains("NEVER_CONFIGURED"),
                "the strict=false override warns about the unknown variable:\n" + all.getOutput());

        // applyPreprocess rewrites the ORIGINAL files in place
        BuildResult apply = run("applyPreprocess");
        assertEquals(TaskOutcome.SUCCESS, apply.task(":applyPreprocess").getOutcome());
        assertTrue(apply.getOutput().contains("applied preprocessing to"));

        Path originalPlatform = new File(demoDir, "src/main/java/demo/Platform.java").toPath();
        String originalText = text(originalPlatform, StandardCharsets.UTF_8);
        assertTrue(originalText.startsWith("// preprocessed with the preprocessor demo"),
                "the original source was rewritten in place:\n" + originalText);
        assertTrue(originalText.contains("return \"fabric\";"), "the live branch is active after applying");
        assertTrue(originalText.indexOf("// preprocessed with the preprocessor demo")
                == originalText.lastIndexOf("// preprocessed with the preprocessor demo"),
                "the header processor guard must not duplicate the header on later cycles");

        // a full build on top of the applied sources stays green and keeps the applied
        // originals stable (reprocessing is idempotent)
        BuildResult rebuild = run("build");
        assertEquals(TaskOutcome.SUCCESS, rebuild.task(":build").getOutcome());
        int headerLines = 0;
        for (String line : text(originalPlatform, StandardCharsets.UTF_8).split("\n", -1)) {
            if (line.startsWith("// preprocessed")) {
                headerLines++;
            }
        }
        assertEquals(1, headerLines, "the idempotency guard must keep the applied source stable");
    }

    @Test
    void demoUpToDateCheckAndCommandLineOverride() throws IOException {
        assertEquals(TaskOutcome.SUCCESS, run("preprocessJava").task(":preprocessJava").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, run("preprocessJava").task(":preprocessJava").getOutcome());

        // a command line variable wins over the source set and global variables
        BuildResult flipped = run("preprocessJava", "-Ppreprocessor.var.PLATFORM=forge");
        assertEquals(TaskOutcome.SUCCESS, flipped.task(":preprocessJava").getOutcome());
        Path platform = new File(demoDir, "build/preprocessed/main/java/demo/Platform.java").toPath();
        String platformText = text(platform, StandardCharsets.UTF_8);
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
        // the console wording differs across Gradle versions
        assertTrue(second.getOutput().contains("Reusing configuration cache")
                        || second.getOutput().contains("Configuration cache entry reused"),
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
