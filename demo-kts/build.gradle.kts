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

// three additional source sets, created and compiled like any other:
//   samples     - kotlin only, detached (see below)
//   config      - resources only, attached to "check" via attachTo
//   integration - declared but disabled for the preprocessor (enabled = false)
sourceSets {
    create("samples")
    create("config")
    create("integration")
}

preprocessor {
    // only the source sets declared below are preprocessed
    autoConfigure = false

    // opt in to build caching although custom logic is registered below; after changing
    // the logic itself, force reprocessing by bumping a variable:
    //   gradlew -p demo-kts build -Ppreprocessor.var.logicRev=2
    cacheWithCustomLogic = true

    // ---- global defaults, inherited by every source set unless overridden below ----

    // strict mode: an unknown variable in a condition aborts instead of warning
    // (the samples source set overrides this back to lenient)
    strict = true

    // per file problems (binary file, malformed encoding, missing directory) fail the
    // build instead of being reported as warnings
    lenient = false

    // the file encoding; UTF-8 is the default, shown here for discoverability. The test
    // source set overrides it with ISO-8859-1 to demonstrate a real per source set change
    encoding = "UTF-8"

    // whether the preprocess output is attached to the consumer tasks at all; false
    // would leave every preprocess* task unwired (the samples source set does this
    // per source set with detach())
    attach = true

    // restrict the consumer wiring globally: only the listed task names are attached.
    // This is the standard set, so behavior is unchanged; per source set attachTo
    // values override this list entirely
    attachTo = listOf("compileJava", "compileKotlin", "processResources", "compileTestJava")

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
        // the kotlin tasks see KOTLIN_ONLY, the resources tasks see RES_ONLY.
        // Every task knob is settable here (vars, remapper, keywords, includes,
        // excludes, encoding, strict, inactiveLines, lenient)
        javaTask { vars.put("JAVA_ONLY", "1") }
        kotlinTask { vars.put("KOTLIN_ONLY", "1") }
        resourcesTask { vars.put("RES_ONLY", "1") }
    }
    sourceSet("test") {
        // granularity: only the java sources of the test source set
        onlyJava()
        // per source set overrides: the BLANK output policy (directive and inactive
        // lines become empty lines) and an ISO-8859-1 encoded source file
        inactiveLines = LinePolicy.BLANK
        encoding = "ISO-8859-1"
        // attach the processed test sources only to compileTestJava; other consumers
        // (processTestResources) keep reading the original files
        attachTo("compileTestJava")
        vars.put("TEST_FLAG", "1")
    }
    sourceSet("samples") {
        // granularity: only the kotlin sources
        onlyKotlin()
        // per source set override of the global strict mode: the unknown variable
        // below only produces a warning and a dead branch instead of failing
        strict = false
        // register the preprocess tasks but wire them to nothing; the processed
        // output in build/preprocessed/samples/... is not consumed by any task
        detach()
        vars.put("SAMPLES", "1")
    }
    sourceSet("config") {
        // granularity: only the resources
        onlyResources()
        // per source set keyword override: txt files use //@ directives here,
        // while json files (global override) use them everywhere else
        keywords.put("txt", Keywords("//@if", "//@elseif", "//@else", "//@endif", "//\$\$"))
        // the clean REMOVE policy: directive and inactive lines are deleted, the
        // processed resource is a plain properties file
        inactiveLines = LinePolicy.REMOVE
        // attaching to a non compile task wires only a task dependency, so the
        // preprocess output exists whenever "check" runs
        attachTo("check")
        vars.put("CONFIG_ONLY", "1")
    }
    sourceSet("integration") {
        // declared but not preprocessed at all: no preprocessIntegrationJava task
        // exists and nothing is attached; compileIntegrationJava reads this
        // original file verbatim
        enabled = false
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

// The detached samples and the disabled integration source sets are not part of the
// default compile graph (nothing consumes them); wire their compilation in so the demo
// build proves their ORIGINAL sources compile as is.
tasks.named("build") { dependsOn("compileSamplesKotlin", "compileIntegrationJava") }

// the ISO-8859-1 test sources must be compiled with the matching encoding
tasks.compileTestJava { options.encoding = "ISO-8859-1" }
