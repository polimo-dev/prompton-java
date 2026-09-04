package dev.polimo.prompton;

import java.util.Locale;

/** Where the use-case document behind a use case came from. Reported as {@code source}. */
public enum Source {
    /** Fetched from PromptOn. */
    REMOTE("remote"),
    /** Read from the local disk cache. */
    DISK("disk"),
    /** Read from the use-case document bundled into the application. */
    BUNDLE("bundle"),
    /** Installed by the application itself, for example in tests. */
    MANUAL("manual");

    private final String wireName;

    Source(String wireName) {
        this.wireName = wireName;
    }

    /** The value sent in a monitoring log. */
    public String wireName() {
        return wireName;
    }

    /** Parses a wire value; anything unrecognised is {@link #MANUAL}. */
    public static Source from(String value) {
        if (value == null) {
            return MANUAL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "remote" -> REMOTE;
            case "disk" -> DISK;
            case "bundle" -> BUNDLE;
            default -> MANUAL;
        };
    }
}
