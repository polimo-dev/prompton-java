package dev.polimo.prompton;

import java.util.Locale;

/** How a failed generation failed, as PromptOn classifies it. */
public enum ErrorKind {
    /** The provider answered 4xx (other than a rate limit). */
    HTTP_4XX("http_4xx"),
    /** The provider answered 5xx. */
    HTTP_5XX("http_5xx"),
    /** The provider rate-limited the call. */
    RATE_LIMITED("rate_limited"),
    /** The call timed out. */
    TIMEOUT("timeout"),
    /** DNS, TLS, a reset connection — the request never got an answer. */
    TRANSPORT("transport"),
    /** The answer arrived but could not be parsed into what the application needed. */
    PARSE("parse"),
    /** Anything else, including an exception thrown by the application's own code. */
    APP("app");

    private final String wireName;

    ErrorKind(String wireName) {
        this.wireName = wireName;
    }

    /** The value sent in {@code error.kind}. */
    public String wireName() {
        return wireName;
    }

    /** Parses a wire value; anything unrecognised is {@link #APP}. */
    public static ErrorKind from(String value) {
        if (value == null) {
            return APP;
        }
        for (ErrorKind kind : values()) {
            if (kind.wireName.equals(value.trim().toLowerCase(Locale.ROOT))) {
                return kind;
            }
        }
        return APP;
    }

    /** The kind that matches an HTTP status: 429 is a rate limit, 5xx and 4xx their own kinds. */
    public static ErrorKind fromStatus(int status) {
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status >= 500) {
            return HTTP_5XX;
        }
        if (status >= 400) {
            return HTTP_4XX;
        }
        return APP;
    }
}
