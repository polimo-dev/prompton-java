package dev.polimo.prompton;

import java.util.Locale;

/** What shape of call a prompt describes. */
public enum UseCaseKind {
    /** A message list. The pinned prompt version carries {@code messages}. */
    CHAT("chat"),
    /** A native Decision state and questions document. */
    DECISION("decision"),
    /** A single string. The pinned prompt version carries {@code text_template}. */
    TEXT("text"),
    /** An embedding call. There is no prompt at all. */
    EMBEDDING("embedding");

    private final String wireName;

    UseCaseKind(String wireName) {
        this.wireName = wireName;
    }

    /** The value used in the prompt document and in monitoring logs. */
    public String wireName() {
        return wireName;
    }

    /** Parses a prompt document value; {@code null} is the legacy chat default. */
    public static UseCaseKind from(String value) {
        if (value == null) {
            return CHAT;
        }
        return fromRequired(value);
    }

    /** Parses a required prompt document value. */
    public static UseCaseKind fromRequired(String value) {
        if (value == null) {
            throw new PromptOnException("prompt kind is missing");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "chat" -> CHAT;
            case "decision" -> DECISION;
            case "text" -> TEXT;
            case "embedding" -> EMBEDDING;
            default -> throw new PromptOnException("unsupported prompt kind " + value);
        };
    }
}
