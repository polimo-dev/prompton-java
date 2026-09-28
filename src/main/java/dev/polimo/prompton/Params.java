package dev.polimo.prompton;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Shallow-merge helper for the two parameter maps the resolver layers.
 *
 * <pre>
 * params           = use_case.default_params &lt;- deployment.params
 * provider_options = model.provider_options   &lt;- deployment.provider_options
 * </pre>
 *
 * <p>The merge is shallow — a nested map on the right replaces the left side whole — and an
 * explicit {@code null} on the right is kept as {@code null} rather than removing the key, because
 * apps rely on sending {@code "only": null} to clear a provider restriction.
 */
public final class Params {

    private Params() {}

    /** Shallow-merges {@code override} on top of {@code base}; the override wins. */
    public static Map<String, Object> merge(Map<String, ?> base, Map<String, ?> override) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null) {
            base.forEach((k, v) -> merged.put(String.valueOf(k), v));
        }
        if (override != null) {
            override.forEach((k, v) -> merged.put(String.valueOf(k), v));
        }
        return merged;
    }

    /** A copy of {@code map} with string keys, sorted so equal maps print equally. */
    public static Map<String, Object> stringKeys(Map<String, ?> map) {
        Map<String, Object> out = new TreeMap<>();
        if (map != null) {
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    static Map<String, Object> mergeTools(Map<String, Object> params, Map<String, Object> tools) {
        Map<String, Object> merged = merge(null, params);
        Map<String, Object> provider = providerToolParams(tools);
        provider.forEach((key, value) -> {
            if (merged.containsKey(key) && !Objects.equals(merged.get(key), value)) {
                throw new PromptOnException("prompt tools conflict with params." + key);
            }
            merged.put(key, value);
        });
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> providerToolParams(Map<String, Object> tools) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (tools == null || tools.isEmpty()) {
            return out;
        }
        Object defs = tools.get("definitions");
        if (defs instanceof List<?> list && !list.isEmpty()) {
            List<Object> stripped = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
                    copy.remove("output_schema");
                    copy.remove("output_examples");
                    stripped.add(copy);
                } else {
                    stripped.add(item);
                }
            }
            out.put("tools", stripped);
        }
        if (tools.containsKey("tool_choice")) {
            out.put("tool_choice", tools.get("tool_choice"));
        }
        if (tools.containsKey("parallel_tool_calls")) {
            out.put("parallel_tool_calls", tools.get("parallel_tool_calls"));
        }
        return out;
    }
}
