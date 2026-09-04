package dev.polimo.prompton;

import java.util.Locale;
import java.util.Set;

/**
 * PromptOn's normalised stop reason, derived from whatever the provider called
 * {@code finish_reason}.
 *
 * <table>
 *   <caption>Normalisation table</caption>
 *   <tr><th>Raw finish reason</th><th>StopKind</th></tr>
 *   <tr><td>{@code stop}, {@code end_turn}, {@code stop_sequence}</td><td>{@link #STOP}</td></tr>
 *   <tr><td>{@code length}, {@code max_tokens}</td><td>{@link #LENGTH}</td></tr>
 *   <tr><td>{@code tool_calls}, {@code tool_use}, {@code tool_call}</td><td>{@link #TOOL_CALL}</td></tr>
 *   <tr><td>{@code content_filter}</td><td>{@link #CONTENT_FILTER}</td></tr>
 *   <tr><td>anything else, empty or absent</td><td>{@link #OTHER}</td></tr>
 * </table>
 *
 * <p>Comparison trims and lowercases, so Google's {@code STOP} and {@code MAX_TOKENS} land
 * correctly, and normalisation is idempotent — feeding a {@code StopKind} back in returns itself,
 * which matters because the server re-normalises whatever the client sent. Google's {@code SAFETY}
 * and {@code RECITATION} map to {@link #OTHER}, not {@link #CONTENT_FILTER}: only the literal
 * string {@code content_filter} lands there. And a tool call is <em>not</em> a truncation: only
 * {@link #LENGTH} sets {@link #truncated()}.
 */
public enum StopKind {
    /** The model finished on its own. */
    STOP("stop"),
    /** The output was cut off by the token limit — the one kind that counts as truncated. */
    LENGTH("length"),
    /** The model asked to call a tool. */
    TOOL_CALL("tool_call"),
    /** The provider's safety filter stopped the generation. */
    CONTENT_FILTER("content_filter"),
    /** Anything else, including an absent or unrecognised finish reason. */
    OTHER("other");

    private static final Set<String> STOP_REASONS = Set.of("stop", "end_turn", "stop_sequence");
    private static final Set<String> LENGTH_REASONS = Set.of("length", "max_tokens");
    private static final Set<String> TOOL_REASONS = Set.of("tool_call", "tool_calls", "tool_use");

    private final String wireName;

    StopKind(String wireName) {
        this.wireName = wireName;
    }

    /** The value sent in a monitoring log's {@code stop_kind} field. */
    public String wireName() {
        return wireName;
    }

    /** Normalises a raw provider finish reason. {@code null} and unknown values give {@link #OTHER}. */
    public static StopKind normalize(String finishReason) {
        if (finishReason == null) {
            return OTHER;
        }
        String reason = finishReason.trim().toLowerCase(Locale.ROOT);
        if (STOP_REASONS.contains(reason)) {
            return STOP;
        }
        if (LENGTH_REASONS.contains(reason)) {
            return LENGTH;
        }
        if (TOOL_REASONS.contains(reason)) {
            return TOOL_CALL;
        }
        if (reason.equals("content_filter")) {
            return CONTENT_FILTER;
        }
        return OTHER;
    }

    /** Whether this stop kind means the output was cut off. Only {@link #LENGTH} is. */
    public boolean truncated() {
        return this == LENGTH;
    }

    /** Whether a raw finish reason means the output was cut off. */
    public static boolean truncated(String finishReason) {
        return normalize(finishReason) == LENGTH;
    }
}
