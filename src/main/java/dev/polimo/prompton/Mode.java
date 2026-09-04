package dev.polimo.prompton;

/** How much of the outside world the SDK is allowed to touch. */
public enum Mode {
    /** Normal operation: poll PromptOn, mirror to disk, send monitoring logs. */
    LIVE,
    /** No HTTP at all. Snapshots are installed by the test, and logs are captured for assertions. */
    TEST,
    /** No HTTP. The snapshot comes from the disk cache or the bundle; logs are never sent. */
    OFFLINE
}
