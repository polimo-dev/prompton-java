package dev.polimo.prompton;

import java.util.Locale;
import java.util.Map;

/**
 * How much of a call's text a monitoring log may carry, as the snapshot declares it per use case.
 *
 * @param mode {@link Mode#FULL} truncates, {@link Mode#HASH} sends digests, {@link Mode#NONE} drops
 *     input and output entirely
 * @param sampleRate the fraction of successful records that keep their payload, 0..1
 * @param maxBytes the budget every truncation limit is derived from
 */
public record PayloadPolicy(Mode mode, double sampleRate, int maxBytes) {

    /** What the policy does with {@code input} and {@code output}. */
    public enum Mode {
        /** Keep the text, truncated to the caps derived from {@code max_bytes}. */
        FULL,
        /** Replace input and output with {@code {sha256, bytes, hashed}} wrappers. */
        HASH,
        /** Drop input and output; the narrow record still goes. */
        NONE
    }

    /** The default policy: full payloads, no sampling, a 256 KiB budget. */
    public static final PayloadPolicy DEFAULT = new PayloadPolicy(Mode.FULL, 1.0, 262144);

    /** Clamps {@code sampleRate} to 0..1 and {@code maxBytes} to a positive value. */
    public PayloadPolicy {
        sampleRate = Math.max(0.0, Math.min(1.0, sampleRate));
        maxBytes = maxBytes > 0 ? maxBytes : 262144;
        if (mode == null) {
            mode = Mode.FULL;
        }
    }

    /** Reads a {@code payload_policy} object out of a snapshot. {@code null} gives the default. */
    public static PayloadPolicy fromMap(Map<String, Object> map) {
        if (map == null) {
            return DEFAULT;
        }
        Object rawMode = map.get("mode");
        Mode mode = switch (rawMode == null ? "full" : String.valueOf(rawMode).toLowerCase(Locale.ROOT)) {
            case "hash" -> Mode.HASH;
            case "none" -> Mode.NONE;
            default -> Mode.FULL;
        };
        Object rate = map.get("sample_rate");
        Object bytes = map.get("max_bytes");
        return new PayloadPolicy(
                mode,
                rate instanceof Number n ? n.doubleValue() : 1.0,
                bytes instanceof Number n ? n.intValue() : 262144);
    }
}
