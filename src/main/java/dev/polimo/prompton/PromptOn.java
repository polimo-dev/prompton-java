package dev.polimo.prompton;

import dev.polimo.prompton.internal.Json;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The PromptOn client: load a use case, render its prompt, call your provider yourself, log what
 * happened.
 *
 * <pre>{@code
 * try (PromptOn prompton = PromptOn.create()) {
 *     UseCase useCase = prompton.useCase("support_reply");
 *     List<Message> messages = useCase.messages(Map.of("question", question));
 *     String answer = useCase.track(TrackMeta.builder()
 *             .inputMessages(messages).variables(Map.of("question", question)).build(),
 *         () -> {
 *             MyReply reply = myProvider.chat(useCase.model(), messages, useCase.params());
 *             return ProviderResult.ok(reply.text(), Result.builder()
 *                     .content(reply.text()).finishReason(reply.finishReason()).build());
 *         });
 * }
 * }</pre>
 *
 * <p>PromptOn is never in the request path: the provider call above is yours, with your key and
 * your HTTP client. {@link #useCase(String)} reads a use-case document held in memory, refreshed in the background,
 * mirrored to disk and backed by a file you can ship inside the application — so if PromptOn is
 * unreachable your app keeps generating on the last configuration it saw.
 *
 * <p>One instance owns a poll loop and a log-sender thread, so build one per process and
 * {@link #close()} it on shutdown; it is safe to share across threads.
 */
public final class PromptOn implements AutoCloseable {

    private final PromptOnConfig config;
    private final SnapshotStore snapshots;
    private final ResolveClient resolveClient;
    private final LogBuffer buffer;
    private final List<Map<String, Object>> capturedLogs = new CopyOnWriteArrayList<>();

    private PromptOn(PromptOnConfig config) {
        this.config = config;
        this.snapshots = new SnapshotStore(config);
        this.resolveClient = new ResolveClient(config);
        this.buffer = config.mode() == Mode.TEST ? null : new LogBuffer(config);
        this.snapshots.start();
    }

    /** A client configured entirely from {@code PTN_HOST}, {@code PTN_API_KEY} and their defaults. */
    public static PromptOn create() {
        return create(PromptOnConfig.builder().build());
    }

    /** A client with an explicit configuration. */
    public static PromptOn create(PromptOnConfig config) {
        return new PromptOn(config);
    }

    /** A configuration builder; call {@link #create(PromptOnConfig)} with what it builds. */
    public static PromptOnConfig.Builder builder() {
        return PromptOnConfig.builder();
    }

    /** The configuration in force. */
    public PromptOnConfig config() {
        return config;
    }

    // ---------------------------------------------------------------------
    // use cases

    /** The use case with its {@code default} prompt. */
    public UseCase useCase(String useCase) {
        return useCase(useCase, null);
    }

    /**
     * The use case with a named prompt.
     *
     * <p>Reads the use-case document in memory: no HTTP call, no blocking, unless nothing has been loaded
     * yet and the very first fetch is still in flight.
     *
     * @param useCase the use case key
     * @param promptName the prompt name, or {@code null} for {@code default}
     * @return what to send to the provider
     * @throws UseCaseException when the use case, its deployment or that prompt name is not in
     *     the use-case document, or when nothing is cached and PromptOn is unreachable
     */
    public UseCase useCase(String useCase, String promptName) {
        SnapshotStore.Entry entry = snapshots.require();
        return Resolver.resolve(
                entry.useCaseDocument(), useCase, promptName, entry.source(), entry.etag())
                .attachTo(this);
    }

    /** Every prompt name the live deployment of {@code useCase} pins, sorted. */
    public List<String> promptNames(String useCase) {
        return snapshots.require().useCaseDocument().promptNames(useCase);
    }

    /**
     * Resolves on the server with {@code POST /use-cases/{key}/prompt}, returning the raw templates.
     *
     * <p>The answer is cached for the use-case document TTL per use case, prompt and environment; render it
     * locally with {@link UseCase#messages(Map)} or {@link UseCase#text(Map)}. Use it as a smoke
     * test or on a cold, low-traffic path,
     * never once per request in a hot loop.
     *
     * @param useCase the use case key
     * @param promptName the prompt name, or {@code null} for {@code default}
     * @return the use case as the server rendered it
     */
    public UseCase useCaseRemote(String useCase, String promptName) {
        return resolveClient.resolve(useCase, promptName, null).attachTo(this);
    }

    /**
     * Resolves and renders on the server in one round trip.
     *
     * <p>Not cached, because the answer depends on the variables. The returned use case's
     * {@link UseCase#messages()} and {@link UseCase#textTemplate()} are already rendered.
     *
     * @param useCase the use case key
     * @param promptName the prompt name, or {@code null} for {@code default}
     * @param variables the values the template reads
     * @return the use case, with its prompt rendered
     */
    public UseCase useCaseRemote(
            String useCase, String promptName, Map<String, Object> variables) {
        return resolveClient.resolve(
                useCase, promptName, variables == null ? Map.of() : variables)
                .attachTo(this);
    }

    // ---------------------------------------------------------------------
    // monitoring logs

    /** A UUIDv7 to use as a record id, issued before the provider call. */
    public String newLogId() {
        return UuidV7.generate();
    }

    /**
     * Queues one monitoring log and returns immediately.
     *
     * <p>Fills in {@code id} and {@code sdk} when they are unset, applies the use case's payload
     * policy — sampling, truncation, hashing, your redact hook — and hands the record to the batch
     * buffer. It never throws for a transport reason: a log that cannot be sent is counted, not
     * raised.
     *
     * @param record the record to send
     * @throws PromptOnException when {@code use_case}, {@code model}, {@code status} or
     *     {@code started_at} is missing
     */
    public void log(LogRecord record) {
        record.validate();
        Map<String, Object> map = record.toMap();
        map.putIfAbsent("id", UuidV7.generate());
        map.putIfAbsent("sdk", sdkIdentity());

        PayloadPolicy policy = record.payloadPolicy();
        if (policy == null) {
            policy = policyFor(record.key());
        }
        Map<String, Object> prepared = Payload.apply(
                map, policy, new Payload.Options(config.hashEndUser(), config.redact()));

        String environment = record.environment() == null ? config.environment() : record.environment();
        if (config.mode() == Mode.TEST) {
            capturedLogs.add(prepared);
            return;
        }
        buffer.enqueue(prepared, environment);
    }

    /** Sends everything queued and waits, up to the configured shutdown timeout. */
    public FlushResult flush() {
        return flush(config.shutdownFlushTimeout());
    }

    /**
     * Sends everything queued and waits for the result — for a script, a test, or a shutdown hook.
     *
     * @param timeout how long to keep sending
     * @return what was sent and what is left
     */
    public FlushResult flush(Duration timeout) {
        if (buffer == null) {
            return FlushResult.EMPTY;
        }
        return buffer.flush(timeout);
    }

    /** A current view of the log queue and its counters. */
    public LogStats logStats() {
        return buffer == null
                ? new LogStats(capturedLogs.size(), 0, capturedLogs.size(), 0, 0, 0, 0)
                : buffer.stats();
    }

    /**
     * Times a provider call, builds the monitoring log from it, and queues it.
     *
     * <p>Whatever your call returns comes back unchanged. An exception thrown inside it is logged
     * as {@code status: "error"} with {@code error.kind: "app"} and then rethrown unchanged, so the
     * wrapper never swallows a failure or changes control flow.
     *
     * @param useCase the use case this call used
     * @param meta what went in, and how to find this call again later
     * @param call your provider call
     * @param <T> whatever your own code wants back
     * @return {@link ProviderResult#value()}
     * @throws Exception whatever your call threw
     */
    <T> T track(UseCase useCase, TrackMeta meta, ProviderCall<T> call)
            throws Exception {
        TrackMeta safeMeta = meta == null ? TrackMeta.empty() : meta;
        String id = safeMeta.id() == null ? UuidV7.generate() : safeMeta.id();
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        try {
            ProviderResult<T> result = call.call();
            long latency = elapsedMillis(start);
            log(buildRecord(useCase, safeMeta, id, startedAt, latency,
                    result.failed() ? LogRecord.Status.ERROR : LogRecord.Status.OK,
                    result.result(), result.error()));
            return result.value();
        } catch (Exception | Error e) {
            long latency = elapsedMillis(start);
            log(buildRecord(useCase, safeMeta, id, startedAt, latency,
                    LogRecord.Status.ERROR, null,
                    LogError.of(ErrorKind.APP, describe(e))));
            throw e;
        }
    }

    /**
     * {@link #track(UseCase, TrackMeta, ProviderCall)} for a call that throws no checked exception.
     *
     * @param useCase the use case this call used
     * @param meta what went in, and how to find this call again later
     * @param call your provider call
     * @param <T> whatever your own code wants back
     * @return {@link ProviderResult#value()}
     */
    <T> T trackUnchecked(
            UseCase useCase, TrackMeta meta, UncheckedProviderCall<T> call) {
        try {
            return track(useCase, meta, call::call);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new PromptOnException("provider call failed: " + e.getMessage(), e);
        }
    }

    /** A provider call that throws nothing checked. */
    @FunctionalInterface
    public interface UncheckedProviderCall<T> {
        /** @return the result, told apart into success and failure */
        ProviderResult<T> call();
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private LogRecord buildRecord(
            UseCase useCase,
            TrackMeta meta,
            String id,
            Instant startedAt,
            long latencyMs,
            LogRecord.Status status,
            Result result,
            LogError error) {
        LogRecord.Builder builder = LogRecord.builder()
                .useCase(useCase)
                .id(id)
                .status(status)
                .startedAt(startedAt)
                .latencyMs(latencyMs)
                .error(error)
                .traceId(meta.traceId())
                .sequence(meta.sequence())
                .endUserRef(meta.endUserRef())
                .environment(config.environment())
                .sdk(sdkIdentity());

        if (meta.params() != null && useCase != null) {
            builder.params(Params.merge(useCase.params(), meta.params()));
        } else if (meta.params() != null) {
            builder.params(meta.params());
        }

        Map<String, Object> input = new LinkedHashMap<>();
        if (meta.variables() != null) {
            input.put("variables", meta.variables());
        }
        if (meta.inputMessages() != null) {
            List<Object> messages = new ArrayList<>(meta.inputMessages().size());
            for (Message message : meta.inputMessages()) {
                messages.add(message.toMap());
            }
            input.put("messages", messages);
        }
        if (meta.inputText() != null) {
            input.put("text", meta.inputText());
        }
        if (!input.isEmpty()) {
            builder.input(input);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (meta.metadata() != null) {
            metadata.putAll(meta.metadata());
        }
        if (result != null) {
            builder.output(result.outputMap());
            builder.finishReason(result.finishReason());
            builder.stopKind(result.stopKind());
            builder.usage(result.usage());
            builder.modelUsed(result.modelUsed());
            builder.upstreamProvider(result.upstreamProvider());
            if (result.byok() != null) {
                metadata.put("is_byok", result.byok());
            }
        }
        builder.metadata(metadata);
        builder.context(meta.context() == null ? Map.of() : meta.context());
        return builder.build();
    }

    private Map<String, Object> sdkIdentity() {
        Map<String, Object> sdk = new LinkedHashMap<>();
        sdk.put("name", PromptOnConfig.SDK_NAME);
        sdk.put("version", PromptOnConfig.SDK_VERSION);
        return sdk;
    }

    private PayloadPolicy policyFor(String useCase) {
        SnapshotStore.Entry entry = snapshots.current();
        if (entry == null || useCase == null) {
            return config.payloadDefaults();
        }
        UseCaseDocument.UseCase found = entry.useCaseDocument().useCases().get(useCase);
        return found == null || found.payloadPolicy() == null
                ? config.payloadDefaults()
                : found.payloadPolicy();
    }

    // ---------------------------------------------------------------------
    // use-case document control

    /** Where the configuration in memory came from and how old it is. */
    public UseCaseDocumentInfo useCaseDocumentInfo() {
        return snapshots.info();
    }

    /**
     * Fetches the use-case document once, now, and waits for it — for a script, a warm-up or a test.
     *
     * @return what the refresh did; a failure is reported, not thrown
     */
    public RefreshResult refresh() {
        return snapshots.refresh();
    }

    /**
     * Writes the document in memory, with its {@code .meta.json} sidecar, to {@code path}.
     *
     * <p>This is how the bundled use-case document is built: run it in CI and commit both files, one per
     * environment, so a cold start with no disk cache and no network still renders.
     *
     * @param path where to write it
     */
    public void exportUseCaseDocument(Path path) {
        snapshots.exportTo(path);
    }

    /** Installs a use-case document the application supplied, as {@link Source#MANUAL}. */
    public void putUseCaseDocument(String json) {
        snapshots.put(UseCaseDocument.parse(json), Source.MANUAL, json);
    }

    /** Installs an already-parsed use-case document, as {@link Source#MANUAL}. */
    public void putUseCaseDocument(UseCaseDocument document) {
        snapshots.put(document, Source.MANUAL, null);
    }

    /**
     * Installs a use-case document and says where it is to be reported as coming from — for a test that
     * needs a record to read {@code source: "remote"}.
     *
     * @param document the document to install
     * @param source what reads against it report
     */
    public void putUseCaseDocument(UseCaseDocument document, Source source) {
        snapshots.put(document, source, null);
    }

    /** The use-case document in memory, or {@code null} when nothing has been loaded. */
    public UseCaseDocument useCaseDocument() {
        SnapshotStore.Entry entry = snapshots.current();
        return entry == null ? null : entry.useCaseDocument();
    }

    // ---------------------------------------------------------------------
    // test mode

    /**
     * The records {@link Mode#TEST} captured instead of sending, oldest first.
     *
     * @return an unmodifiable view; empty outside test mode
     */
    public List<Map<String, Object>> capturedLogs() {
        return Collections.unmodifiableList(new ArrayList<>(capturedLogs));
    }

    /** Forgets every captured record. */
    public void clearCapturedLogs() {
        capturedLogs.clear();
    }

    /** The last captured record, as canonical JSON — handy in an assertion message. */
    public String lastCapturedLogAsJson() {
        return capturedLogs.isEmpty()
                ? "<none>"
                : Json.canonical(capturedLogs.get(capturedLogs.size() - 1));
    }

    /** Stops the poll loop and drains the log buffer, best effort. */
    @Override
    public void close() {
        try {
            if (buffer != null) {
                buffer.close();
            }
        } finally {
            snapshots.close();
            if (config.ownsHttpClient()) {
                config.httpClient().close();
            }
        }
    }
}
