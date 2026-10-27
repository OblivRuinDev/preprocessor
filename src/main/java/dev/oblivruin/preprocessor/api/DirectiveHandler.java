package dev.oblivruin.preprocessor.api;

/**
 * A custom preprocessor directive registered with
 * {@code preprocessor.directives.register(name, handler)}.
 *
 * <p>Handlers are invoked for every line matching {@code <prefix><name>[ argument]}
 * (e.g. {@code //#mydirective foo}). Like the built-in directives they are invoked
 * even inside inactive branches so they can participate in block tracking through
 * {@link DirectiveContext#blocks()}; {@link DirectiveContext#active()} reports whether
 * the handler was reached from an active branch.</p>
 *
 * <p>Handlers must be pure functions of their inputs: they must not capture mutable
 * build state, because registered handlers only participate in up-to-date checks by
 * their name and class.</p>
 */
@FunctionalInterface
public interface DirectiveHandler {
    /**
     * Processes one directive line.
     *
     * @param context access to the argument, variables, output and block state
     */
    void handle(DirectiveContext context);
}
