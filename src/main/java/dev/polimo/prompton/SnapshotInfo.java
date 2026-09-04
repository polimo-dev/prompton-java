package dev.polimo.prompton;

import java.time.Instant;

/**
 * Where the configuration in memory came from and how old it is — what a health endpoint should
 * report, and the first thing to look at when a deployment does not seem to have taken effect.
 *
 * @param source {@code remote}, {@code disk}, {@code bundle} or {@code manual}; {@code null} when
 *     nothing has been loaded at all
 * @param etag the ETag of the document in memory
 * @param lastModified the {@code Last-Modified} PromptOn reported for it
 * @param fetchedAt when this process obtained it
 * @param stale whether the last refresh failed, or the document did not come from PromptOn
 * @param ageSeconds how old the document is, in seconds
 * @param project the project the document belongs to
 * @param environment the environment it describes
 */
public record SnapshotInfo(
        ResolutionSource source,
        String etag,
        String lastModified,
        Instant fetchedAt,
        boolean stale,
        Long ageSeconds,
        String project,
        String environment) {

    /** Nothing is loaded. */
    public static final SnapshotInfo NONE =
            new SnapshotInfo(null, null, null, null, true, null, null, null);

    /** Whether any document is in memory at all. */
    public boolean loaded() {
        return source != null;
    }
}
