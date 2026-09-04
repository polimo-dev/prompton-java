package dev.polimo.prompton;

/**
 * A snapshot of the monitoring-log buffer, for a health endpoint or a test.
 *
 * @param queued records waiting to be sent, including those in a batch awaiting retry
 * @param queuedBytes their encoded size
 * @param sent records PromptOn accepted over this process's life — the running total of
 *     {@link FlushResult#accepted()}, not of {@link FlushResult#records()}
 * @param droppedFull records dropped because the queue was full
 * @param droppedRejected records PromptOn refused
 * @param droppedFailed records dropped after their batch exhausted its retries or hit a 4xx —
 *     and, with no API key or in {@link Mode#OFFLINE}, every record, since nothing can be sent
 * @param retries how many times a batch has been resent
 */
public record LogStats(
        int queued,
        long queuedBytes,
        long sent,
        long droppedFull,
        long droppedRejected,
        long droppedFailed,
        long retries) {}
