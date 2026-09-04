package dev.polimo.prompton;

import java.util.Locale;

/** What shape of call a use case describes. */
public enum UseCaseKind {
    /** A message list. The pinned prompt version carries {@code messages}. */
    CHAT("chat"),
    /** A single string. The pinned prompt version carries {@code text_template}. */
    TEXT("text"),
    /** An embedding call. There is no prompt at all. */
    EMBEDDING("embedding");

    private final String wireName;

    UseCaseKind(String wireName) {
        this.wireName = wireName;
    }

    /** The value used in the use-case document and in monitoring logs. */
    public String wireName() {
        return wireName;
    }

    /** Parses a use-case document value; anything unrecognised (or {@code null}) is {@link #CHAT}. */
    public static UseCaseKind from(String value) {
        if (value == null) {
            return CHAT;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "text" -> TEXT;
            case "embedding" -> EMBEDDING;
            default -> CHAT;
        };
    }
}
