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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Demand-driven, per-prompt configuration store. */
final class SnapshotStore implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SnapshotStore.class.getName());
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration CONFIG_TTL = Duration.ofSeconds(10);
    private static volatile Supplier<Instant> clock = Instant::now;

    /** One loaded document and where it came from. */
    record Entry(
            UseCaseDocument useCaseDocument,
            String etag,
            String lastModified,
            Source source,
            Instant fetchedAt,
            Instant staleSince,
            String rawJson) {}

    private record FetchOutcome(
            RefreshResult result,
            Entry entry,
            String reason,
            Instant startedAt,
            Instant deadline) {
        static FetchOutcome updated(Entry entry, Instant startedAt, Instant deadline) {
            return new FetchOutcome(RefreshResult.UPDATED, entry, null, startedAt, deadline);
        }

        static FetchOutcome notModified(Entry entry, Instant startedAt, Instant deadline) {
            return new FetchOutcome(RefreshResult.NOT_MODIFIED, entry, null, startedAt, deadline);
        }

        static FetchOutcome failed(String reason, Instant startedAt, Instant deadline) {
            return new FetchOutcome(RefreshResult.FAILED, null, reason, startedAt, deadline);
        }
    }

    private static final class KeyState {
        Entry entry;
        Instant lastAttemptAt;
        CompletableFuture<FetchOutcome> inFlight;
        Instant inFlightDeadline;
    }

    private final PromptOnConfig config;
    private final ConcurrentHashMap<String, KeyState> states = new ConcurrentHashMap<>();
    private final AtomicReference<Entry> localDocument = new AtomicReference<>();
    private final AtomicReference<Entry> lastServed = new AtomicReference<>();
    private final AtomicLong httpCalls = new AtomicLong();
    private final Object diskLock = new Object();
    private final ExecutorService fetchExecutor =
            Executors.newCachedThreadPool(new FetchThreadFactory());
    private volatile boolean noKeyLogged;

    SnapshotStore(PromptOnConfig config) {
        this.config = config;
    }

    /** Loads disk or bundle. It never starts a remote fetch or a polling timer. */
    void start() {
        if (config.mode() != Mode.TEST) {
            loadLocal();
        }
        if (!config.remoteEnabled() && !noKeyLogged) {
            noKeyLogged = true;
            LOG.log(Level.INFO, () -> "[PromptOn] " + whyNoRemote() + "; resolving from "
                    + (localDocument.get() == null ? "nothing" : localDocument.get().source().wireName())
                    + " only, and never contacting the server");
        }
    }

    private static Instant now() {
        return clock.get();
    }

    static void useClockForTests(Supplier<Instant> replacement) {
        clock = replacement == null ? Instant::now : replacement;
    }

    private String whyNoRemote() {
        if (config.mode() != Mode.LIVE) {
            return config.mode().name().toLowerCase(java.util.Locale.ROOT) + " mode";
        }
        return "no API key configured";
    }

    Entry require(String key) {
        KeyState state = states.computeIfAbsent(key, ignored -> new KeyState());
        Entry local = localFor(key);
        CompletableFuture<FetchOutcome> future;
        Instant deadline;
        Instant now = now();
        synchronized (state) {
            if (state.entry == null && local != null) {
                state.entry = local;
                lastServed.set(local);
            }
            if (isFresh(state.entry, now)) {
                lastServed.set(state.entry);
                return state.entry;
            }
            if (!config.remoteEnabled()) {
                return cachedOrThrow(state.entry);
            }
            if (state.inFlight != null && !now.isBefore(state.inFlightDeadline)) {
                state.inFlight = null;
                state.inFlightDeadline = null;
            }
            if (state.inFlight == null && attemptDue(state.lastAttemptAt, now)) {
                startFetchLocked(key, state, now);
            }
            future = state.inFlight;
            deadline = state.inFlightDeadline;
            if (future == null) {
                return cachedOrThrow(state.entry);
            }
        }

        awaitSharedFetch(future, deadline);
        synchronized (state) {
            return cachedOrThrow(state.entry);
        }
    }

    private Entry cachedOrThrow(Entry entry) {
        if (entry != null) {
            lastServed.set(entry);
            return entry;
        }
        throw UseCaseException.of(UseCaseException.Reason.NOT_READY, null);
    }

    private boolean isFresh(Entry entry, Instant now) {
        return entry != null
                && entry.source() == Source.REMOTE
                && entry.staleSince() == null
                && Duration.between(entry.fetchedAt(), now).compareTo(CONFIG_TTL) < 0;
    }

    private boolean attemptDue(Instant lastAttemptAt, Instant now) {
        return lastAttemptAt == null
                || Duration.between(lastAttemptAt, now).compareTo(CONFIG_TTL) >= 0;
    }

    private void startFetchLocked(String key, KeyState state, Instant startedAt) {
        state.lastAttemptAt = startedAt;
        Instant deadline = startedAt.plus(fetchTimeout());
        Entry previous = state.entry;
        CompletableFuture<FetchOutcome> future = new CompletableFuture<>();
        state.inFlight = future;
        state.inFlightDeadline = deadline;
        fetchExecutor.execute(() -> {
            FetchOutcome outcome;
            Throwable error = null;
            try {
                outcome = fetch(key, previous, startedAt, deadline);
            } catch (Throwable t) {
                error = t;
                outcome = FetchOutcome.failed(t.getMessage(), startedAt, deadline);
            }
            finishFetch(key, state, future, outcome, error);
            future.complete(outcome);
        });
    }

    private void finishFetch(
            String key,
            KeyState state,
            CompletableFuture<FetchOutcome> future,
            FetchOutcome outcome,
            Throwable error) {
        Instant completedAt = now();
        synchronized (state) {
            if (state.inFlight == future) {
                state.inFlight = null;
                state.inFlightDeadline = null;
            }
            FetchOutcome resolved = outcome == null
                    ? FetchOutcome.failed(error == null ? "unknown fetch failure" : error.getMessage(),
                            completedAt, completedAt)
                    : outcome;
            if (completedAt.isAfter(resolved.deadline())) {
                markStale(state, completedAt);
                return;
            }
            if (resolved.entry() != null) {
                state.entry = resolved.entry();
                lastServed.set(resolved.entry());
                if (resolved.result() == RefreshResult.UPDATED) {
                    persist(key, resolved.entry());
                    LOG.log(Level.FINE, () -> "[PromptOn] prompt " + key
                            + " config updated, etag=" + resolved.entry().etag());
                }
                return;
            }
            markStale(state, completedAt);
            LOG.log(Level.WARNING, () -> "[PromptOn] prompt " + key
                    + " config fetch failed: " + resolved.reason()
                    + "; serving the cached value until the next demand attempt is due");
        }
    }

    private void awaitSharedFetch(CompletableFuture<FetchOutcome> future, Instant deadline) {
        long waitMillis = Duration.between(now(), deadline).toMillis();
        if (waitMillis <= 0) {
            return;
        }
        try {
            future.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // The late result is ignored by finishFetch; the caller uses stale cache now.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // finishFetch records the failure. The caller still falls back to cache.
        }
    }

    private FetchOutcome fetch(String key, Entry previous, Instant startedAt, Instant deadline) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json");
        headers.put("user-agent", config.userAgent());
        headers.put("authorization", "Bearer " + config.apiKey());
        if (previous != null && previous.etag() != null) {
            headers.put("if-none-match", previous.etag());
        }
        String url = config.baseUrl() + "/prompts/" + pathEncode(key) + "?environment="
                + URLEncoder.encode(config.environment(), StandardCharsets.UTF_8);

        HttpResponse response;
        try {
            httpCalls.incrementAndGet();
            response = config.httpClient().send(new HttpRequest(
                    "GET", url, headers, null, fetchTimeout()));
        } catch (IOException | RuntimeException e) {
            return FetchOutcome.failed("transport: " + e, startedAt, deadline);
        }

        if (now().isAfter(deadline)) {
            return FetchOutcome.failed("deadline exceeded", startedAt, deadline);
        }
        int status = response.status();
        if (status == 304) {
            if (previous == null) {
                return FetchOutcome.failed("304 without a cached value", startedAt, deadline);
            }
            Entry refreshed = new Entry(previous.useCaseDocument(), previous.etag(), previous.lastModified(),
                    Source.REMOTE, now(), null, previous.rawJson());
            return FetchOutcome.notModified(refreshed, startedAt, deadline);
        }
        if (status == 200) {
            UseCaseDocument document;
            try {
                document = UseCaseDocument.parse(response.body());
            } catch (RuntimeException e) {
                return FetchOutcome.failed("undecodable prompt document: " + e.getMessage(),
                        startedAt, deadline);
            }
            String mismatch = mismatch(document, key);
            if (mismatch != null) {
                return FetchOutcome.failed(mismatch, startedAt, deadline);
            }
            Entry entry = new Entry(
                    document,
                    response.header("etag"),
                    response.header("last-modified"),
                    Source.REMOTE,
                    now(),
                    null,
                    response.body());
            return FetchOutcome.updated(entry, startedAt, deadline);
        }
        return FetchOutcome.failed("HTTP " + status + ": " + shorten(response.body()),
                startedAt, deadline);
    }

    private Duration fetchTimeout() {
        return config.requestTimeout().compareTo(FETCH_TIMEOUT) < 0
                ? config.requestTimeout()
                : FETCH_TIMEOUT;
    }

    private static String pathEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Entry localFor(String key) {
        Entry local = localDocument.get();
        if (local == null || !local.useCaseDocument().useCases().containsKey(key)) {
            return null;
        }
        return local;
    }

    private void markStale(KeyState state, Instant when) {
        if (state.entry != null && state.entry.staleSince() == null) {
            state.entry = new Entry(state.entry.useCaseDocument(), state.entry.etag(),
                    state.entry.lastModified(), state.entry.source(), state.entry.fetchedAt(),
                    when, state.entry.rawJson());
        }
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }

    /** Explicit bulk refresh is intentionally disabled in the demand-driven runtime. */
    RefreshResult refresh() {
        return RefreshResult.SKIPPED;
    }

    /** Installs a document the application supplied. */
    void put(UseCaseDocument document, Source source, String rawJson) {
        Instant now = now();
        Entry entry = new Entry(document, null, null, source, now,
                source == Source.REMOTE ? null : now, rawJson);
        localDocument.set(entry);
        lastServed.set(entry);
        states.clear();
    }

    /** Clears the document in memory. */
    void clear() {
        localDocument.set(null);
        lastServed.set(null);
        states.clear();
    }

    /** How many HTTP requests this store has made, for tests and diagnostics. */
    long httpCalls() {
        return httpCalls.get();
    }

    Entry current() {
        return lastServed.get();
    }

    Entry currentFor(String key) {
        KeyState state = states.get(key);
        if (state != null) {
            synchronized (state) {
                if (state.entry != null) {
                    return state.entry;
                }
            }
        }
        return localFor(key);
    }

    private void loadLocal() {
        boolean loadedDiskEntries = loadDiskEntries(config.diskCachePath());
        if (!loadedDiskEntries && loadFile(config.diskCachePath(), Source.DISK)) {
            return;
        }
        loadFile(config.bundlePath(), Source.BUNDLE);
    }

    @SuppressWarnings("unchecked")
    private boolean loadDiskEntries(Path path) {
        DiskCache.Stored stored = DiskCache.read(path);
        if (stored == null) {
            return false;
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(stored.body());
        } catch (RuntimeException e) {
            return false;
        }
        Map<String, Object> entries = Json.mapAt(root, "entries");
        if (entries == null) {
            return false;
        }
        boolean loaded = false;
        for (Map.Entry<String, Object> item : entries.entrySet()) {
            if (!(item.getValue() instanceof Map<?, ?> rawEntry)) {
                continue;
            }
            Map<String, Object> entryObject = (Map<String, Object>) rawEntry;
            String body = Json.stringAt(entryObject, "body");
            Map<String, Object> meta = Json.mapAt(entryObject, "meta");
            if (body == null || meta == null) {
                continue;
            }
            UseCaseDocument document;
            try {
                document = UseCaseDocument.parse(body);
            } catch (RuntimeException e) {
                continue;
            }
            String mismatch = mismatch(document, item.getKey());
            if (mismatch != null) {
                LOG.log(Level.WARNING, () -> "[PromptOn] ignoring the disk prompt document for "
                        + item.getKey() + " at " + path + ": " + mismatch);
                continue;
            }
            Instant fetchedAt = parseInstant(Json.stringAt(meta, "fetched_at"));
            Entry entry = new Entry(document, Json.stringAt(meta, "etag"),
                    Json.stringAt(meta, "last_modified"), Source.DISK, fetchedAt,
                    now(), body);
            KeyState state = states.computeIfAbsent(item.getKey(), ignored -> new KeyState());
            synchronized (state) {
                state.entry = entry;
            }
            lastServed.compareAndSet(null, entry);
            loaded = true;
        }
        if (loaded) {
            LOG.log(Level.INFO, () -> "[PromptOn] loaded per-prompt disk cache entries from " + path);
        }
        return loaded;
    }

    private boolean loadFile(Path path, Source source) {
        DiskCache.Stored stored = DiskCache.read(path);
        if (stored == null) {
            return false;
        }
        UseCaseDocument document;
        try {
            document = UseCaseDocument.parse(stored.body());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "[PromptOn] ignoring the " + source.wireName()
                    + " prompt document at " + path + ": " + e.getMessage());
            return false;
        }
        String mismatch = mismatch(document, null);
        if (mismatch != null) {
            LOG.log(Level.WARNING, () -> "[PromptOn] ignoring the " + source.wireName()
                    + " prompt document at " + path + ": " + mismatch);
            return false;
        }
        Instant now = now();
        Entry entry = new Entry(
                document,
                Json.stringAt(stored.meta(), "etag"),
                Json.stringAt(stored.meta(), "last_modified"),
                source,
                parseInstant(Json.stringAt(stored.meta(), "fetched_at")),
                now,
                stored.body());
        localDocument.set(entry);
        lastServed.set(entry);
        LOG.log(Level.INFO, () -> "[PromptOn] loaded the " + source.wireName()
                + " prompt document from " + path);
        return true;
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return now();
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return now();
        }
    }

    private String mismatch(UseCaseDocument document, String requestedKey) {
        String environment = document.environment();
        if (environment != null && !environment.equals(config.environment())) {
            return "it describes environment \"" + environment + "\", not \"" + config.environment()
                    + "\"";
        }
        String project = document.project();
        if (project != null && config.project() != null && !project.equals(config.project())) {
            return "it belongs to project \"" + project + "\", not \"" + config.project() + "\"";
        }
        if (requestedKey != null && !document.useCases().containsKey(requestedKey)) {
            return "it does not contain requested prompt \"" + requestedKey + "\"";
        }
        return null;
    }

    private void persist(String key, Entry entry) {
        Path path = config.diskCachePath();
        if (path == null || entry.rawJson() == null) {
            return;
        }
        synchronized (diskLock) {
            Map<String, Object> root = readDiskRoot(path);
            Map<String, Object> entries = Json.mapAt(root, "entries");
            if (entries == null) {
                entries = new LinkedHashMap<>();
                root.put("entries", entries);
            }
            root.put("prompton_sdk_cache_version", 1);

            Map<String, Object> meta = metaFor(entry);
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("body", entry.rawJson());
            saved.put("meta", meta);
            entries.put(key, saved);

            Map<String, Object> sidecar = new LinkedHashMap<>();
            sidecar.put("environment", config.environment());
            sidecar.put("project", config.project());
            sidecar.put("fetched_at", entry.fetchedAt().toString());
            if (!DiskCache.write(path, Json.write(root), sidecar)) {
                LOG.warning("[PromptOn] could not write the disk cache at " + path);
            }
        }
    }

    private Map<String, Object> readDiskRoot(Path path) {
        DiskCache.Stored stored = DiskCache.read(path);
        if (stored == null) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> root = Json.parseObject(stored.body());
            if (Json.mapAt(root, "entries") != null) {
                return root;
            }
        } catch (RuntimeException ignored) {
            // Legacy or corrupt files are replaced by the per-prompt cache envelope.
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> metaFor(Entry entry) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("etag", entry.etag());
        meta.put("last_modified", entry.lastModified());
        meta.put("environment", entry.useCaseDocument().environment());
        meta.put("project", entry.useCaseDocument().project());
        meta.put("fetched_at", entry.fetchedAt().toString());
        return meta;
    }

    /** Writes the most recently served document, with its sidecar, to {@code path}. */
    void exportTo(Path path) {
        Entry entry = lastServed.get();
        if (entry == null) {
            throw new PromptOnException("there is no prompt document in memory to export");
        }
        String body = entry.rawJson();
        if (body == null) {
            throw new PromptOnException(
                    "the prompt document in memory has no original document to export");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("etag", entry.etag());
        meta.put("last_modified", entry.lastModified());
        meta.put("environment", entry.useCaseDocument().environment());
        meta.put("project", entry.useCaseDocument().project());
        meta.put("exported_at", now().toString());
        if (!DiskCache.write(path, body, meta)) {
            throw new PromptOnException("could not write the prompt document bundle to " + path);
        }
    }

    /** What a health endpoint should report. */
    UseCaseDocumentInfo info() {
        Entry entry = lastServed.get();
        if (entry == null) {
            return UseCaseDocumentInfo.NONE;
        }
        long age = Math.max(0, Duration.between(entry.fetchedAt(), now()).toSeconds());
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
        fetchExecutor.shutdownNow();
    }

    private static final class FetchThreadFactory implements ThreadFactory {
        private final AtomicLong count = new AtomicLong();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "prompton-config-fetch-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
