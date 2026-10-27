package demo;

/**
 * Conditional blocks: if / elseif / else (and the "else if" two word form), version
 * aware comparison, numeric comparison, in-file variables (//#var / //#unset), the
 * //$$ activation marker, a custom directive (//#tag) and dead-branch safety (the
 * //#error below never fires because its branch is inactive).
 */
public final class Platform {

    //#tag release

    // TODO remove before release

    private Platform() {
    }

    public static String name() {
        //#if PLATFORM == "fabric"
        return "fabric";
        //#elseif PLATFORM == "forge"
        return "forge";
        //#else
        return "unknown";
        //#endif
    }

    public static String api() {
        //#if MC >= "1.17"
        return "modern";
        //#else
        return "legacy";
        //#endif
    }

    /** The JAVA_ONLY variable is set by the javaTask { } DSL block in build.gradle. */
    public static String javaOnly() {
        //#if JAVA_ONLY
        return "java";
        //#endif
    }

    /** Nested conditional blocks. */
    public static String describe() {
        //#if PLATFORM == "fabric"
        String platform = "fabric";
        //#if MC >= "1.17"
        return platform + " (modern)";
        //#else
        return platform + " (legacy)";
        //#endif
        //#else
        String platform = "other";
        return platform;
        //#endif
    }

    /** EXPERIMENTAL is defined with the value "0" - defined X is still true for it. */
    public static String experimental() {
        //#if defined EXPERIMENTAL && (PLATFORM == "fabric" || PLATFORM == "quilt")
        return "experimental";
        //#else
        return "stable";
        //#endif
    }

    /** CI comes from a Provider (System.getenv); string comparison is exact. */
    public static String onCi() {
        //#if CI == "definitely-not-set"
        return "ci";
        //#else
        return "local";
        //#endif
    }

    /** In-file variable assignment with a numeric comparison. */
    public static String varDemo() {
        //#var MINOR = 5
        //#if MINOR == 5 && MC >= "1.17"
        return "minor five, modern";
        //#else
        return "other";
        //#endif
    }

    /** //#unset removes a file-local binding again; !defined checks the result. */
    public static String unsetDemo() {
        //#var TEMP = 1
        //#unset TEMP
        //#if !defined TEMP && defined EXPERIMENTAL
        return "unset worked";
        //#else
        return "unset failed";
        //#endif
    }

    /** The two word form of elseif (with a trailing plain else so every configuration compiles). */
    public static String elseIfTwoWord() {
        //#if PLATFORM == "quilt"
        return "quilt";
        //#else if PLATFORM == "fabric"
        return "fabric two word";
        //#else
        return "other";
        //#endif
    }

    /** The //$$ marker activates the line below while its branch is live. */
    public static void log() {
        //#if PLATFORM == "fabric"
        //$$ System.out.println("fabric logging");
        //#endif
    }

    /** Errors in inactive branches never fire; the directive line stays a comment. */
    public static String unsupported() {
        //#if PLATFORM == "tizen"
        //#error unsupported platform
        return "never";
        //#endif
        return "supported";
    }

    /** Warnings in live branches surface through the build log. */
    public static void warned() {
        //#warning this shows in the build log
    }
}
