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
 * The PromptOn client: resolve a pin, render its prompt, call your provider yourself, log what
 * happened.
 *
 * <pre>{@code
 * try (PromptOn prompton = PromptOn.create()) {
 *     Resolution pin = prompton.resolve("support_reply");
 *     List<Message> messages = prompton.renderMessages(pin, Map.of("question", question));
 *     String answer = prompton.withGeneration(pin, GenerationMeta.builder()
 *             .inputMessages(messages).variables(Map.of("question", question)).build(),
 *         () -> {
 *             MyReply reply = myProvider.chat(pin.model(), messages, pin.effectiveParams());
 *             return ProviderResult.ok(reply.text(), GenerationOutcome.builder()
 *                     .content(reply.text()).finishReason(reply.finishReason()).build());
 *         });
 * }
 * }</pre>
 *
 * <p>PromptOn is never in the request path: the provider call above is yours, with your key and
 * your HTTP client. {@link #resolve} reads a snapshot held in memory, refreshed in the background,
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
    // resolve and render

    /** The pin for {@code useCase} with the {@code default} prompt. */
    public Resolution resolve(String useCase) {
        return resolve(useCase, null);
    }

    /**
     * The pin for {@code useCase} with a named prompt.
     *
     * <p>Reads the snapshot in memory: no HTTP call, no blocking, unless nothing has been loaded
     * yet and the very first fetch is still in flight.
     *
     * @param useCase the use case key
     * @param promptName the prompt name, or {@code null} for {@code default}
     * @return what to send to the provider
     * @throws ResolutionException when the use case, its deployment or that prompt name is not in
     *     the snapshot, or when nothing is cached and PromptOn is unreachable
     */
    public Resolution resolve(String useCase, String promptName) {
        SnapshotStore.Entry entry = snapshots.require();
        return Resolver.resolve(
                entry.snapshot(), useCase, promptName, entry.source(), entry.etag());
    }

    /** Every prompt name the live deployment of {@code useCase} pins, sorted. */
    public List<String> promptNames(String useCase) {
        return snapshots.require().snapshot().promptNames(useCase);
    }

    /**
     * Renders a chat pin's messages with this call's variables.
     *
     * @param resolution a pin of kind {@link UseCaseKind#CHAT}
     * @param variables the values the template reads
     * @return the messages to send to the provider
     * @throws TemplateException when a required variable is missing
     */
    public List<Message> renderMessages(Resolution resolution, Map<String, Object> variables) {
        if (resolution.messages() == null) {
            throw new PromptOnException(
                    "use case " + resolution.useCase() + " is of kind "
                            + resolution.kind().wireName() + " and has no chat template");
        }
        return Template.renderMessages(resolution.messages(), variables, resolution.engine());
    }

    /**
     * Renders a text pin's template with this call's variables.
     *
     * @param resolution a pin of kind {@link UseCaseKind#TEXT}
     * @param variables the values the template reads
     * @return the prompt to send to the provider
     * @throws TemplateException when a required variable is missing
     */
    public String renderText(Resolution resolution, Map<String, Object> variables) {
        if (resolution.textTemplate() == null) {
            throw new PromptOnException(
                    "use case " + resolution.useCase() + " is of kind "
                            + resolution.kind().wireName() + " and has no text template");
        }
        return Template.render(resolution.textTemplate(), variables, resolution.engine());
    }

    /**
     * Resolves on the server with {@code POST /resolve}, returning the raw templates.
     *
     * <p>The answer is cached for the snapshot TTL per use case, prompt and environment; render it
     * locally with {@link #renderMessages}. Use it as a smoke test or on a cold, low-traffic path,
     * never once per request in a hot loop.
     *
     * @param useCase the use case key
     * @param promptName the prompt name, or {@code null} for {@code default}
     * @return the pin as the server resolved it
     */
    public Resolution resolveRemote(String useCase, String promptName) {
        return resolveClient.resolve(useCase, promptName, null);
    }

    /**
     * Resolves and renders on the server in one round trip.
     *
     * <p>Not cached, because the answer depends on the variables. The returned resolution's
     * {@link Resolution#messages()} and {@link Resolution#textTemplate()} are already rendered.
     *
     * @param useCase the use case key
     * @param promptName the prompt name, or {@code null} for {@code default}
     * @param variables the values the template reads
     * @return the pin, with its prompt rendered
     */
    public Resolution resolveRemote(
            String useCase, String promptName, Map<String, Object> variables) {
        return resolveClient.resolve(
                useCase, promptName, variables == null ? Map.of() : variables);
    }

    // ---------------------------------------------------------------------
    // monitoring logs

    /** A UUIDv7 to use as a record id, issued before the provider call. */
    public String newGenerationId() {
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
    public void log(GenerationRecord record) {
        record.validate();
        Map<String, Object> map = record.toMap();
        map.putIfAbsent("id", UuidV7.generate());
        map.putIfAbsent("sdk", sdkIdentity());

        PayloadPolicy policy = record.payloadPolicy();
        if (policy == null) {
            policy = policyFor(record.useCase());
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

    /** A snapshot of the log queue and its counters. */
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
     * @param resolution the pin this call used
     * @param meta what went in, and how to find this call again later
     * @param call your provider call
     * @param <T> whatever your own code wants back
     * @return {@link ProviderResult#value()}
     * @throws Exception whatever your call threw
     */
    public <T> T withGeneration(Resolution resolution, GenerationMeta meta, ProviderCall<T> call)
            throws Exception {
        GenerationMeta safeMeta = meta == null ? GenerationMeta.empty() : meta;
        String id = safeMeta.id() == null ? UuidV7.generate() : safeMeta.id();
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        try {
            ProviderResult<T> result = call.call();
            long latency = elapsedMillis(start);
            log(buildRecord(resolution, safeMeta, id, startedAt, latency,
                    result.failed() ? GenerationRecord.Status.ERROR : GenerationRecord.Status.OK,
                    result.outcome(), result.error()));
            return result.value();
        } catch (Exception | Error e) {
            long latency = elapsedMillis(start);
            log(buildRecord(resolution, safeMeta, id, startedAt, latency,
                    GenerationRecord.Status.ERROR, null,
                    GenerationError.of(ErrorKind.APP, describe(e))));
            throw e;
        }
    }

    /**
     * {@link #withGeneration} for a call that throws no checked exception.
     *
     * @param resolution the pin this call used
     * @param meta what went in, and how to find this call again later
     * @param call your provider call
     * @param <T> whatever your own code wants back
     * @return {@link ProviderResult#value()}
     */
    public <T> T withGenerationUnchecked(
            Resolution resolution, GenerationMeta meta, UncheckedProviderCall<T> call) {
        try {
            return withGeneration(resolution, meta, call::call);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new PromptOnException("provider call failed: " + e.getMessage(), e);
        }
    }

    /** A provider call that throws nothing checked. */
    @FunctionalInterface
    public interface UncheckedProviderCall<T> {
        /** @return the outcome, told apart into success and failure */
        ProviderResult<T> call();
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private GenerationRecord buildRecord(
            Resolution resolution,
            GenerationMeta meta,
            String id,
            Instant startedAt,
            long latencyMs,
            GenerationRecord.Status status,
            GenerationOutcome outcome,
            GenerationError error) {
        GenerationRecord.Builder builder = GenerationRecord.builder()
                .resolution(resolution)
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

        if (meta.params() != null && resolution != null) {
            builder.params(Params.merge(resolution.effectiveParams(), meta.params()));
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
        if (outcome != null) {
            builder.output(outcome.outputMap());
            builder.finishReason(outcome.finishReason());
            builder.stopKind(outcome.stopKind());
            builder.usage(outcome.usage());
            builder.modelUsed(outcome.modelUsed());
            builder.upstreamProvider(outcome.upstreamProvider());
            if (outcome.byok() != null) {
                metadata.put("is_byok", outcome.byok());
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
        Snapshot.UseCase found = entry.snapshot().useCases().get(useCase);
        return found == null || found.payloadPolicy() == null
                ? config.payloadDefaults()
                : found.payloadPolicy();
    }

    // ---------------------------------------------------------------------
    // snapshot control

    /** Where the configuration in memory came from and how old it is. */
    public SnapshotInfo snapshotInfo() {
        return snapshots.info();
    }

    /**
     * Fetches the snapshot once, now, and waits for it — for a script, a warm-up or a test.
     *
     * @return what the refresh did; a failure is reported, not thrown
     */
    public RefreshOutcome refresh() {
        return snapshots.refresh();
    }

    /**
     * Writes the document in memory, with its {@code .meta.json} sidecar, to {@code path}.
     *
     * <p>This is how the bundled snapshot is built: run it in CI and commit both files, one per
     * environment, so a cold start with no disk cache and no network still resolves.
     *
     * @param path where to write it
     */
    public void exportSnapshot(Path path) {
        snapshots.exportTo(path);
    }

    /** Installs a snapshot the application supplied, as {@link ResolutionSource#MANUAL}. */
    public void putSnapshot(String json) {
        snapshots.put(Snapshot.parse(json), ResolutionSource.MANUAL, json);
    }

    /** Installs an already-parsed snapshot, as {@link ResolutionSource#MANUAL}. */
    public void putSnapshot(Snapshot snapshot) {
        snapshots.put(snapshot, ResolutionSource.MANUAL, null);
    }

    /**
     * Installs a snapshot and says where it is to be reported as coming from — for a test that
     * needs a record to read {@code resolution_source: "remote"}.
     *
     * @param snapshot the document to install
     * @param source what resolutions against it report
     */
    public void putSnapshot(Snapshot snapshot, ResolutionSource source) {
        snapshots.put(snapshot, source, null);
    }

    /** The snapshot in memory, or {@code null} when nothing has been loaded. */
    public Snapshot snapshot() {
        SnapshotStore.Entry entry = snapshots.current();
        return entry == null ? null : entry.snapshot();
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
