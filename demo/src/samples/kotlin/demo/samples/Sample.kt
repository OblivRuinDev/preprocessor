package demo.samples

/**
 * The samples source set is onlyKotlin() and detached (see build.gradle): the
 * preprocessSamplesKotlin task still runs and writes build/preprocessed/samples/...,
 * but no task consumes the output, so compileSamplesKotlin keeps reading this
 * ORIGINAL file - which therefore must be valid Kotlin as is (whole members are
 * guarded, never half functions).
 *
 * NEVER_CONFIGURED is deliberately not defined anywhere: the samples source set
 * overrides the global strict mode, so this only produces a warning and a dead
 * branch instead of failing the build.
 */
class Sample {
    //#if NEVER_CONFIGURED
    fun experimental(): String = "experimental"
    //#endif

    fun base(): String = "sample base"
}
