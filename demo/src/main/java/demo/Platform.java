package demo;

/**
 * Conditional blocks: if / elseif / else, version aware comparison, nesting,
 * the //$$ activation marker and a custom directive (//#tag).
 */
public final class Platform {

    //#tag release

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

    public static void log() {
        //#if PLATFORM == "fabric"
        //$$ System.out.println("fabric logging");
        //#endif
    }
}
