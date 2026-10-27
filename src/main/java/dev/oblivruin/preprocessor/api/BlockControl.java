package dev.oblivruin.preprocessor.api;

/**
 * Allows custom directives to open and close conditional blocks with the same
 * semantics as the built-in {@code //#if} / {@code //#endif}.
 */
public interface BlockControl {
    /**
     * Opens a block that is active when {@code condition} is {@code true}.
     * Inside an inactive branch the block is opened inertly (never active).
     */
    void beginBranch(boolean condition);

    /**
     * Opens an alternative branch of the innermost open block, like {@code //#elseif}.
     */
    void nextBranch(boolean condition);

    /**
     * Opens the fallback branch of the innermost open block, like {@code //#else}.
     */
    void nextBranch();

    /** Closes the innermost open block, like {@code //#endif}. */
    void endBranch();

    /** @return {@code true} if at least one block is open */
    boolean hasOpenBranch();

    /** @return the live active state of the current position */
    boolean branchActive();
}
