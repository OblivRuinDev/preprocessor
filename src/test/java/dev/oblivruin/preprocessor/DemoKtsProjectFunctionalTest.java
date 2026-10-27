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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests against the Kotlin DSL demo project ({@code demo-kts}, see its
 * README). The project is copied to a temporary directory, so the checked in sources
 * stay clean. The handlers there are Serializable object expressions, which keeps the
 * project configuration cache friendly (Kotlin DSL lambdas would not be).
 */
class DemoKtsProjectFunctionalTest {

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

    private static String text(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    void ktsDemoBuildProducesProcessedSourcesAndJar() throws IOException {
        BuildResult result = run("build");
        assertEquals(TaskOutcome.SUCCESS, result.task(":build").getOutcome());

        // java: processors, custom directive, live branches and per task variables all
        // work through the Kotlin DSL configuration
        Path platform = new File(demoDir, "build/preprocessed/main/java/demo/Platform.java").toPath();
        String platformText = text(platform);
        assertTrue(platformText.startsWith("// preprocessed with the preprocessor demo"),
                "the demo header processor must prepend a line:\n" + platformText);
        assertFalse(platformText.contains("// TODO"), "stripTodo processor must remove TODO lines");
        assertTrue(platformText.contains("return \"fabric\";"));
        assertTrue(platformText.contains("//$$ return \"forge\";"));
        assertTrue(platformText.contains("// tag[release] = stable"), "custom directive output");
        assertTrue(platformText.contains("return \"java\";"), "JAVA_ONLY from javaTask { }");
        assertTrue(platformText.contains("return \"unset worked\";"), "//#unset + !defined");
        assertTrue(platformText.contains("return \"fabric two word\";"), "//#else if two word form");

        // remapper rewrote the legacy reference; the excluded legacy file is verbatim
        Path remapped = new File(demoDir, "build/preprocessed/main/java/demo/Remapped.java").toPath();
        assertTrue(text(remapped).contains("demo.api.NewApi.name()"), "remapper output");
        Path oldApi = new File(demoDir, "build/preprocessed/main/java/demo/legacy/OldApi.java").toPath();
        assertTrue(text(oldApi).contains("//#tag excluded"), "excluded files stay verbatim");

        // kotlin and resources work through the Kotlin DSL wiring as well
        Path greeting = new File(demoDir, "build/preprocessed/main/kotlin/demo/Greeting.kt").toPath();
        assertTrue(text(greeting).contains("return \"hello from kotlin\""));
        Path features = new File(demoDir, "build/preprocessed/main/resources/demo/features.json").toPath();
        assertTrue(text(features).contains("\"loader\": \"fabric\""));

        // test source set: onlyJava + strict + BLANK policy through the Kotlin DSL
        Path demoTest = new File(demoDir, "build/preprocessed/test/java/demo/DemoTest.java").toPath();
        String testText = text(demoTest);
        assertTrue(testText.contains("return \"test-fabric\";"));
        assertFalse(testText.contains("test-other"), "the BLANK policy removes the inactive body");

        File jar = new File(demoDir, "build/libs/preprocessor-demo-kts.jar");
        assertTrue(jar.isFile(), "the jar must exist:\n" + result.getOutput());
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

    private BuildResult runAndFail(String... arguments) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(demoDir)
                .withArguments(arguments)
                .buildAndFail();
    }

    @Test
    void ktsDemoBuildCacheRoundTrip() throws IOException {
        run("preprocessJava", "--build-cache");
        BuildResult cached = run("clean", "preprocessJava", "--build-cache");
        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":preprocessJava").getOutcome(),
                "the cached task must be restored from the build cache:\n" + cached.getOutput());
    }
}
