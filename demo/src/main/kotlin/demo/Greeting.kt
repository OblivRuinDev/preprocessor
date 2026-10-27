package demo

/**
 * The KOTLIN_ONLY variable is set by the kotlinTask { } DSL block in build.gradle;
 * the //$$ marker activates the line below while its branch is live.
 */
fun greeting(): String {
    //#if KOTLIN_ONLY
    return "hello from kotlin"
    //#else
    //$$ return "hello"
    //#endif
}
