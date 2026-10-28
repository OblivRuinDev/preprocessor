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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests against the Kotlin DSL demo project ({@code demo-kts}, see its
 * README). The project is copied to a temporary directory, so the checked in sources
 * stay clean. The handlers there are Serializable object expressions, and the test
 * locks in the configuration cache limitation of script defined handler classes.
 */
class DemoKtsProjectFunctionalTest {

    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;

    @TempDir
    File workDir;

    private File demoDir;

    @BeforeEach
    void copyDemoProject() throws IOException {
        Path source = DemoProjectFunctionalTest.locateDemoDirectory("demo-kts");
        demoDir = new File(workDir, "demo-kts");
        DemoProjectFunctionalTest.copyTree(source, demoDir.toPath());
    }

    private BuildResult run(String... arguments) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(demoDir)
                .withArguments(arguments)
                .build();
    }

    private BuildResult runAndFail(String... arguments) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(demoDir)
                .withArguments(arguments)
                .buildAndFail();
    }

    private static String text(Path path, Charset charset) throws IOException {
        return new String(Files.readAllBytes(path), charset);
    }

    @Test
    void ktsDemoBuildProducesProcessedSourcesAndJar() throws IOException {
        BuildResult result = run("build");
        assertEquals(TaskOutcome.SUCCESS, result.task(":build").getOutcome());

        // java: processors, custom directive, live branches and per task variables all
        // work through the Kotlin DSL configuration
        Path platform = new File(demoDir, "build/preprocessed/main/java/demo/Platform.java").toPath();
        String platformText = text(platform, StandardCharsets.UTF_8);
        assertTrue(platformText.startsWith("// preprocessed with the preprocessor demo"),
                "the demo header processor must prepend a line:\n" + platformText);
        assertFalse(platformText.contains("// TODO"), "stripTodo processor must remove TODO lines");
        assertTrue(platformText.contains("return \"fabric\";"));
        assertTrue(platformText.contains("//$$ return \"forge\";"));
        assertTrue(platformText.contains("// tag[release] = stable"), "custom directive output");
        assertTrue(platformText.contains("return \"java\";"), "JAVA_ONLY from javaTask { }");
        assertTrue(platformText.contains("return platform + \" (modern)\";"), "nested condition");
        assertTrue(platformText.contains("return \"unset worked\";"), "//#unset + !defined");
        assertTrue(platformText.contains("return \"fabric two word\";"), "//#else if two word form");

        // remapper rewrote the legacy reference; the excluded legacy file is verbatim
        Path remapped = new File(demoDir, "build/preprocessed/main/java/demo/Remapped.java").toPath();
        assertTrue(text(remapped, StandardCharsets.UTF_8).contains("demo.api.NewApi.name()"), "remapper output");
        Path oldApi = new File(demoDir, "build/preprocessed/main/java/demo/legacy/OldApi.java").toPath();
        assertTrue(text(oldApi, StandardCharsets.UTF_8).contains("//#tag excluded"), "excluded files stay verbatim");

        // kotlin and resources work through the Kotlin DSL wiring as well
        Path greeting = new File(demoDir, "build/preprocessed/main/kotlin/demo/Greeting.kt").toPath();
        assertTrue(text(greeting, StandardCharsets.UTF_8).contains("return \"hello from kotlin\""));
        Path features = new File(demoDir, "build/preprocessed/main/resources/demo/features.json").toPath();
        assertTrue(text(features, StandardCharsets.UTF_8).contains("\"loader\": \"fabric\""));

        // config source set: onlyResources + per source set keyword override + REMOVE
        // policy + attachTo "check" (task dependency only)
        Path metadata = new File(demoDir, "build/preprocessed/config/resources/demo/metadata.txt").toPath();
        String metadataText = text(metadata, StandardCharsets.UTF_8);
        assertTrue(metadataText.contains("mode=config-only"), "CONFIG_ONLY branch is live:\n" + metadataText);
        assertFalse(metadataText.contains("//@if"), "REMOVE policy deletes the directive lines");
        assertFalse(metadataText.contains("mode=universal"), "REMOVE policy deletes the inactive lines");

        // test source set: onlyJava + BLANK policy + ISO-8859-1 through the Kotlin DSL
        Path demoTest = new File(demoDir, "build/preprocessed/test/java/demo/DemoTest.java").toPath();
        String testText = text(demoTest, LATIN1);
        assertTrue(testText.contains("return \"test-fabric caf\u00e9\";"),
                "the ISO-8859-1 encoded non ASCII character must round trip:\n" + testText);
        assertFalse(testText.contains("test-other"), "the BLANK policy removes the inactive body");

        // samples (detached) and integration (disabled) produce nothing in a build
        assertFalse(Files.exists(new File(demoDir, "build/preprocessed/samples").toPath()),
                "detached source sets produce nothing during a regular build");
        assertFalse(Files.exists(new File(demoDir, "build/preprocessed/integration").toPath()),
                "disabled source sets produce nothing at all");

        File jar = new File(demoDir, "build/libs/preprocessor-demo-kts.jar");
        assertTrue(jar.isFile(), "the jar must exist:\n" + result.getOutput());

        // applyPreprocessJava rewrites the original sources in place (idempotency is
        // covered by the Groovy demo test)
        BuildResult apply = run("applyPreprocessJava");
        assertEquals(TaskOutcome.SUCCESS, apply.task(":applyPreprocessJava").getOutcome());
        Path originalPlatform = new File(demoDir, "src/main/java/demo/Platform.java").toPath();
        String originalText = text(originalPlatform, StandardCharsets.UTF_8);
        assertTrue(originalText.startsWith("// preprocessed with the preprocessor demo"),
                "the original source was rewritten in place:\n" + originalText);
        assertTrue(originalText.contains("return \"fabric\";"), "the live branch is active after applying");
    }

    @Test
    void ktsDemoConfigurationCacheRejectsScriptDefinedHandlers() throws IOException {
        // Script defined handler objects (Kotlin object expressions, Groovy classes) are
        // not serializable for the configuration cache even when they implement
        // Serializable - Gradle cannot store classes that are defined in a build script.
        // Only Groovy closures (see the Groovy demo) or handler classes from a stable
        // classpath (buildSrc / a plugin) work with the configuration cache.
        BuildResult result = runAndFail("preprocessJava", "--configuration-cache");
        assertTrue(result.getOutput().contains("DirectiveHandler"),
                "the configuration cache must report the unserializable handler:\n" + result.getOutput());
    }

    @Test
    void ktsDemoBuildCacheRoundTrip() throws IOException {
        run("preprocessJava", "--build-cache");
        BuildResult cached = run("clean", "preprocessJava", "--build-cache");
        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":preprocessJava").getOutcome(),
                "the cached task must be restored from the build cache:\n" + cached.getOutput());
    }
}
