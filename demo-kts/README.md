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

The `build.gradle.kts` exercises the same full configuration surface as the Groovy mirror — see
[the Groovy demo README](../demo/README.md) for the complete feature table (global
`strict`/`lenient`/`encoding`/`attach`/`attachTo`, include/exclude, per source set and per task
variables, all granularity helpers, all three output policies, per source set keyword overrides,
`enabled = false`, `detach()`, selective attachment, `applyPreprocess`, ...). The source files and
the three extra source sets (`samples`, `config`, `integration`) are identical to the Groovy demo;
only the build script syntax differs.

## Running it with the test suite

`DemoKtsProjectFunctionalTest` copies this project to a temporary directory and runs real Gradle
builds against it: a full `build` (javac + kotlinc compile the processed output, jar packaging)
followed by `applyPreprocessJava`, a build cache round trip and a configuration cache test that
locks in the script-defined-handler limitation described above. Run from the repository root:

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
gradlew.bat -p demo-kts applyPreprocess
```

The processed output lands in `demo-kts/build/preprocessed/<sourceSet>/<language>/`; a regular build
never touches the original sources.
