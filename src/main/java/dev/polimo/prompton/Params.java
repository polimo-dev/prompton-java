package dev.polimo.prompton;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shallow-merge helper for the two parameter maps the resolver layers.
 *
 * <pre>
 * effective_params           = use_case.default_params &lt;- deployment.params
 * effective_provider_options = model.provider_options   &lt;- deployment.provider_options
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
}
