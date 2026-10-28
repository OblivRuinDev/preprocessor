package dev.oblivruin.preprocessor.core;

import dev.oblivruin.preprocessor.api.Variables;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Variable lookup for a single file. Reads resolve through the chain
 * {@code file-local overrides -> task variables}; writes always go to the file-local
 * overlay created by {@code //#var} or custom directives.
 */
public final class VariableScope implements Variables {
    private final Map<String, Object> base;
    private final Map<String, Object> local = new LinkedHashMap<>();

    public VariableScope(Map<String, Object> base) {
        this.base = base == null ? new LinkedHashMap<>() : base;
    }

    @Override
    public boolean defined(String name) {
        return get(name) != null;
    }

    @Override
    public Object get(String name) {
        Object localValue = local.get(name);
        if (localValue != null) {
            return localValue;
        }
        return base.get(name);
    }

    @Override
    public Object getOrDefault(String name, Object defaultValue) {
        Object value = get(name);
        return value != null ? value : defaultValue;
    }

    @Override
    public void set(String name, Object value) {
        if (value == null) {
            local.remove(name);
        } else {
            local.put(name, value);
        }
    }

    @Override
    public void unset(String name) {
        local.remove(name);
    }

    @Override
    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>(base);
        result.putAll(local);
        return result;
    }
}
