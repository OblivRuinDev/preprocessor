package dev.oblivruin.preprocessor;

import dev.oblivruin.preprocessor.api.DirectiveContext;
import dev.oblivruin.preprocessor.api.DirectiveHandler;
import groovy.lang.Closure;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry for custom preprocessor directives.
 *
 * <p>Example (Groovy DSL):</p>
 * <pre>
 * preprocessor {
 *     directives.register('say') { ctx -&gt;
 *         ctx.replaceLine('// [say] ' + ctx.argument())
 *     }
 * }
 * </pre>
 *
 * <p>Example (Kotlin DSL):</p>
 * <pre>
 * preprocessor {
 *     directives.register("say") { ctx -&gt;
 *         ctx.replaceLine("// [say] " + ctx.argument())
 *     }
 * }
 * </pre>
 */
public class DirectiveRegistry {
    /** Words handled by the engine itself and therefore not available for custom directives. */
    private static final String[] RESERVED = {"if", "elseif", "else", "endif", "var", "assign", "unset", "error", "warning"};

    private final Map<String, DirectiveHandler> handlers = new LinkedHashMap<>();

    /**
     * Registers a custom directive.
     *
     * @param name    the directive word as written in the files, e.g. {@code say} for {@code //#say hello}
     * @param handler the handler executed for every matching line
     */
    public void register(String name, DirectiveHandler handler) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("directive name must not be empty");
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            throw new IllegalArgumentException("directive name must start with a letter or underscore: " + name);
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                throw new IllegalArgumentException("directive name must be a valid identifier: " + name);
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String reserved : RESERVED) {
            if (reserved.equals(lower)) {
                throw new IllegalArgumentException("'" + name + "' is a built-in directive and cannot be overridden");
            }
        }
        if (handlers.containsKey(lower)) {
            throw new IllegalArgumentException("directive '" + name + "' is already registered");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler for directive '" + name + "' must not be null");
        }
        handlers.put(lower, handler);
    }

    /**
     * Registers a custom directive implemented by a Groovy closure receiving the
     * {@link dev.oblivruin.preprocessor.api.DirectiveContext} as its single argument.
     */
    public void register(String name, Closure<?> closure) {
        if (closure == null) {
            throw new IllegalArgumentException("closure for directive '" + name + "' must not be null");
        }
        register(name, new ClosureDirectiveHandler(closure));
    }

    /** @return an unmodifiable snapshot of all registered directives (lowercase names) */
    public Map<String, DirectiveHandler> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(handlers));
    }

    /** @return {@code true} if no custom directive is registered */
    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    /**
     * Serializable: it only wraps a Groovy {@link Closure}, which is Serializable
     * itself. This keeps closure registered directives configuration cache friendly.
     */
    private static final class ClosureDirectiveHandler implements DirectiveHandler, Serializable {
        private static final long serialVersionUID = 1L;

        private final Closure<?> closure;

        ClosureDirectiveHandler(Closure<?> closure) {
            this.closure = closure;
        }

        @Override
        public void handle(DirectiveContext context) {
            closure.call(context);
        }
    }
}
