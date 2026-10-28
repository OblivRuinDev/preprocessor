# Preprocessor Demo (Groovy DSL)

A complete, runnable showcase project for the `dev.oblivruin.preprocessor` Gradle plugin. The
`build.gradle` exercises the full supported configuration surface and doubles as the fixture for
the plugin's functional tests (`DemoProjectFunctionalTest`). The Kotlin DSL mirror of this project
lives in [`../demo-kts`](../demo-kts/README.md).

## What it demonstrates

### Global options (`preprocessor { ... }`)

| Feature | Notes |
| --- | --- |
| `autoConfigure = false` | only the declared source sets are preprocessed |
| `cacheWithCustomLogic = true` | build caching despite registered custom logic |
| `strict = true` | unknown variables in conditions abort instead of warning |
| `lenient = false` | per file problems (binary file, malformed encoding, missing directory) fail instead of warning |
| `encoding = 'UTF-8'` | global default, overridden per source set for the test sources |
| `attach = true` | whether preprocess output is attached to the consumer tasks at all |
| `attachTo = [...]` | global allowlist of consumer task names (per source set values override it) |
| `vars` incl. a lazy `Provider` (`CI`) | overridable from the command line: `-Ppreprocessor.var.PLATFORM=forge` |
| `keywords` (global, json) | mixin config style `//@if` directives in json files |
| `remapper` | literal string replacement on every processed line |
| `customLogicInputs` + `directives.register` | custom `//#tag` directive backed by `logic/tags.txt` |
| `processors.registerBefore` / `registerAfter` | Groovy closure processors with ordering anchors (TODO stripping + idempotent demo header) |

### Per source set configuration (`sourceSets { ... }`)

| Source set | Demonstrates |
| --- | --- |
| `main` | `include` / `exclude` (the excluded `demo/legacy/**` is copied verbatim), per source set variables, per task variables (`javaTask { }` / `kotlinTask { }` / `resourcesTask { }`) |
| `test` | `onlyJava()`, BLANK output policy, per source set `encoding = 'ISO-8859-1'` (the source file really is ISO-8859-1 encoded, and `compileTestJava` uses the matching `-encoding`), selective `attachTo 'compileTestJava'` |
| `samples` | `onlyKotlin()`, per source set `strict = false` override (an unknown variable only warns), `detach()` (tasks registered, output produced, nothing consumes it) |
| `config` | `onlyResources()`, per source set `keywords` override (`//@` directives in txt), REMOVE output policy (directive and inactive lines deleted, plain properties file remains), `attachTo 'check'` — attaching to a non compile task wires only a task dependency |
| `integration` | `enabled = false` — declared but not preprocessed at all; the original file is compiled verbatim |

### Source file level (`src/main/java/demo/Platform.java` and friends)

`//#if` / `//#elseif` / `//#else if` / `//#else` / `//#endif`, nested blocks, version and numeric
comparison, `defined`, `//#var` / `//#unset`, the `//$$` activation marker, `//#error` (inert in a
dead branch), `//#warning` (surfaces in the build log), the custom `//#tag` directive, the
remapper effect (`Remapped.java`) and dead branch safety.

### Tasks

`preprocess<SourceSet><Language>` / `preprocessAll` / `applyPreprocess<SourceSet><Language>` /
`applyPreprocess` — see the `demoPreprocessAllAndApplyPreprocess` test: `preprocessAll` runs every
preprocess task (including the detached source set), `applyPreprocess` rewrites the originals in
place, and a full build on top of the applied sources stays green because reprocessing is
idempotent (the demo header processor guard).

## Running it with the test suite

The test suite copies this project to a temporary directory and runs real Gradle builds against it
(`DemoProjectFunctionalTest`): a full `build` (javac + kotlinc compile the processed output, jar
packaging), `preprocessAll` + `applyPreprocess` + rebuild, up-to-date checks, command line
overrides, a build cache round trip and a configuration cache round trip. Run from the repository
root:

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
gradlew.bat -p demo preprocessAll
gradlew.bat -p demo applyPreprocess
gradlew.bat -p demo clean
```

The processed output lands in `demo/build/preprocessed/<sourceSet>/<language>/`; a regular build
never touches the original sources. `gradlew -p demo applyPreprocess` (experimental) rewrites the
sources in place instead.
