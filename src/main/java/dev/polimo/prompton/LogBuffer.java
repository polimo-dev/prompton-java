package dev.polimo.prompton;

import dev.polimo.prompton.http.HttpRequest;
import dev.polimo.prompton.http.HttpResponse;
import dev.polimo.prompton.internal.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The monitoring-log queue: records go in without blocking the caller, and go out in batches.
 *
 * <p>A batch is sent when the queue reaches the size or byte trigger, or when the flush interval
 * elapses, and carries at most 200 records and under 5 MB — one batch per environment, since
 * {@code ?environment=} applies to the whole request. Records are therefore held in one queue per
 * environment and a batch drains the environment whose oldest record has waited longest, so a call
 * site logging into two environments still sends two requests rather than one per record.
 *
 * <p>Record ids are UUIDv7 idempotency keys, so a
 * retry is safe: a {@code 429} or any 5xx resends the same batch with the same ids, honouring
 * {@code Retry-After} and otherwise doubling from one second up to five minutes; a {@code 413} is
 * split in half; any other 4xx drops the batch, because retrying a request PromptOn has refused only
 * loses the next one. Records queue behind a batch that is waiting to be retried. Over the queue cap
 * the oldest are dropped and counted.
 */
final class LogBuffer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LogBuffer.class.getName());
    private static final int MAX_BATCH_RECORDS = 200;
    private static final int MAX_BATCH_BYTES = 4_500_000;
    private static final Duration RETRY_BASE = Duration.ofSeconds(1);

    private record Queued(Map<String, Object> record, String environment, int bytes, long sequence) {}

    private static final class Batch {
        final List<Queued> records;
        final String environment;
        int attempts;

        Batch(List<Queued> records, String environment, int attempts) {
            this.records = records;
            this.environment = environment;
            this.attempts = attempts;
        }
    }

    private final PromptOnConfig config;
    private final Map<String, ArrayDeque<Queued>> queues = new LinkedHashMap<>();
    private final ArrayDeque<Batch> pending = new ArrayDeque<>();
    private final Object lock = new Object();
    private final ScheduledExecutorService sender;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong droppedFull = new AtomicLong();
    private final AtomicLong droppedRejected = new AtomicLong();
    private final AtomicLong droppedFailed = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    private int queuedCount;
    private long queuedBytes;
    private long nextSequence;
    private Instant pausedUntil = Instant.EPOCH;
    private Instant lastFullWarning = Instant.EPOCH;
    private boolean fourxxLogged;
    private boolean offlineLogged;

    LogBuffer(PromptOnConfig config) {
        this.config = config;
        this.sender = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "prompton-log-sender");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Math.max(1, config.logFlushInterval().toMillis());
        this.sender.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Queues one record. Returns immediately; never throws. */
    void enqueue(Map<String, Object> record, String environment) {
        int bytes;
        try {
            bytes = Json.byteSize(Json.write(record));
        } catch (RuntimeException e) {
            LOG.warning("[PromptOn] dropping a monitoring log that is not JSON-encodable: " + e);
            droppedFailed.incrementAndGet();
            return;
        }
        if (bytes > MAX_BATCH_BYTES) {
            LOG.warning("[PromptOn] dropping a monitoring log of " + bytes
                    + " bytes: it cannot fit in a request on its own");
            droppedFailed.incrementAndGet();
            return;
        }
        String key = environment == null ? config.environment() : environment;
        boolean trigger;
        synchronized (lock) {
            while (queuedCount >= config.logMaxBuffer() && dropOldest()) {
                warnQueueFull();
            }
            queues.computeIfAbsent(key, k -> new ArrayDeque<>())
                    .addLast(new Queued(record, key, bytes, nextSequence++));
            queuedCount++;
            queuedBytes += bytes;
            trigger = queuedCount >= config.logFlushSize() || queuedBytes >= config.logFlushBytes();
        }
        if (trigger) {
            submit(this::tick);
        }
    }

    /** Drops the record that has waited longest, whichever environment it belongs to. */
    private boolean dropOldest() {
        ArrayDeque<Queued> oldest = oldestQueue();
        if (oldest == null) {
            return false;
        }
        Queued dropped = oldest.removeFirst();
        if (oldest.isEmpty()) {
            queues.remove(dropped.environment());
        }
        queuedCount--;
        queuedBytes -= dropped.bytes();
        droppedFull.incrementAndGet();
        return true;
    }

    /** The environment queue whose head has waited longest, or {@code null} when nothing is queued. */
    private ArrayDeque<Queued> oldestQueue() {
        ArrayDeque<Queued> best = null;
        long bestSequence = Long.MAX_VALUE;
        for (ArrayDeque<Queued> candidate : queues.values()) {
            Queued head = candidate.peekFirst();
            if (head != null && head.sequence() < bestSequence) {
                bestSequence = head.sequence();
                best = candidate;
            }
        }
        return best;
    }

    private void warnQueueFull() {
        Instant now = Instant.now();
        if (Duration.between(lastFullWarning, now).toMillis() >= 60_000) {
            lastFullWarning = now;
            LOG.warning("[PromptOn] the monitoring-log queue is full; dropping the oldest records"
                    + " (total dropped: " + droppedFull.get() + ")");
        }
    }

    private void submit(Runnable task) {
        try {
            sender.execute(task);
        } catch (RejectedExecutionException ignored) {
            // The buffer is shutting down; the drain in close() is the last chance.
        }
    }

    /** Sends everything queued and waits for the result. */
    FlushResult flush(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Future<FlushResult> future;
        try {
            future = sender.submit(() -> drainAll(deadline));
        } catch (RejectedExecutionException e) {
            return snapshotResult(0, 0, 0, 0, 0);
        }
        try {
            return future.get(Math.max(1, timeout.toMillis() + 500), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return snapshotResult(0, 0, 0, 0, 0);
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, e, () -> "[PromptOn] flush failed: " + e.getCause());
            return snapshotResult(0, 0, 0, 0, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return snapshotResult(0, 0, 0, 0, 0);
        }
    }

    private FlushResult snapshotResult(int batches, int records, int accepted, int duplicates,
            int rejected) {
        synchronized (lock) {
            return new FlushResult(batches, records, accepted, duplicates, rejected, remaining());
        }
    }

    /** UseCaseDocument of the queue and its counters. */
    LogStats stats() {
        synchronized (lock) {
            return new LogStats(remaining(), queuedBytes, sent.get(), droppedFull.get(),
                    droppedRejected.get(), droppedFailed.get(), retries.get());
        }
    }

    /** Records still waiting: queued, plus those in a batch that is waiting to be retried. */
    private int remaining() {
        return queuedCount + pending.stream().mapToInt(b -> b.records.size()).sum();
    }

    // ---------------------------------------------------------------------
    // sending, always on the sender thread

    private void tick() {
        if (Instant.now().isBefore(pausedUntil)) {
            return;
        }
        for (int i = 0; i < MAX_BATCH_RECORDS; i++) {
            Batch batch = takeBatch();
            if (batch == null) {
                return;
            }
            send(batch, null);
            if (Instant.now().isBefore(pausedUntil)) {
                return;
            }
        }
    }

    private FlushResult drainAll(Instant deadline) {
        int batches = 0;
        int records = 0;
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        while (Instant.now().isBefore(deadline)) {
            if (Instant.now().isBefore(pausedUntil)) {
                if (pausedUntil.isAfter(deadline)) {
                    break;
                }
                long wait = Duration.between(Instant.now(), pausedUntil).toMillis();
                if (wait <= 0) {
                    continue;
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            Batch batch = takeBatch();
            if (batch == null) {
                break;
            }
            int[] counts = new int[3];
            batches++;
            records += batch.records.size();
            send(batch, counts);
            accepted += counts[0];
            duplicates += counts[1];
            rejected += counts[2];
        }
        return snapshotResult(batches, records, accepted, duplicates, rejected);
    }

    private Batch takeBatch() {
        synchronized (lock) {
            if (!pending.isEmpty()) {
                Batch batch = pending.removeFirst();
                for (Queued record : batch.records) {
                    queuedBytes -= record.bytes();
                }
                return batch;
            }
            ArrayDeque<Queued> oldest = oldestQueue();
            if (oldest == null) {
                return null;
            }
            String environment = oldest.peekFirst().environment();
            List<Queued> records = new ArrayList<>();
            int bytes = 0;
            while (!oldest.isEmpty() && records.size() < MAX_BATCH_RECORDS) {
                Queued head = oldest.peekFirst();
                if (!records.isEmpty() && bytes + head.bytes() > MAX_BATCH_BYTES) {
                    break;
                }
                oldest.removeFirst();
                queuedCount--;
                queuedBytes -= head.bytes();
                bytes += head.bytes();
                records.add(head);
            }
            if (oldest.isEmpty()) {
                queues.remove(environment);
            }
            return new Batch(records, environment, 0);
        }
    }

    private void requeue(Batch batch) {
        synchronized (lock) {
            pending.addFirst(batch);
            for (Queued record : batch.records) {
                queuedBytes += record.bytes();
            }
        }
    }

    private void send(Batch batch, int[] counts) {
        if (batch.records.isEmpty()) {
            return;
        }
        if (!config.remoteEnabled()) {
            droppedFailed.addAndGet(batch.records.size());
            if (!offlineLogged) {
                offlineLogged = true;
                LOG.info("[PromptOn] " + (config.mode() == Mode.LIVE
                                ? "no API key configured"
                                : config.mode().name().toLowerCase(java.util.Locale.ROOT) + " mode")
                        + ": monitoring logs are counted in logStats().droppedFailed() and dropped"
                        + " rather than sent or stored");
            }
            return;
        }
        List<Object> records = new ArrayList<>(batch.records.size());
        for (Queued queued : batch.records) {
            records.add(queued.record());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("logs", records);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json");
        headers.put("content-type", "application/json");
        headers.put("user-agent", config.userAgent());
        headers.put("authorization", "Bearer " + config.apiKey());
        String environment = batch.environment == null ? config.environment() : batch.environment;
        String url = config.baseUrl() + "/logs?environment="
                + URLEncoder.encode(environment, StandardCharsets.UTF_8);

        HttpResponse response;
        try {
            response = config.httpClient().send(new HttpRequest(
                    "POST", url, headers, Json.write(body), config.requestTimeout()));
        } catch (IOException | RuntimeException e) {
            retryLater(batch, null, "transport: " + e);
            return;
        }

        int status = response.status();
        if (status >= 200 && status < 300) {
            accept(batch, response, counts);
            return;
        }
        if (status == 413) {
            split(batch);
            return;
        }
        if (status == 429 || status >= 500) {
            retryLater(batch, Backoff.retryAfterFrom(response), "HTTP " + status);
            return;
        }
        droppedFailed.addAndGet(batch.records.size());
        if (!fourxxLogged) {
            fourxxLogged = true;
            LOG.warning("[PromptOn] the server refused a monitoring-log batch with HTTP " + status
                    + " and it will not be retried: " + response.body());
        }
    }

    private void accept(Batch batch, HttpResponse response, int[] counts) {
        pausedUntil = Instant.EPOCH;
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        try {
            Map<String, Object> body = Json.parseObject(response.body());
            accepted = Json.intAt(body, "accepted", 0);
            duplicates = Json.intAt(body, "duplicates", 0);
            List<Object> refused = Json.listAt(body, "rejected");
            if (refused != null && !refused.isEmpty()) {
                rejected = refused.size();
                droppedRejected.addAndGet(rejected);
                LOG.warning("[PromptOn] " + rejected + " monitoring log(s) were rejected: "
                        + Json.write(refused));
            }
        } catch (RuntimeException ignored) {
            accepted = batch.records.size();
        }
        sent.addAndGet(accepted);
        if (counts != null) {
            counts[0] = accepted;
            counts[1] = duplicates;
            counts[2] = rejected;
        }
    }

    private void split(Batch batch) {
        if (batch.records.size() <= 1) {
            droppedFailed.addAndGet(batch.records.size());
            LOG.warning("[PromptOn] a single monitoring log was refused with HTTP 413 and dropped");
            return;
        }
        int half = batch.records.size() / 2;
        Batch second = new Batch(
                new ArrayList<>(batch.records.subList(half, batch.records.size())),
                batch.environment, batch.attempts);
        Batch first = new Batch(
                new ArrayList<>(batch.records.subList(0, half)), batch.environment, batch.attempts);
        requeue(second);
        requeue(first);
        LOG.info("[PromptOn] a batch of " + batch.records.size()
                + " monitoring logs was refused with HTTP 413; splitting it in half");
    }

    private void retryLater(Batch batch, Duration retryAfter, String reason) {
        batch.attempts++;
        if (batch.attempts > config.logMaxAttempts()) {
            droppedFailed.addAndGet(batch.records.size());
            LOG.warning("[PromptOn] dropping " + batch.records.size() + " monitoring log(s) after "
                    + batch.attempts + " failed attempts (" + reason + ")");
            return;
        }
        Duration wait = retryAfter != null
                ? retryAfter
                : Backoff.exponential(RETRY_BASE, batch.attempts, config.maxBackoff());
        pausedUntil = Instant.now().plus(wait);
        retries.incrementAndGet();
        requeue(batch);
        LOG.log(Level.INFO, () -> "[PromptOn] retrying " + batch.records.size()
                + " monitoring log(s) in " + wait.toMillis() + "ms (" + reason + ")");
    }

    @Override
    public void close() {
        FlushResult result = flush(config.shutdownFlushTimeout());
        if (!result.drained()) {
            LOG.warning("[PromptOn] shutting down with " + result.remaining()
                    + " monitoring log(s) unsent");
        }
        sender.shutdownNow();
        try {
            sender.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
