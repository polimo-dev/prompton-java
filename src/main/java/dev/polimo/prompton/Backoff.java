package dev.polimo.prompton;

import dev.polimo.prompton.http.HttpResponse;
import dev.polimo.prompton.internal.Json;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

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

    /**
     * The wait a response asks for: the {@code Retry-After} header, falling back to the contract's
     * {@code error.details.retry_after} in the body.
     *
     * @param response the answer PromptOn gave
     * @return the wait, or {@code null} when the response names none
     */
    static Duration retryAfterFrom(HttpResponse response) {
        Duration header = retryAfter(response.header("retry-after"));
        if (header != null) {
            return header;
        }
        try {
            Map<String, Object> error = Json.mapAt(Json.parseObject(response.body()), "error");
            Map<String, Object> details = Json.mapAt(error, "details");
            Integer seconds = Json.intAt(details, "retry_after", null);
            return seconds == null ? null : Duration.ofSeconds(Math.max(0, seconds));
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
