package dev.polimo.prompton;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the call cost.
 *
 * @param inputTokens prompt tokens, or {@code null}
 * @param outputTokens completion tokens, or {@code null}
 * @param costUsd what it cost, or {@code null}
 * @param costSource {@code provider}, {@code catalog} or {@code unknown}
 * @param raw the provider's own usage object, kept for auditing; the server blanks it over 16 KB
 */
public record GenerationUsage(
        Integer inputTokens,
        Integer outputTokens,
        Double costUsd,
        String costSource,
        Map<String, Object> raw) {

    /** Token counts only. */
    public static GenerationUsage ofTokens(Integer inputTokens, Integer outputTokens) {
        return new GenerationUsage(inputTokens, outputTokens, null, "unknown", null);
    }

    /** The wire form. Nested nulls are sent as {@code null} and accepted. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("input_tokens", inputTokens);
        map.put("output_tokens", outputTokens);
        map.put("cost_usd", costUsd);
        map.put("cost_source", costSource == null ? "unknown" : costSource);
        map.put("raw", raw);
        return map;
    }
}
