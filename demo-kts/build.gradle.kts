// Showcase project for the dev.oblivruin.preprocessor plugin (Kotlin DSL).
//
// The plugin is applied without a version because this project doubles as the
// functional test fixture (the test suite injects the plugin classpath). To use
// this project standalone:
//   1. gradlew publishToMavenLocal          (from the repository root)
//   2. add the version below, e.g. id("dev.oblivruin.preprocessor") version "1.0.0"
//   3. run: gradlew -p demo-kts build
//
// The Groovy DSL mirror of this project lives in ../demo.

import dev.oblivruin.preprocessor.Keywords
import dev.oblivruin.preprocessor.LinePolicy
import dev.oblivruin.preprocessor.api.DirectiveContext
import dev.oblivruin.preprocessor.api.DirectiveHandler
import dev.oblivruin.preprocessor.api.ProcessorContext
import dev.oblivruin.preprocessor.api.TextProcessor
import java.io.File
import java.io.Serializable

plugins {
    java
    kotlin("jvm") version "1.9.24"
    id("dev.oblivruin.preprocessor")
}

repositories {
    mavenCentral()
}

preprocessor {
    // only the source sets declared below are preprocessed
    autoConfigure = false

    // opt in to build caching although custom logic is registered below; after changing
    // the logic itself, force reprocessing by bumping a variable:
    //   gradlew -p demo-kts build -Ppreprocessor.var.logicRev=2
    cacheWithCustomLogic = true

    // global variables, overridable from the command line:
    //   gradlew -p demo-kts build -Ppreprocessor.var.PLATFORM=forge
    vars.put("MC", "1.20.1")
    vars.put("PLATFORM", "fabric")
    // dynamic values: Providers are resolved lazily and take part in the fingerprint
    vars.put("CI", providers.provider { System.getenv("CI") ?: "off" })

    // Kotlin DSL note: the NamedDomainObjectContainer style `sourceSets { main { } }`
    // does not resolve reliably in .kts scripts for an injected plugin classpath - use
    // the typed `sourceSet("<name>") { }` helper instead (identical semantics).
    sourceSet("main") {
        // only files below demo/ are preprocessed; demo/legacy/** is copied verbatim
        include("demo/**")
        exclude("demo/legacy/**")

        // per source set variables, visible to main only
        vars.put("EXPERIMENTAL", "0")

        // per task variables: preprocessJava / applyPreprocessJava see JAVA_ONLY,
        // the kotlin tasks see KOTLIN_ONLY, the resources tasks see RES_ONLY
        javaTask { vars.put("JAVA_ONLY", "1") }
        kotlinTask { vars.put("KOTLIN_ONLY", "1") }
        resourcesTask { vars.put("RES_ONLY", "1") }
    }
    sourceSet("test") {
        // granularity: only the java sources of the test source set
        onlyJava()
        // per source set overrides: strict mode and the BLANK output policy
        strict = true
        inactiveLines = LinePolicy.BLANK
        // attach the processed test sources only to compileTestJava; other consumers
        // (processTestResources) keep reading the original files
        attachTo("compileTestJava")
        vars.put("TEST_FLAG", "1")
    }

    // mixin config style keyword override: json files use //@ directives
    // ($ is escaped because Kotlin strings use it for templates)
    keywords.put("json", Keywords("//@if", "//@elseif", "//@else", "//@endif", "//\$\$"))

    // literal string replacement on every processed line (ToCraft compatible)
    remapper.put("demo.legacy.OldApi", "demo.api.NewApi")

    // files the custom directive reads; they join the up-to-date fingerprint and the
    // build cache key, so changing tags.txt reruns the tasks
    customLogicInputs.from("logic/tags.txt")

    // Handlers are declared as Serializable object expressions rather than lambdas.
    // Note: handler classes declared INSIDE a build script (Kotlin object expressions,
    // Groovy classes) cannot be stored in the configuration cache at all, because Gradle
    // refuses to serialize script defined classes - the Serializable marker only helps
    // for classes from a stable classpath (e.g. buildSrc). Groovy closures work because
    // Gradle serializes them specially (see the Groovy DSL demo).
    val tagsPath = project.file("logic/tags.txt").absolutePath
    directives.register("tag", object : DirectiveHandler, Serializable {
        override fun handle(ctx: DirectiveContext) {
            ctx.replaceLine("// tag[" + ctx.argument() + "] = " + File(tagsPath).readText().trim())
        }
    })

    // custom text processors with ordering anchors: strip TODO lines before the built-in
    // remapper, add the demo header after them (idempotent thanks to the guard)
    processors.registerBefore("stripTodo", "remapper", object : TextProcessor, Serializable {
        override fun process(ctx: ProcessorContext) {
            ctx.lines().removeIf { it.trim().startsWith("// TODO") }
        }
    })
    processors.registerAfter("demoHeader", "stripTodo", object : TextProcessor, Serializable {
        override fun process(ctx: ProcessorContext) {
            val lines = ctx.lines()
            if (lines.isNotEmpty() && !lines[0].startsWith("// preprocessed")) {
                lines.add(0, "// preprocessed with the preprocessor demo")
            }
        }
    })
}
