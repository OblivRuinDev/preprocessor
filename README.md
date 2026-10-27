# PreProcessor Gradle Plugin

[![License](https://img.shields.io/badge/license-Apache_2-blue.svg?colorB=red)](https://github.com/OblivRuinDev/preprocessor/blob/master/LICENSE)

A source preprocessing plugin for Gradle, written entirely in Java (plugin ID: `dev.oblivruin.preprocessor`).
It is based on the ideas of [ReplayMod/PreProcessor](https://github.com/ReplayMod/preprocessor) and
[ToCraft/PreProcessor](https://github.com/ToCraft/PreProcessor), but adds **registerable custom
preprocessing logic**, **fine-grained variable control**, and **exact per source set / per language
processing scopes**.

## Contents

- [Feature Overview](#feature-overview)
- [Relation to the ToCraft PreProcessor](#relation-to-the-tocraft-preprocessor)
- [Quick Start](#quick-start)
- [Variable System (Fine-Grained Control)](#variable-system-fine-grained-control)
- [Condition Expressions](#condition-expressions)
- [Granularity](#granularity)
- [Task Attachment](#task-attachment)
- [Per-Task Configuration](#per-task-configuration)
- [Build Cache](#build-cache)
- [Configuration Cache](#configuration-cache)
- [Tasks](#tasks)
- [Keywords and File Types](#keywords-and-file-types)
- [Registering Custom Preprocessing Logic](#registering-custom-preprocessing-logic)
- [Output Policy](#output-policy)
- [Remapper (String Replacement, ToCraft Compatible)](#remapper-string-replacement-tocraft-compatible)
- [Other Global Options](#other-global-options)
- [Compatibility Notes](#compatibility-notes)
- [Building & Testing](#building--testing)
- [Project Layout](#project-layout)

## Feature Overview

| Capability              | Description                                                                                                                                                                                    |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Conditional blocks      | `//#if` / `//#elseif` / `//#else if` / `//#else` / `//#endif`, arbitrarily nested                                                                                                              |
| Line activation marker  | Lines marked with `//$$` are activated when their branch is live; inactive branches keep them commented (readable in the IDE, stable line numbers)                                             |
| Expressions             | `&&` `\|\|` `!` with parentheses, numeric comparison, **version aware comparison** (`"1.16" > "1.9"` is true), string comparison, `defined X`                                                  |
| Custom directives       | `preprocessor.directives.register(...)` — closures or Java classes; they can manipulate variables, emit lines, and even open/close conditional blocks                                          |
| Custom processors       | `preprocessor.processors.register(...)` — file level `TextProcessor`s with ordering anchors                                                                                                    |
| Declared logic inputs   | `customLogicInputs.from(...)`: files read by custom handlers become part of the up-to-date fingerprint and the build cache key                                                                  |
| Variable scopes         | Command line `-P` override → per source set → global → in-file `//#var`; dynamic variables via `Provider`                                                                                      |
| Granularity             | Per source set switch (`enabled`), per language switches (`java` / `kotlin` / `resources`), per file include/exclude patterns                                                                  |
| Keyword overrides       | Replace the whole keyword set per file suffix (e.g. `mixins.json` with `//@if`)                                                                                                                |
| Build integration       | Output goes to `build/preprocessed/...`; `compileJava` / `compileKotlin` / `processResources` automatically consume the processed files; **regular builds never modify your original sources** |
| Task attachment control | `attach` / `attachTo`: wire nothing, or only the consumer tasks you name (custom tasks get a dependency automatically)                                                                         |
| Per-task configuration  | `javaTask { }` / `kotlinTask { }` / `resourcesTask { }`: different variables and settings per task                                                                                             |
| Build cache             | Cacheable tasks; fully cacheable without custom logic, cache disabled for tasks with custom logic unless opted in                                                                              |
| Configuration cache     | Supported and tested (Groovy closure handlers work; Kotlin DSL lambdas need a `Serializable` handler class)                                                                                    |
| Incremental & cacheable | Tasks are cacheable (`@CacheableTask`) and participate in up-to-date checks; changed inputs or variables retrigger processing                                                                  |
| Compatibility           | No compile time dependency on the Kotlin plugin (reflection based); BOM, CRLF and binary files are detected and preserved                                                                      |

## Relation to the ToCraft PreProcessor

Built-in directives, the `//$$` marker, suffix based `Keywords`, the in-place `applyPreprocess` tasks
and the `remapper` string replacement behave compatible with ToCraft/ReplayMod (migrating an existing
project usually only means switching the plugin id and extension name). On top of that:

- Expressions are no longer limited to integer comparison: full boolean syntax with parentheses,
  version aware ordering, strings and `defined`.
- Source sets are not force-processed in full: everything is on by default but every piece can be
  switched off individually.
- Preprocessed results are written to the build directory instead of rewriting the sources in place
  (in-place rewriting remains available as the separate experimental `applyPreprocess` task).
- No compile time dependency on the Kotlin plugin; the Kotlin compile task is wired through
  `AbstractCompile` with a reflection fallback.

## Quick Start

A complete, runnable example lives in the [`demo`](demo/README.md) directory (java + kotlin sources,
custom directives, keyword overrides, remapper, per task variables); it doubles as the functional
test fixture of the plugin.

### Groovy DSL

```groovy
plugins {
    id 'java'                       // or 'org.jetbrains.kotlin.jvm'
    id 'dev.oblivruin.preprocessor'
}

preprocessor {
    // global variables (values may also be Providers, e.g. providers.provider { System.getenv('CI') })
    vars.put('MC', '1.20.1')
    vars.put('FABRIC', '1')
}
```

### Kotlin DSL

```kotlin
plugins {
    java // or kotlin("jvm")
    id("dev.oblivruin.preprocessor")
}

preprocessor {
    vars.put("MC", "1.20.1")
    vars.put("FABRIC", "1")
}
```

### Using directives in sources

```java
package demo;

public class Hello {
    public static String platform() {
        //#if FABRIC
        return "fabric";
        //#elseif FORGE
        return "forge";
        //#else
        return "unknown";
        //#endif
    }

    public static String mcVersion() {
        //#if MC >= "1.17"
        return "modern";
        //#else
        return "legacy";
        //#endif
    }
}
```

With `MC = 1.20.1`, `return "modern";` stays live and the other branch is commented out as
`//$$ return "legacy";`. Line numbers are preserved, which keeps debugging and diffs sane.

## Variable System (Fine-Grained Control)

Resolution order (highest first):

1. **Command line property**: `gradlew build -Ppreprocessor.var.<NAME>=<value>`
2. **Per source set variables**: `preprocessor.sourceSets { main { vars.put(...) } }`
3. **Global variables**: `preprocessor { vars.put(...) }`
4. **In-file variables**: `//#var NAME = <expression>` (undo with `//#unset NAME`)

```groovy
preprocessor {
    vars.put('MC', '1.20.1')                                  // global
    sourceSets {
        main {
            vars.put('PLATFORM', 'fabric')                    // visible to main only
        }
        test {
            vars.put('PLATFORM', 'unittest')
        }
    }
}
```

Variable values may be arbitrary objects or `Provider`s (e.g.
`providers.provider { System.getenv('CI') }`, `providers.environmentVariable('X')`); providers are
resolved lazily and participate in the up-to-date fingerprint, so changing them reruns the task.

### In-File Assignment

```
//#var MINOR = 5
//#if MINOR == 5
...
//#endif
```

The right hand side accepts the full condition syntax: `//#var flag = A && B`, `//#var name = "1.16"`.
Assignments only affect the lines below them **in the same file** and never leak into other files.

## Condition Expressions

```
//#if a                          // variable exists and is truthy ("0"/"false"/""/"null"/"no"/"off" are falsy)
//#if a == 1 && b != 2           // numeric comparison
//#if MC >= "1.17"               // version aware comparison ("1.16" > "1.9" is true)
//#if PLATFORM == "fabric" || PLATFORM == "quilt"
//#if !LEGACY && (A || B)        // not / and / or / parentheses
//#if defined EXPERIMENTAL       // whether a variable is defined (even if its value is "0")
```

Unknown variables evaluate to `false` with a warning by default; set `preprocessor.strict = true` to
turn that into a build error. **Conditions inside dead branches are never evaluated**, so even strict
mode can safely reference variables that only exist for other versions.

## Granularity

```groovy
preprocessor {
    // true (default) = process every source set with defaults; false = only the declared ones below
    autoConfigure = false

    sourceSets {
        main {
            onlyJava()                     // process only the java sources of main
            // or individually: java = true; kotlin = false; resources = false
            include '**/platform/**'       // only matching files are preprocessed, others are copied verbatim
            exclude '**/legacy/**'         // matching files are copied verbatim
            vars.put('MAIN_ONLY', '1')
        }
        test {
            enabled = false                // skip the test source set entirely
        }
    }
}
```

Every enabled entry registers `preprocess<SourceSet><Language>` tasks (e.g. `preprocessJava`,
`preprocessTestKotlin`, `preprocessTestResources`, named like Gradle's native tasks) plus the optional
`applyPreprocess<SourceSet><Language>` tasks.

## Task Attachment

By default the preprocessed output is attached automatically to the standard consumer tasks of the
enabled languages (`compileJava`, `compileKotlin`, `processResources`). Two settings control exactly
what gets attached; both exist globally and per source set (per source set wins):

| Setting | Default | Meaning |
| --- | --- | --- |
| `attach` | `true` | Whether consumer tasks are wired to the preprocess output at all |
| `attachTo` | *(empty)* | Explicit allowlist of task names; empty = the standard consumer tasks |

### Attach to selected tasks only

```groovy
preprocessor {
    sourceSets {
        main {
            attachTo 'compileJava'   // only compileJava consumes the preprocessed output;
        }                            // processResources keeps reading the original resources
    }
}
```

- Names are matched by exact task name within the same project (so `compileTestJava`, a task of a
  different plugin, or your own tasks all work). Unknown names fail the configuration immediately.
- A listed task that is a compile task of the source set (e.g. `compileJava`, `compileKotlin`)
  gets its sources redirected to the preprocessed output.
- `processResources` (when listed) gets the preprocessed resources added on top.
- Any other listed task only gets a task dependency on the preprocess tasks (so the output in
  `build/preprocessed/...` exists when it runs) - the task keeps consuming the original files,
  which is exactly what you want for custom generation or copy tasks.

### Manual mode (attach to nothing)

```groovy
preprocessor {
    sourceSets {
        main {
            detach()              // or: attach = false
        }
    }
}
```

The `preprocess*` tasks are still registered and `preprocessAll` still works, but the regular build
(`compileJava`, `processResources`, `jar`, ...) keeps consuming the original sources and never runs
the preprocess tasks. Consume `build/preprocessed/<source set>/<language>/` manually, or wire it
yourself:

```groovy
tasks.processResources {
    from(files('build/preprocessed/main/resources')) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}
```

### Global defaults

```groovy
preprocessor {
    attach = true                       // global default, overridden per source set
    attachTo = ['compileJava', 'processResources']   // global allowlist, overridden per source set
}
```

## Per-Task Configuration

Every `preprocess*` (and its `applyPreprocess*` counterpart) can be configured individually through
the `javaTask` / `kotlinTask` / `resourcesTask` DSL helpers, so different tasks can run with
different variable values. The closure's delegate is the task itself:

```groovy
preprocessor {
    vars.put('SHARED', '1')             // visible to every task
    sourceSets {
        main {
            javaTask {
                vars.put('MODE', 'java')          // only for preprocessJava / applyPreprocessJava
                // remapper.put(...); keywords.put(...); includes.add(...)
                // strict.set(true); encoding.set('ISO-8859-1')
            }
            resourcesTask { vars.put('MODE', 'res') }
            // kotlinTask { ... }
        }
    }
}
```

Kotlin DSL (SAM conversion):

```kotlin
preprocessor {
    sourceSets {
        main {
            javaTask {
                vars.put("MODE", "java")
            }
        }
    }
}
```

Semantics:

- Accumulative options (`vars`, `remapper`, `keywords`, `includes`, `excludes`) are **merged per
  key**: a task level entry wins over the base value of the same key, all other keys stay
  inherited.
- Scalar options (`strict`, `encoding`, `inactiveLines`, `lenient`) are overridden as a whole when
  set on the task.
- Effective resolution order: **task &gt; command line (`-Ppreprocessor.var.NAME`) &gt; source set
  &gt; global &gt; in-file `//#var`**.
- The same actions are applied to the matching `applyPreprocess*` task, so an in-place apply always
  produces the same result the build consumes.
- Granularity note: one preprocess task = one output. Different values per task mean per (source
  set, language) pairs - the natural task boundaries of this plugin.

## Build Cache

The `preprocess*` tasks are `@CacheableTask` and are served from the build cache like any other
cacheable task:

- **Without custom logic** (no registered directives, no registered processors) the tasks are fully
  cacheable: identical inputs (source files, variables, keywords, patterns, engine version) restore
  the output from the cache (`FROM-CACHE`).
- **With custom logic** (directives or processors) caching is **disabled for those tasks by
  default** (`doNotCacheIf`): the handlers are live objects that cannot be part of a cache key, so
  a changed handler would silently keep producing cached results. Up-to-date checks still apply
  locally. Set `cacheWithCustomLogic = true` to allow caching anyway if you know the logic is
  stable.

```groovy
preprocessor {
    cacheWithCustomLogic = true   // opt in to caching despite custom directives/processors
}
```

Caveat for custom logic (applies to up-to-date checks in every case): the fingerprint records the
*names* of the registered directives/processors, not their bodies. After changing a handler's
logic, force reprocessing by bumping a variable, e.g. `gradlew build -Ppreprocessor.var.logicRev=2`
(variables are part of the fingerprint).

### Declared inputs of custom logic

Custom handlers often read external files (replacement tables, mappings, ...). Such files are not
source files, so Gradle cannot know about them — declare them and they reliably participate in
up-to-date checks *and* build cache keys of every `preprocess*` / `applyPreprocess*` task:

```groovy
preprocessor {
    customLogicInputs.from('config/replacements.txt')

    // read the file at execution time; capture only the path (a String) so the
    // closure stays Serializable and configuration cache friendly
    def tablePath = project.file('config/replacements.txt').absolutePath
    directives.register('map') { ctx ->
        def line = new File(tablePath).readLines().findResult {
            def parts = it.split('=', 2)
            parts.size() == 2 && parts[0] == ctx.argument() ? parts[1] : null
        }
        if (line != null) ctx.replaceLine('// ' + ctx.argument() + ' -> ' + line)
    }
}
```

Changing `config/replacements.txt` reruns the tasks and produces a different cache key. The
*logic* of a handler still is not part of the key — after changing the handler itself, bump a
variable (see above) or opt in via `cacheWithCustomLogic` knowingly.

## Configuration Cache

The plugin is compatible with the
[configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html) and tested
against it (`preprocess*` / `compile*` wiring round trips store and reuse the entry). Command line
variables (`-Ppreprocessor.var.NAME=...`) are tracked as configuration inputs, so changing them
invalidates the entry automatically.

One limitation to know about: task state is **serialized** into the configuration cache, and that
includes the registered custom logic handlers. The deciding property is plain
`java.io.Serializable` — not a mutable/immutable classification:

- Groovy closures (`directives.register('x') { ... }` in a Groovy build script) are `Serializable`
  and work with the configuration cache. The plugin wraps them in a `Serializable` adapter, so
  closure registered directives are always configuration cache friendly. Capture only serializable
  values in the closure (a `String` path instead of a `Project`/`File` reference, see the example
  above).
- Kotlin DSL SAM lambdas (`directives.register("x") { ... }` in a `.kts` script) are **not**
  `Serializable`: storing the configuration cache fails with an error naming
  `dev.oblivruin.preprocessor.api.DirectiveHandler`. Workarounds: use a Groovy build script for the
  directive registrations, or implement the handler as a `Serializable` class (e.g. in buildSrc):
  `class MyDirective : DirectiveHandler, Serializable`.
- The same applies to `processors.register(...)` with `TextProcessor`.

When the configuration cache is enabled, the plugin warns during configuration about handlers that
are not `Serializable` (naming them and the fix) — *before* Gradle fails with its cryptic
serialization error. Without the configuration cache, serializability has no effect and no warning
is emitted.

Note that serializability only decides the **configuration** cache. A `Serializable` handler is not
automatically safe for the **build** cache: the handler logic is still not part of the build cache
key (see Build Cache).

## Tasks

| Task                                   | Description                                                                                                                                               |
|----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `preprocess<SourceSet><Language>`      | Preprocesses into `build/preprocessed/<source set>/<language>/`; the standard consumer tasks depend on it (see Task Attachment for `attach` / `attachTo`) |
| `preprocessAll`                        | Aggregates all `preprocess*` tasks of this project                                                                                                        |
| `applyPreprocess<SourceSet><Language>` | **Experimental**: writes the preprocessed result back to the original sources (inactive lines commented as `//$$`) so the IDE shows what the build sees   |
| `applyPreprocess`                      | Aggregates all `applyPreprocess*` tasks (never part of the regular build graph, run manually)                                                             |

`applyPreprocess` rewrites are idempotent; keep your sources under version control. Regular builds
(`build` / `assemble`) never touch your source files.

## Keywords and File Types

```groovy
import dev.oblivruin.preprocessor.Keywords   // in Kotlin DSL / buildSrc

preprocessor {
    keywords.put('json', new Keywords('//@if', '//@elseif', '//@else', '//@endif', '//$$'))
    // full form with an explicit prefix:
    // new Keywords('//@', 'if', 'elseif', 'else', 'endif', '//$$')
}
```

Keys are matched against the end of the file name at a dot boundary (`json` matches
`mixins.json` and a file literally named `json`, but not `foojson`); the longest matching suffix
wins. Files without a match use the default keywords (`//#if` family + `//$$`). Source set level
`keywords` override the global map.

## Registering Custom Preprocessing Logic

### Custom Directives

Custom directives use the same prefix as the built-in ones: `//#<name> [argument]`. Handlers produce
output only inside **active** branches, but they are invoked in dead branches too (so they can
participate in block tracking); `context.active()` tells them where they are.

Groovy closure:

```groovy
preprocessor {
    directives.register('say') { ctx ->
        ctx.replaceLine('// [say] ' + ctx.argument())   // //#say hello -> '// [say] hello'
    }

    // a custom paired block directive
    directives.register('beginCustom') { ctx -> ctx.blocks().beginBranch(false) }
    directives.register('endCustom')   { ctx -> ctx.blocks().endBranch() }
}
```

Kotlin DSL (SAM conversion):

```kotlin
preprocessor {
    directives.register("say") { ctx ->
        ctx.replaceLine("// [say] " + ctx.argument())
    }
}
```

Java (from buildSrc or another plugin):

```java
public class VersionDirective implements DirectiveHandler {
    @Override
    public void handle(DirectiveContext ctx) {
        String var = ctx.argument();                 // //#versionOf MC
        ctx.replaceLine("// " + var + " = " + ctx.variables().get(var));
    }
}
// registration:
preprocessor.getDirectives().register("versionOf", new VersionDirective());
```

`DirectiveContext` capabilities:

- `argument()` / `directive()` / `fileName()` / `lineNumber()` / `active()` / `currentlyActive()`
- `variables()`: read and write variables (writes go to the file-local scope)
- `emit(line)`: append a line to the output (dropped automatically in dead branches)
- `replaceLine(text)` / `dropLine()`: rewrite or remove the directive line itself
- `blocks()`: `beginBranch(cond)` / `nextBranch(cond)` / `nextBranch()` / `endBranch()` for custom
  `if/endif` style block directives
- `meta()`: per-file scratch data shared with text processors
- `warn(msg)` / `fail(msg)`: warn, or fail with file and line information

Note: `if`, `elseif`, `else`, `endif`, `var`, `assign`, `unset`, `error`, `warning` are reserved
built-ins; directive names are case-insensitive. By default the directive line itself is kept in the
output according to the line policy (this keeps `applyPreprocess` idempotent); call `dropLine()` when
you do not want it.

### Custom Text Processors (File Level)

```java
public class StripTodoProcessor implements TextProcessor {
    @Override
    public void process(ProcessorContext ctx) {
        ctx.lines().removeIf(line -> line.trim().startsWith("// TODO"));
    }
}
```

```groovy
preprocessor {
    processors.register('stripTodo', new StripTodoProcessor())
    processors.registerBefore('early', 'remapper', new MyEarlyProcessor())  // ordering anchors
}
```

Execution order: directive scan → built-in `remapper` string replacement → custom processors
(ordered by registration and anchors).

## Output Policy

```groovy
preprocessor {
    inactiveLines = dev.oblivruin.preprocessor.LinePolicy.COMMENT   // default: comment out inactive lines, stable line numbers
    // BLANK : directive and inactive lines become empty lines (stable line numbers, clean output)
    // REMOVE: remove them entirely (cleanest output, line numbers shift)
}
```

ToCraft compatible detail: inactive lines that are *less* indented than their `//#if` line are left
untouched (neither commented nor activated), because they are usually closing braces or annotations
of the enclosing context rather than branch content.

## Remapper (String Replacement, ToCraft Compatible)

```groovy
preprocessor {
    remapper.put('com.example.legacy.OldUtil', 'com.example.new.NewUtil')
}
```

Literal replacement on every processed line, applied in insertion order.

## Other Global Options

| Option | Default | Description |
| --- | --- | --- |
| `strict` | `false` | Unknown variables in conditions fail the build instead of warning |
| `inactiveLines` | `COMMENT` | See output policy above |
| `encoding` | `UTF-8` | Read/write encoding (a UTF-8 BOM is preserved) |
| `autoConfigure` | `true` | Process every source set automatically |
| `attach` | `true` | Global default: attach the preprocess output to the consumer tasks (see Task Attachment) |
| `attachTo` | *(empty)* | Global allowlist of task names to attach to (see Task Attachment) |
| `cacheWithCustomLogic` | `false` | Allow the build cache to store/load preprocess tasks that have custom directives or processors (see Build Cache) |
| `customLogicInputs` | *(empty)* | Files read by custom directives/processors; declared files join the up-to-date fingerprint and cache key (see Build Cache) |
| `lenient` | `true` | Single-file problems (e.g. missing source directories) warn instead of failing |

## Compatibility Notes

- **Bytecode**: the plugin is published as Java 8 bytecode. It can therefore be loaded by Gradle 8.x
  builds on a Java 8+ runtime as well as Gradle 9 builds (Java 17+ runtime). The build is compiled
  against the Gradle 8.8 API.
- **Gradle**: developed and fully tested on Gradle 8.8; the code only uses stable APIs
  (`Property` / `MapProperty` / `TaskProvider`, no removed `convention()` calls).
- **Configuration cache**: supported and tested - see Configuration Cache below.
- **Kotlin**: zero compile time dependency. If the kotlin plugin added its `kotlin` source directory
  set to a source set, a `preprocess<SourceSet>Kotlin` task is created and the `compileKotlin` task
  is redirected via `AbstractCompile` (with a reflection fallback).
- **Binary / encoding**: files containing a NUL byte are treated as binary and copied verbatim (as
  a stream, never buffered completely); a UTF-8 BOM is preserved and mixed CRLF/LF line endings are
  normalized to the file's dominant one. Text that is invalid for the configured encoding (e.g. a
  GBK file read as UTF-8) produces a `malformed input` warning naming the file and line, instead of
  silently corrupting the build input.
- **Resource use**: files are processed one at a time and outputs are written as they are produced
  (constant memory, independent of the source set size); files whose content did not change are not
  rewritten. The remapper uses plain string matching (no per call regex compilation, which matters
  on Java 8 runtimes).
- **Multiple source directories**: directories of one source set are processed in order; duplicate
  relative paths resolve with later-directories-win semantics (same as ToCraft) with a warning.
- **Building this project**: the wrapper is pinned to Gradle 8.8. Because the build script uses the
  `com.gradle.plugin-publish` plugin (2.2.1, which requires a Java 17+ build runtime), building
  needs a JDK between 17 and 22 (Gradle 8.8 cannot run on newer JDKs: its embedded Kotlin cannot
  parse their class files). Select the build JDK with the IDE settings or
  `-Dorg.gradle.java.home=<jdk>`. The produced plugin jar has no such restriction - it runs on any
  Gradle 8.x/9.x build with a JVM the respective Gradle version supports. `options.release = 8` is
  applied automatically when the compiler JDK is 9+.

## Building & Testing

```bat
gradlew.bat build          // compile + all tests + jar
gradlew.bat test           // engine unit tests + TestKit functional tests
gradlew.bat publishToMavenLocal
```

The [`demo`](demo/README.md) directory is a complete, runnable showcase project (java + kotlin,
custom directive backed by a declared input file, json keyword override, remapper, per task
variables). It doubles as the fixture for `DemoProjectFunctionalTest`, which copies it and runs real
Gradle builds against it (full `build` with javac + kotlinc, up-to-date checks, command line
overrides, configuration cache round trip).

Test coverage: 13 expression evaluator tests (precedence, version comparison, strict mode, ...),
41 engine tests (branches incl. the `//#else if` two word form, policies, variables, custom
directives and blocks, idempotency, suffix keyword matching, ...), and 18 TestKit end-to-end tests
(compile wiring, resource wiring, up-to-date checks, granularity, task attachment control, per task
variables, include/exclude DSL, declared custom logic inputs, build cache round trip with and
without custom logic, configuration cache round trips, the Kotlin lambda limitation, malformed
encoding detection and three demo project scenarios) - 72 tests in total.

## Project Layout

```
src/main/java/dev/oblivruin/preprocessor/
├── PreProcessorPlugin.java        plugin entry point (wires source sets in afterEvaluate)
├── PreprocessorExtension.java     the `preprocessor` extension
├── SourceSetSettings.java         per source set configuration
├── Keywords.java / LinePolicy.java
├── DirectiveRegistry.java / ProcessorRegistry.java
├── api/                           interfaces users implement for custom logic
│   ├── DirectiveHandler / DirectiveContext / BlockControl
│   ├── TextProcessor / ProcessorContext
│   └── Variables
├── core/                          the Gradle-free engine
│   ├── Preprocessor.java          line scan + directive dispatch + block stack
│   ├── ExpressionEvaluator.java   condition expressions
│   ├── VariableScope.java / RemapProcessor.java
│   └── ProcessingException.java
├── tasks/                         AbstractPreprocessTask (shared base) +
│                                  PreprocessTask (cacheable) / ApplyPreprocessTask (experimental)
└── internal/                      source set wiring, file utilities

demo/                              runnable showcase project (see demo/README.md);
                                   fixture for DemoProjectFunctionalTest
```
