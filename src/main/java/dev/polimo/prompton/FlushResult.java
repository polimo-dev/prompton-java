package dev.polimo.prompton;

/**
 * What one {@link PromptOn#flush()} achieved.
 *
 * @param batches how many requests were made
 * @param sent how many records those requests carried
 * @param accepted how many PromptOn stored
 * @param duplicates how many it had already stored — a resend of the same ids
 * @param rejected how many it refused, per record
 * @param remaining how many are still queued, because the timeout ran out or a retry is pending
 */
public record FlushResult(
        int batches, int sent, int accepted, int duplicates, int rejected, int remaining) {

    /** Nothing was queued. */
    public static final FlushResult EMPTY = new FlushResult(0, 0, 0, 0, 0, 0);

    /** Whether everything queued was sent and nothing is left. */
    public boolean drained() {
        return remaining == 0;
    }
}
