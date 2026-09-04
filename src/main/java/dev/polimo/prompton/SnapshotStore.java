package dev.polimo.prompton;

import dev.polimo.prompton.http.HttpRequest;
import dev.polimo.prompton.http.HttpResponse;
import dev.polimo.prompton.internal.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The three tiers of configuration, in the order they are consulted: memory, the disk cache, the
 * bundled file — and behind them PromptOn itself.
 *
 * <p>Every use-case lookup reads memory, so within the cache TTL no call touches the network. Once the TTL
 * has passed a refresh runs in the background: {@code GET /use-cases} with {@code If-None-Match},
 * where a {@code 304} means there is nothing to parse. A refresh never blocks a provider call and never
 * fails one — while it is in flight, and if it fails, the previous document keeps serving. A
 * {@code 429} is honoured to the second from {@code Retry-After}; a 5xx, a timeout or a transport
 * failure backs off by doubling from the TTL up to five minutes. Only when no tier has a document at
 * all does resolution fail.
 *
 * <p>A document for another environment or project is never used: both are recorded in the file and
 * a mismatch means the file is ignored, so a staging process cannot boot on a production bundle.
 */
final class SnapshotStore implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SnapshotStore.class.getName());

    /** One loaded document and where it came from. */
    record Entry(
            UseCaseDocument useCaseDocument,
            String etag,
            String lastModified,
            Source source,
            Instant fetchedAt,
            Instant staleSince,
            String rawJson) {}

    private final PromptOnConfig config;
    private final AtomicReference<Entry> current = new AtomicReference<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicLong httpCalls = new AtomicLong();
    private final AtomicLong attemptsFinished = new AtomicLong();
    private final Object arrival = new Object();
    private final Object scheduleLock = new Object();

    private volatile Instant nextAttemptAt = Instant.EPOCH;
    private volatile int failures;
    private volatile boolean noKeyLogged;
    private ScheduledExecutorService poller;
    private ScheduledExecutorService refresher;

    SnapshotStore(PromptOnConfig config) {
        this.config = config;
    }

    /** Loads the local tiers and, in live mode, starts the poll loop. */
    void start() {
        if (config.mode() == Mode.TEST) {
            return;
        }
        loadLocal();
        if (!config.remoteEnabled()) {
            if (!noKeyLogged) {
                noKeyLogged = true;
                LOG.log(Level.INFO, () -> "[PromptOn] " + whyNoRemote() + "; resolving from "
                        + (current.get() == null ? "nothing" : current.get().source().wireName())
                        + " only, and never contacting the server");
            }
            return;
        }
        synchronized (scheduleLock) {
            refresher = Executors.newSingleThreadScheduledExecutor(
                    runnable -> daemon(runnable, "prompton-use-cases-refresh"));
            if (config.pollingEnabled()) {
                poller = Executors.newSingleThreadScheduledExecutor(
                        runnable -> daemon(runnable, "prompton-use-cases-poll"));
                poller.scheduleWithFixedDelay(
                        this::pollTick, 0, Math.max(1, config.cacheTtl().toMillis()),
                        TimeUnit.MILLISECONDS);
            }
        }
        if (!config.pollingEnabled()) {
            triggerRefresh();
        }
    }

    /** Why no remote call will be made: the mode, or the missing key. */
    private String whyNoRemote() {
        if (config.mode() != Mode.LIVE) {
            return config.mode().name().toLowerCase(java.util.Locale.ROOT) + " mode";
        }
        return "no API key configured";
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** The document in memory, or {@code null}. */
    Entry current() {
        return current.get();
    }

    /**
     * The document to load use cases from, waiting for the first fetch when memory, disk and bundle are
     * all empty. Never blocks once anything has been loaded.
     */
    Entry require() {
        Entry entry = current.get();
        if (entry != null) {
            triggerRefresh();
            return entry;
        }
        if (config.remoteEnabled()) {
            entry = awaitFirstDocument();
        }
        if (entry == null) {
            throw UseCaseException.of(UseCaseException.Reason.NOT_READY, null);
        }
        return entry;
    }

    /**
     * Starts an attempt when one is due and waits for it, so a process that came up while PromptOn
     * was down recovers on a later lookup instead of failing for the rest of its life. Waits only
     * while an attempt is actually running: during a rate-limit pause or a backoff it returns at
     * once, because nothing is going to change until the pause has elapsed.
     */
    private Entry awaitFirstDocument() {
        long seen = attemptsFinished.get();
        if (!triggerRefresh()) {
            return current.get();
        }
        Instant deadline = Instant.now().plus(config.initialFetchTimeout());
        synchronized (arrival) {
            while (current.get() == null && attemptsFinished.get() == seen) {
                long wait = Duration.between(Instant.now(), deadline).toMillis();
                if (wait <= 0) {
                    LOG.warning("[PromptOn] the first snapshot fetch has not returned yet");
                    break;
                }
                try {
                    arrival.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return current.get();
    }

    /**
     * Queues a background refresh when the TTL has passed and no retry pause is in force.
     *
     * <p>At most one refresh is ever in flight and at most one more queued behind it, so a burst of
     * resolves arriving on an expired TTL produces one fetch, not one per caller.
     *
     * @return whether an attempt is now running or queued, and therefore worth waiting for
     */
    private boolean triggerRefresh() {
        if (!config.remoteEnabled()) {
            return false;
        }
        if (refreshing.get() || refreshQueued.get()) {
            return true;
        }
        if (Instant.now().isBefore(nextAttemptAt)) {
            return false;
        }
        ScheduledExecutorService executor;
        synchronized (scheduleLock) {
            executor = refresher;
        }
        if (executor == null || executor.isShutdown()) {
            return false;
        }
        if (!refreshQueued.compareAndSet(false, true)) {
            return true;
        }
        try {
            executor.execute(this::backgroundRefresh);
            return true;
        } catch (RejectedExecutionException e) {
            refreshQueued.set(false);
            return false;
        }
    }

    /**
     * A queued refresh, run on the refresher thread. It re-reads the pause first: a {@code 429} or a
     * backoff may have been set after this task was queued, and the contract is that the server is
     * not contacted again before {@code Retry-After} has elapsed.
     */
    private void backgroundRefresh() {
        refreshQueued.set(false);
        if (Instant.now().isBefore(nextAttemptAt)) {
            return;
        }
        refreshQuietly();
    }

    private void pollTick() {
        if (Instant.now().isBefore(nextAttemptAt)) {
            return;
        }
        refreshQuietly();
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> "[PromptOn] snapshot refresh failed: " + e.getMessage());
        }
    }

    /** Fetches once, now. Returns what happened; never throws for a server or network failure. */
    RefreshResult refresh() {
        if (!config.remoteEnabled()) {
            return RefreshResult.SKIPPED;
        }
        if (!refreshing.compareAndSet(false, true)) {
            return RefreshResult.SKIPPED;
        }
        try {
            return fetch();
        } finally {
            refreshing.set(false);
            synchronized (arrival) {
                attemptsFinished.incrementAndGet();
                arrival.notifyAll();
            }
        }
    }

    private RefreshResult fetch() {
        Entry previous = current.get();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json");
        headers.put("user-agent", config.userAgent());
        headers.put("authorization", "Bearer " + config.apiKey());
        if (previous != null && previous.etag() != null) {
            headers.put("if-none-match", previous.etag());
        }
        String url = config.baseUrl() + "/use-cases?environment="
                + URLEncoder.encode(config.environment(), StandardCharsets.UTF_8);

        HttpResponse response;
        try {
            httpCalls.incrementAndGet();
            response = config.httpClient().send(new HttpRequest(
                    "GET", url, headers, null, config.requestTimeout()));
        } catch (IOException | RuntimeException e) {
            return failed("transport: " + e, null);
        }

        int status = response.status();
        if (status == 304) {
            failures = 0;
            nextAttemptAt = Instant.now().plus(config.cacheTtl());
            if (previous != null
                    && (previous.source() != Source.REMOTE || previous.staleSince() != null)) {
                current.set(new Entry(previous.useCaseDocument(), previous.etag(), previous.lastModified(),
                        Source.REMOTE, previous.fetchedAt(), null, previous.rawJson()));
            }
            return RefreshResult.NOT_MODIFIED;
        }
        if (status == 200) {
            UseCaseDocument snapshot;
            try {
                snapshot = UseCaseDocument.parse(response.body());
            } catch (RuntimeException e) {
                return failed("undecodable snapshot: " + e.getMessage(), null);
            }
            String mismatch = mismatch(snapshot);
            if (mismatch != null) {
                return failed(mismatch, null);
            }
            Entry entry = new Entry(
                    snapshot,
                    response.header("etag"),
                    response.header("last-modified"),
                    Source.REMOTE,
                    Instant.now(),
                    null,
                    response.body());
            current.set(entry);
            failures = 0;
            nextAttemptAt = Instant.now().plus(config.cacheTtl());
            persist(entry);
            LOG.log(Level.FINE, () -> "[PromptOn] snapshot updated, etag=" + entry.etag());
            return RefreshResult.UPDATED;
        }
        if (status == 429) {
            return failed("rate limited", Backoff.retryAfterFrom(response));
        }
        return failed("HTTP " + status + ": " + shorten(response.body()),
                Backoff.retryAfterFrom(response));
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }

    private RefreshResult failed(String reason, Duration retryAfter) {
        failures++;
        Duration wait = retryAfter != null
                ? retryAfter
                : Backoff.exponential(config.cacheTtl(), failures, config.maxBackoff());
        nextAttemptAt = Instant.now().plus(wait);
        Entry previous = current.get();
        if (previous != null && previous.staleSince() == null) {
            current.set(new Entry(previous.useCaseDocument(), previous.etag(), previous.lastModified(),
                    previous.source(), previous.fetchedAt(), Instant.now(), previous.rawJson()));
        }
        int attempt = failures;
        LOG.log(Level.WARNING, () -> "[PromptOn] snapshot refresh failed (attempt " + attempt + "): "
                + reason + "; serving the cached document, next attempt in " + wait.toSeconds() + "s");
        return RefreshResult.FAILED;
    }

    /** Installs a document the application supplied. */
    void put(UseCaseDocument snapshot, Source source, String rawJson) {
        current.set(new Entry(snapshot, null, null, source, Instant.now(), null, rawJson));
        synchronized (arrival) {
            arrival.notifyAll();
        }
    }

    /** Clears the document in memory. */
    void clear() {
        current.set(null);
    }

    /** How many HTTP requests this store has made, for tests and diagnostics. */
    long httpCalls() {
        return httpCalls.get();
    }

    // ---------------------------------------------------------------------
    // local tiers

    private void loadLocal() {
        if (loadFile(config.diskCachePath(), Source.DISK)) {
            return;
        }
        loadFile(config.bundlePath(), Source.BUNDLE);
    }

    private boolean loadFile(Path path, Source source) {
        DiskCache.Stored stored = DiskCache.read(path);
        if (stored == null) {
            return false;
        }
        UseCaseDocument snapshot;
        try {
            snapshot = UseCaseDocument.parse(stored.body());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "[PromptOn] ignoring the " + source.wireName()
                    + " snapshot at " + path + ": " + e.getMessage());
            return false;
        }
        String mismatch = mismatch(snapshot);
        if (mismatch != null) {
            LOG.log(Level.WARNING, () -> "[PromptOn] ignoring the " + source.wireName()
                    + " snapshot at " + path + ": " + mismatch);
            return false;
        }
        current.set(new Entry(
                snapshot,
                Json.stringAt(stored.meta(), "etag"),
                Json.stringAt(stored.meta(), "last_modified"),
                source,
                parseInstant(Json.stringAt(stored.meta(), "fetched_at")),
                Instant.now(),
                stored.body()));
        LOG.log(Level.INFO, () -> "[PromptOn] loaded the " + source.wireName() + " snapshot from " + path);
        return true;
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    /** Why this document must not be used here, or {@code null} when it may. */
    private String mismatch(UseCaseDocument snapshot) {
        String environment = snapshot.environment();
        if (environment != null && !environment.equals(config.environment())) {
            return "it describes environment \"" + environment + "\", not \"" + config.environment()
                    + "\"";
        }
        String project = snapshot.project();
        if (project != null && config.project() != null && !project.equals(config.project())) {
            return "it belongs to project \"" + project + "\", not \"" + config.project() + "\"";
        }
        return null;
    }

    private void persist(Entry entry) {
        Path path = config.diskCachePath();
        if (path == null || entry.rawJson() == null) {
            return;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("etag", entry.etag());
        meta.put("last_modified", entry.lastModified());
        meta.put("environment", entry.useCaseDocument().environment());
        meta.put("project", entry.useCaseDocument().project());
        meta.put("fetched_at", entry.fetchedAt().toString());
        if (!DiskCache.write(path, entry.rawJson(), meta)) {
            LOG.warning("[PromptOn] could not write the disk cache at " + path);
        }
    }

    /** Writes the document in memory, with its sidecar, to {@code path}. */
    void exportTo(Path path) {
        Entry entry = current.get();
        if (entry == null) {
            throw new PromptOnException("there is no use-case document in memory to export");
        }
        String body = entry.rawJson();
        if (body == null) {
            throw new PromptOnException(
                    "the use-case document in memory has no original document to export");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("etag", entry.etag());
        meta.put("last_modified", entry.lastModified());
        meta.put("environment", entry.useCaseDocument().environment());
        meta.put("project", entry.useCaseDocument().project());
        meta.put("exported_at", Instant.now().toString());
        if (!DiskCache.write(path, body, meta)) {
            throw new PromptOnException("could not write the use-case document bundle to " + path);
        }
    }

    /** What a health endpoint should report. */
    UseCaseDocumentInfo info() {
        Entry entry = current.get();
        if (entry == null) {
            return UseCaseDocumentInfo.NONE;
        }
        long age = Math.max(0, Duration.between(entry.fetchedAt(), Instant.now()).toSeconds());
        return new UseCaseDocumentInfo(
                entry.source(),
                entry.etag(),
                entry.lastModified(),
                entry.fetchedAt(),
                entry.source() != Source.REMOTE || entry.staleSince() != null,
                age,
                entry.useCaseDocument().project(),
                entry.useCaseDocument().environment());
    }

    @Override
    public void close() {
        ScheduledExecutorService pollExecutor;
        ScheduledExecutorService refreshExecutor;
        synchronized (scheduleLock) {
            pollExecutor = poller;
            refreshExecutor = refresher;
            poller = null;
            refresher = null;
        }
        stop(pollExecutor);
        stop(refreshExecutor);
    }

    /** Stops an executor and waits briefly, so no refresh is still writing the disk cache. */
    private static void stop(ScheduledExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
