package dev.polimo.prompton;

/** What one snapshot refresh did. */
public enum RefreshOutcome {
    /** A new document was fetched and installed. */
    UPDATED,
    /** PromptOn confirmed the ETag: the document in memory is current. */
    NOT_MODIFIED,
    /** The refresh failed. The previous document keeps serving; nothing was lost. */
    FAILED,
    /** Nothing was attempted: no API key, or the SDK is in test or offline mode. */
    SKIPPED
}
