# Preprocessor Demo (Kotlin DSL)

A complete, runnable showcase project for the `dev.oblivruin.preprocessor` Gradle plugin written in
the Kotlin DSL. It is the mirror of the Groovy DSL project in [`../demo`](../demo/README.md) and
demonstrates the same feature set — this file only notes the Kotlin specific parts.

## Kotlin DSL specifics

- Handlers are declared as `object : DirectiveHandler, Serializable` / `object : TextProcessor,
  Serializable` instead of lambdas: Kotlin DSL lambdas are **not** `java.io.Serializable`, so a
  build with the configuration cache fails to store the task state. Note that script defined
  handler *classes* (Kotlin object expressions, Groovy classes declared in a build script) cannot
  be stored in the configuration cache either - Gradle refuses to serialize classes defined in a
  build script. For a configuration cache friendly setup, register custom logic from a Groovy
  script (closures work) or define the handler classes in buildSrc / a plugin.
- The `//$$` eval marker must be written as `"//\$\$"` inside Kotlin strings (Kotlin uses `$` for
  string templates).
- The DSL helpers accept Kotlin lambdas directly: `javaTask { vars.put("JAVA_ONLY", "1") }`,
  `include("demo/**")`, `attachTo("compileTestJava")`.
- Per source set configuration uses the typed `sourceSet("<name>") { }` helper. The Groovy
  container style `sourceSets { main { } }` does not resolve reliably in `.kts` scripts for an
  injected plugin classpath.

## What it demonstrates

| Feature | Where |
| --- | --- |
| `autoConfigure = false` + explicit `sourceSets` | `build.gradle.kts` |
| Global variables incl. a lazy `Provider` (`CI`) | `build.gradle.kts` |
| Per source set variables, include/exclude, per task variables (`javaTask`/`kotlinTask`/`resourcesTask`) | `build.gradle.kts`, `Platform.java`, `Greeting.kt`, `features.json` |
| Granularity (`onlyJava()`), strict mode, BLANK output policy, selective attachment (`attachTo`) | `build.gradle.kts` (test source set), `src/test/java/demo/DemoTest.java` |
| `//#if` / `//#elseif` / `//#else if` / `//#else` / `//#endif`, version/numeric comparison, `defined`, `//#var` / `//#unset`, `//$$`, `//#error` / `//#warning` in branches | `src/main/java/demo/Platform.java` |
| Custom directive `//#tag` backed by a declared input file (`customLogicInputs`) | `build.gradle.kts`, `logic/tags.txt` |
| Custom text processors with ordering anchors (`registerBefore`/`registerAfter`) | `build.gradle.kts` (TODO stripping + demo header) |
| Keyword override for a file suffix (mixin style `//@if` in json) | `build.gradle.kts`, `src/main/resources/demo/features.json` |
| Remapper (literal string replacement) | `build.gradle.kts`, `Remapped.java`, `demo/legacy/OldApi.java`, `demo/api/NewApi.java` |
| Verbatim copy of excluded files (`exclude "demo/legacy/**"`) | `src/main/java/demo/legacy/OldApi.java` |
| Kotlin wiring without a compile time dependency on the Kotlin plugin | `src/main/kotlin/demo/Greeting.kt` |

## Running it with the test suite

`DemoKtsProjectFunctionalTest` copies this project to a temporary directory and runs real Gradle
builds against it: a full `build` (javac + kotlinc compile the processed output, jar packaging), a
build cache round trip and a configuration cache test that locks in the script-defined-handler
limitation described above. Run from the repository root:

```bat
gradlew.bat test
```

## Running it standalone

```bat
gradlew.bat publishToMavenLocal     (repository root)

// add the version in demo-kts/build.gradle.kts:
//     id("dev.oblivruin.preprocessor") version "1.0.0"

gradlew.bat -p demo-kts build
gradlew.bat -p demo-kts build -Ppreprocessor.var.PLATFORM=forge
```

The processed output lands in `demo-kts/build/preprocessed/main/<language>/`; a regular build never
touches the original sources.
