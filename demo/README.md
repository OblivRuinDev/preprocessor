# Preprocessor Demo

A complete, runnable showcase project for the `dev.oblivruin.preprocessor` Gradle plugin. It
demonstrates every major feature on real Java and Kotlin sources and doubles as the fixture for the
plugin's functional tests (`DemoProjectFunctionalTest`).

## What it demonstrates

| Feature | Where |
| --- | --- |
| `//#if` / `//#elseif` / `//#else` / `//#endif`, version aware comparison, nesting, `//$$` marker | `src/main/java/demo/Platform.java` |
| Custom directive `//#tag <name>` backed by a declared input file | `build.gradle` + `logic/tags.txt` |
| Declared custom logic inputs (`customLogicInputs`) — changing `logic/tags.txt` reruns the tasks | `build.gradle` |
| Per task variables (`javaTask { }` / `kotlinTask { }`) | `build.gradle`, `Platform.java`, `Greeting.kt` |
| Keyword override for a file suffix (mixin style `//@if` in json) | `build.gradle`, `src/main/resources/demo/features.json` |
| Remapper (literal string replacement) | `build.gradle`, `Remapped.java`, `demo/legacy/OldApi.java`, `demo/api/NewApi.java` |
| Kotlin wiring without a compile time dependency on the Kotlin plugin | `src/main/kotlin/demo/Greeting.kt` |

With the default variables (`PLATFORM=fabric`, `MC=1.20.1`), the processed `Platform.java` keeps the
fabric / modern branches live and comments the rest with `//$$`; line numbers are preserved.

## Running it with the test suite

The test suite copies this project to a temporary directory and runs real Gradle builds against it
(`DemoProjectFunctionalTest`), including a full `build` (javac + kotlinc compile the processed
output), up-to-date checks, command line overrides and a configuration cache round trip. Run from
the repository root:

```bat
gradlew.bat test
```

## Running it standalone

The plugin id is versionless in `build.gradle` because the test suite injects the plugin classpath.
For standalone use:

```bat
gradlew.bat publishToMavenLocal     (repository root)

// add the version in demo/build.gradle:
//     id 'dev.oblivruin.preprocessor' version '1.0.0'

gradlew.bat -p demo build
gradlew.bat -p demo build -Ppreprocessor.var.PLATFORM=forge
gradlew.bat -p demo clean
```

The processed output lands in `demo/build/preprocessed/main/<language>/`; a regular build never
touches the original sources. `gradlew -p demo applyPreprocessJava` (experimental) rewrites the
sources in place instead.
