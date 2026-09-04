package dev.polimo.prompton;

/** How much of the outside world the SDK is allowed to touch. */
public enum Mode {
    /** Normal operation: poll PromptOn, mirror to disk, send monitoring logs. */
    LIVE,
    /** No HTTP at all. Use-case documents are installed by the test, and logs are captured for assertions. */
    TEST,
    /**
     * No HTTP. The use-case document comes from the disk cache or the bundle.
     *
     * <p>Monitoring logs cannot be sent and are not stored: each one is counted in
     * {@link LogStats#droppedFailed()} and dropped, with one log line saying so. Configuring no
     * API key at all has the same effect on logs. Use {@link #TEST} when the records matter.
     */
    OFFLINE
}
