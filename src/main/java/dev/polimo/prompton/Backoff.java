package dev.polimo.prompton;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** How long to wait before trying PromptOn again. Shared by the snapshot poller and the log buffer. */
final class Backoff {

    private Backoff() {}

    /** {@code base × 2^(failures-1)}, capped at {@code max}. */
    static Duration exponential(Duration base, int failures, Duration max) {
        if (failures <= 0) {
            return base;
        }
        long millis = base.toMillis();
        for (int i = 1; i < failures && millis < max.toMillis(); i++) {
            millis *= 2;
        }
        return Duration.ofMillis(Math.min(millis, max.toMillis()));
    }

    /**
     * The wait a {@code Retry-After} header asks for: a number of seconds or an HTTP date.
     *
     * @param value the header value, or {@code null}
     * @return the wait, or {@code null} when the header is absent or unparseable
     */
    static Duration retryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(trimmed)));
        } catch (NumberFormatException ignored) {
            // Fall through to the HTTP-date form.
        }
        try {
            Instant when = Instant.from(
                    DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).parse(trimmed));
            Duration wait = Duration.between(Instant.now(), when);
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
