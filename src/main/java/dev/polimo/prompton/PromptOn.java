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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The PromptOn client: load a prompt, render its prompt, call your provider yourself, log what
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
 * your HTTP client. {@link #useCase(String)} reads a prompt document held in memory, refreshed in the background,
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
    private final List<Map<String, Object>> capturedEvents = new CopyOnWriteArrayList<>();

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
    // prompts

    /** The prompt with its {@code default} prompt. */
    public UseCase useCase(String useCase) {
        return useCase(useCase, null);
    }

    /**
     * The prompt with a named prompt.
     *
     * <p>Reads the prompt document in memory: no HTTP call, no blocking, unless nothing has been loaded
     * yet and the very first fetch is still in flight.
     *
     * @param useCase the prompt key
     * @param promptName the prompt name, or {@code null} for {@code default}
     * @return what to send to the provider
     * @throws UseCaseException when the prompt, its deployment or that prompt name is not in
     *     the prompt document, or when nothing is cached and PromptOn is unreachable
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
     * Resolves on the server with {@code POST /renders/{key}/render}, returning the raw templates.
     *
     * <p>The answer is cached for the prompt document TTL per prompt, prompt and environment; render it
     * locally with {@link UseCase#messages(Map)} or {@link UseCase#text(Map)}. Use it as a smoke
     * test or on a cold, low-traffic path,
     * never once per request in a hot loop.
     *
     * @param useCase the prompt key
     * @param promptName the prompt name, or {@code null} for {@code default}
     * @return the prompt as the server rendered it
     */
    public UseCase useCaseRemote(String useCase, String promptName) {
        return resolveClient.resolve(useCase, promptName, null).attachTo(this);
    }

    /**
     * Resolves and renders on the server in one round trip.
     *
     * <p>Not cached, because the answer depends on the variables. The returned prompt's
     * {@link UseCase#messages()} and {@link UseCase#textTemplate()} are already rendered.
     *
     * @param useCase the prompt key
     * @param promptName the prompt name, or {@code null} for {@code default}
     * @param variables the values the template reads
     * @return the prompt, with its prompt rendered
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
     * <p>Fills in {@code id} and {@code sdk} when they are unset, applies the prompt's payload
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

    /**
     * Sends tool-attempt and completion trace events to the monitoring endpoint.
     *
     * <p>The SDK only records what your application observed. It never executes tools and never
     * infers tool invocations from provider {@code tool_calls}. Missing {@code event_id},
     * {@code observed_at}, {@code sdk}, and {@code metadata.sdk.version} are filled once before the
     * request is sent, so retries by the caller can reuse the same prepared event map.
     *
     * @param events up to 500 event maps
     */
    public void logEvents(List<Map<String, Object>> events) {
        logEvents(events, config.environment());
    }

    /** Sends tool-attempt and completion trace events for a specific environment. */
    public void logEvents(List<Map<String, Object>> events, String environment) {
        List<Map<String, Object>> prepared = prepareEvents(events);
        if (config.mode() == Mode.TEST) {
            capturedEvents.addAll(prepared);
            return;
        }
        if (!config.remoteEnabled()) {
            return;
        }
        postEvents(prepared, environment == null ? config.environment() : environment);
    }

    private List<Map<String, Object>> prepareEvents(List<Map<String, Object>> events) {
        if (events == null) {
            throw new PromptOnException("events must not be null");
        }
        if (events.size() > 500) {
            throw new PromptOnException("logEvents accepts at most 500 events per request");
        }
        List<Map<String, Object>> prepared = new ArrayList<>(events.size());
        for (Map<String, Object> event : events) {
            if (event == null) {
                throw new PromptOnException("events must not contain null entries");
            }
            Map<String, Object> copy = new LinkedHashMap<>(event);
            requireEventField(copy, "trace_id");
            String kind = requireEventField(copy, "event_kind");
            if (!List.of("tool_attempt", "completion").contains(kind)) {
                throw new PromptOnException("event_kind must be tool_attempt or completion");
            }
            String status = requireEventField(copy, "status");
            if (!List.of("started", "ok", "error", "denied", "cancelled", "timeout",
                    "missing", "incomplete").contains(status)) {
                throw new PromptOnException("event status is not supported: " + status);
            }
            Object arguments = copy.get("arguments");
            if (arguments != null && !(arguments instanceof Map<?, ?>)) {
                throw new PromptOnException("event arguments must be a JSON object");
            }
            copy.putIfAbsent("event_id", UuidV7.generate());
            copy.putIfAbsent("observed_at", Instant.now().toString());
            copy.putIfAbsent("sdk", sdkIdentity());
            copy.put("metadata", metadataWithSdkVersion(copy.get("metadata")));
            prepared.add(copy);
        }
        return prepared;
    }

    private static String requireEventField(Map<String, Object> event, String key) {
        Object value = event.get(key);
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new PromptOnException("event is missing the required field " + key);
        }
        return String.valueOf(value);
    }

    private Map<String, Object> metadataWithSdkVersion(Object original) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (original instanceof Map<?, ?> map) {
            map.forEach((key, value) -> metadata.put(String.valueOf(key), value));
        }
        Object sdk = metadata.get("sdk");
        Map<String, Object> sdkMetadata = new LinkedHashMap<>();
        if (sdk instanceof Map<?, ?> map) {
            map.forEach((key, value) -> sdkMetadata.put(String.valueOf(key), value));
        }
        sdkMetadata.putIfAbsent("version", PromptOnConfig.SDK_VERSION);
        metadata.put("sdk", sdkMetadata);
        return metadata;
    }

    private void postEvents(List<Map<String, Object>> events, String environment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("logs", List.of());
        body.put("events", events);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json");
        headers.put("content-type", "application/json");
        headers.put("user-agent", config.userAgent());
        headers.put("authorization", "Bearer " + config.apiKey());
        String url = config.baseUrl() + "/logs?environment="
                + URLEncoder.encode(environment, StandardCharsets.UTF_8);
        try {
            HttpResponse response = config.httpClient().send(new HttpRequest(
                    "POST", url, headers, Json.write(body), config.requestTimeout()));
            if (response.status() < 200 || response.status() >= 300) {
                throw new PromptOnException("event log submission failed with HTTP "
                        + response.status() + ": " + response.body());
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof PromptOnException pe) {
                throw pe;
            }
            throw new PromptOnException("event log submission failed: " + e.getMessage(), e);
        }
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
     * @param useCase the prompt this call used
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
     * @param useCase the prompt this call used
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
        Map<String, Object> effectiveParams = meta.params() != null && useCase != null
                ? Params.merge(useCase.params(), meta.params())
                : meta.params() != null ? meta.params() : useCase == null ? null : useCase.params();
        addToolInputFields(input, effectiveParams);
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

    private static void addToolInputFields(Map<String, Object> input, Map<String, Object> params) {
        if (params == null) {
            return;
        }
        for (String key : List.of("tools", "tool_choice", "parallel_tool_calls")) {
            if (params.containsKey(key)) {
                input.put(key, params.get(key));
            }
        }
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
    // prompt document control

    /** Where the configuration in memory came from and how old it is. */
    public UseCaseDocumentInfo useCaseDocumentInfo() {
        return snapshots.info();
    }

    /**
     * Fetches the prompt document once, now, and waits for it — for a script, a warm-up or a test.
     *
     * @return what the refresh did; a failure is reported, not thrown
     */
    public RefreshResult refresh() {
        return snapshots.refresh();
    }

    /**
     * Writes the document in memory, with its {@code .meta.json} sidecar, to {@code path}.
     *
     * <p>This is how the bundled prompt document is built: run it in CI and commit both files, one per
     * environment, so a cold start with no disk cache and no network still renders.
     *
     * @param path where to write it
     */
    public void exportUseCaseDocument(Path path) {
        snapshots.exportTo(path);
    }

    /** Installs a prompt document the application supplied, as {@link Source#MANUAL}. */
    public void putUseCaseDocument(String json) {
        snapshots.put(UseCaseDocument.parse(json), Source.MANUAL, json);
    }

    /** Installs an already-parsed prompt document, as {@link Source#MANUAL}. */
    public void putUseCaseDocument(UseCaseDocument document) {
        snapshots.put(document, Source.MANUAL, null);
    }

    /**
     * Installs a prompt document and says where it is to be reported as coming from — for a test that
     * needs a record to read {@code source: "remote"}.
     *
     * @param document the document to install
     * @param source what reads against it report
     */
    public void putUseCaseDocument(UseCaseDocument document, Source source) {
        snapshots.put(document, source, null);
    }

    /** The prompt document in memory, or {@code null} when nothing has been loaded. */
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

    /** The trace events {@link Mode#TEST} captured instead of sending, oldest first. */
    public List<Map<String, Object>> capturedEvents() {
        return Collections.unmodifiableList(new ArrayList<>(capturedEvents));
    }

    /** Forgets every captured trace event. */
    public void clearCapturedEvents() {
        capturedEvents.clear();
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
