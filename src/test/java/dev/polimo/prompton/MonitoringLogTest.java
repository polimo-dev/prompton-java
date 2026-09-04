package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.internal.Json;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The monitoring-log buffer: batching, partial acceptance, retries, splitting and dropping. */
class MonitoringLogTest {

    @TempDir
    Path tempDir;

    private StubServer server;

    @BeforeEach
    void startServer() {
        server = new StubServer();
        server.handle(this::defaultRoutes);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private StubServer.Reply defaultRoutes(StubServer.Request request) {
        if (request.path().endsWith("/use-cases")) {
            return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"");
        }
        return StubServer.Reply.of(202, "{\"accepted\":1,\"duplicates\":0,\"rejected\":[]}");
    }

    private PromptOnConfig.Builder config() {
        return PromptOnConfig.builder()
                .apiKey("ptn_sdkfixture_test")
                .baseUrl(server.baseUrl())
                .environment("production")
                .project("sdkfixture")
                .diskCacheEnabled(false)
                .pollingEnabled(false)
                .logFlushInterval(Duration.ofSeconds(30))
                .logFlushSize(1000)
                .requestTimeout(Duration.ofSeconds(3));
    }

    private static LogRecord record(String useCase) {
        return LogRecord.builder()
                .key(useCase)
                .model("openai/gpt-4o-mini")
                .status(LogRecord.Status.OK)
                .startedAt(Instant.now())
                .latencyMs(12L)
                .build();
    }

    @Test
    void aBatchCarriesTheEnvelopeTheEnvironmentAndUuidV7Ids() {
        try (PromptOn prompton = PromptOn.create(config().build())) {
            prompton.log(record("greeting"));
            prompton.log(record("summarize"));
            FlushResult result = prompton.flush(Duration.ofSeconds(5));

            assertEquals(1, result.batches());
            assertEquals(2, result.records());
            List<StubServer.Request> posts = server.requests("/logs");
            assertEquals(1, posts.size());
            assertEquals("environment=production", posts.get(0).query());

            List<Object> records = Json.listAt(Json.parseObject(posts.get(0).body()), "logs");
            assertEquals(2, records.size());
            for (Object entry : records) {
                Map<String, Object> map = Conformance.map(entry);
                assertTrue(UuidV7.isUuidV7(String.valueOf(map.get("id"))),
                        "the id must be a UUIDv7, not a v4: " + map.get("id"));
                assertEquals(Map.of("name", "prompton-java", "version", "0.2.0"), map.get("sdk"));
            }
        }
    }

    @Test
    void aBatchNeverCarriesMoreThanTwoHundredRecords() {
        try (PromptOn prompton = PromptOn.create(config().build())) {
            for (int i = 0; i < 250; i++) {
                prompton.log(record("greeting"));
            }
            prompton.flush(Duration.ofSeconds(10));

            List<StubServer.Request> posts = server.requests("/logs");
            assertEquals(2, posts.size());
            assertEquals(200, Json.listAt(Json.parseObject(posts.get(0).body()), "logs").size());
            assertEquals(50, Json.listAt(Json.parseObject(posts.get(1).body()), "logs").size());
        }
    }

    @Test
    void oneBatchPerEnvironmentEvenWhenTheRecordsInterleave() {
        try (PromptOn prompton = PromptOn.create(config().build())) {
            for (int i = 0; i < 50; i++) {
                prompton.log(LogRecord.builder()
                        .key("greeting").model("m").status(LogRecord.Status.OK)
                        .startedAt(Instant.now())
                        .environment(i % 2 == 0 ? "production" : "staging")
                        .build());
            }
            FlushResult result = prompton.flush(Duration.ofSeconds(10));

            List<StubServer.Request> posts = server.requests("/logs");
            List<String> queries = new ArrayList<>();
            posts.forEach(r -> queries.add(r.query()));
            assertEquals(List.of("environment=production", "environment=staging"), queries,
                    "two environments are two requests, never one request per record");
            assertEquals(2, result.batches());
            assertEquals(50, result.records());
            assertEquals(25, Json.listAt(Json.parseObject(posts.get(0).body()), "logs").size());
            assertEquals(25, Json.listAt(Json.parseObject(posts.get(1).body()), "logs").size());
        }
    }

    @Test
    void partialAcceptanceIsReadAndAcceptedRecordsAreNeverResent() {
        server.handle(request -> request.path().endsWith("/use-cases")
                ? StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"")
                : StubServer.Reply.of(202, "{\"accepted\":2,\"duplicates\":0,\"rejected\":"
                        + "[{\"index\":1,\"id\":\"x\",\"code\":\"invalid_request\","
                        + "\"message\":\"started_at is more than 7 days in the past\"}]}"));
        try (PromptOn prompton = PromptOn.create(config().build())) {
            prompton.log(record("greeting"));
            prompton.log(record("greeting"));
            prompton.log(record("greeting"));
            FlushResult result = prompton.flush(Duration.ofSeconds(5));

            assertEquals(1, result.rejected());
            assertEquals(2, result.accepted());
            assertEquals(0, result.remaining());
            assertEquals(1, server.requests("/logs").size(), "nothing is resent");
            assertEquals(1, prompton.logStats().droppedRejected());
        }
    }

    @Test
    void aRateLimitResendsTheSameBatchWithTheSameIdsAndNeverReachesTheCaller() {
        AtomicInteger posts = new AtomicInteger();
        server.handle(request -> {
            if (request.path().endsWith("/use-cases")) {
                return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"");
            }
            if (posts.incrementAndGet() == 1) {
                return StubServer.Reply.of(429,
                                "{\"error\":{\"code\":\"rate_limited\",\"details\":{\"retry_after\":1}}}")
                        .withHeader("retry-after", "1");
            }
            return StubServer.Reply.of(202, "{\"accepted\":2,\"duplicates\":0,\"rejected\":[]}");
        });
        try (PromptOn prompton = PromptOn.create(config().build())) {
            prompton.log(record("greeting"));
            prompton.log(record("greeting"));
            FlushResult result = prompton.flush(Duration.ofSeconds(10));

            List<StubServer.Request> sent = server.requests("/logs");
            assertEquals(2, sent.size());
            assertEquals(idsOf(sent.get(0)), idsOf(sent.get(1)), "the same ids are resent");
            assertEquals(2, result.accepted());
            assertEquals(0, result.remaining());
        }
    }

    @Test
    void aServerErrorIsRetriedAndThenDroppedAndCounted() {
        server.handle(request -> request.path().endsWith("/use-cases")
                ? StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"")
                : StubServer.Reply.of(503, "{\"error\":{\"code\":\"unavailable\"}}")
                        .withHeader("retry-after", "0"));
        try (PromptOn prompton = PromptOn.create(config().logMaxAttempts(2).build())) {
            prompton.log(record("greeting"));
            prompton.flush(Duration.ofSeconds(10));

            assertEquals(3, server.requests("/logs").size(),
                    "two retries, then the batch is dropped");
            assertEquals(1, prompton.logStats().droppedFailed());
            assertEquals(0, prompton.logStats().queued());
        }
    }

    @Test
    void aPayloadTooLargeBatchIsSplitInHalf() {
        server.handle(request -> {
            if (request.path().endsWith("/use-cases")) {
                return StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"");
            }
            int count = Json.listAt(Json.parseObject(request.body()), "logs").size();
            return count > 3
                    ? StubServer.Reply.of(413, "{\"error\":{\"code\":\"payload_too_large\"}}")
                    : StubServer.Reply.of(202,
                            "{\"accepted\":" + count + ",\"duplicates\":0,\"rejected\":[]}");
        });
        try (PromptOn prompton = PromptOn.create(config().build())) {
            for (int i = 0; i < 6; i++) {
                prompton.log(record("greeting"));
            }
            FlushResult result = prompton.flush(Duration.ofSeconds(10));

            List<StubServer.Request> posts = server.requests("/logs");
            assertEquals(3, posts.size(), "one refused batch, then its two halves");
            assertEquals(6, result.accepted());
            assertEquals(0, result.remaining());
        }
    }

    @Test
    void anyOtherFourxxDropsTheBatchWithoutRetrying() {
        server.handle(request -> request.path().endsWith("/use-cases")
                ? StubServer.Reply.ok(Fixtures.production()).withHeader("etag", "\"v1\"")
                : StubServer.Reply.of(403, "{\"error\":{\"code\":\"forbidden\","
                        + "\"message\":\"API key lacks the logs scope\"}}"));
        try (PromptOn prompton = PromptOn.create(config().build())) {
            prompton.log(record("greeting"));
            prompton.flush(Duration.ofSeconds(5));

            assertEquals(1, server.requests("/logs").size(), "a 4xx is never retried");
            assertEquals(1, prompton.logStats().droppedFailed());
        }
    }

    @Test
    void theQueueIsBoundedAndDropsTheOldest() {
        try (PromptOn prompton = PromptOn.create(config().logMaxBuffer(3).build())) {
            for (int i = 0; i < 5; i++) {
                prompton.log(record("greeting"));
            }
            LogStats stats = prompton.logStats();
            assertEquals(3, stats.queued());
            assertEquals(2, stats.droppedFull());
        }
    }

    @Test
    void closeFlushesWhatIsLeft() {
        PromptOn prompton = PromptOn.create(config().build());
        prompton.log(record("greeting"));
        assertEquals(0, server.requests("/logs").size());
        prompton.close();
        assertEquals(1, server.requests("/logs").size(), "close drains the buffer");
    }

    @Test
    void theRedactHookRunsLastAndTheEndUserRefCanBeHashed() {
        List<String> seen = new ArrayList<>();
        try (PromptOn prompton = PromptOn.create(config()
                .mode(Mode.TEST)
                .hashEndUser(true)
                .redact(map -> {
                    seen.add(String.valueOf(map.get("end_user_ref")));
                    Map<String, Object> copy = new LinkedHashMap<>(map);
                    copy.put("trace_id", "redacted");
                    return copy;
                })
                .build())) {
            prompton.log(LogRecord.builder()
                    .key("greeting").model("m").status(LogRecord.Status.OK)
                    .startedAt(Instant.now()).endUserRef("user-42").traceId("secret")
                    .build());

            Map<String, Object> logged = prompton.capturedLogs().get(0);
            assertEquals(Payload.sha256Hex("user-42"), logged.get("end_user_ref"));
            assertEquals("redacted", logged.get("trace_id"));
            assertEquals(List.of(Payload.sha256Hex("user-42")), seen,
                    "the hook sees the already-hashed ref, so it runs last");
        }
    }

    @Test
    void aRecordMissingARequiredFieldIsRefusedBeforeItIsQueued() {
        try (PromptOn prompton = PromptOn.create(config().mode(Mode.TEST).build())) {
            PromptOnException e = assertThrows(PromptOnException.class, () -> prompton.log(
                    LogRecord.builder().key("greeting").model("m").build()));
            assertTrue(e.getMessage().contains("status"));
            assertTrue(prompton.capturedLogs().isEmpty());
        }
    }

    @Test
    void testModeCapturesRecordsAndMakesNoHttpCall() {
        try (PromptOn prompton = PromptOn.create(config().mode(Mode.TEST).build())) {
            prompton.putUseCaseDocument(Fixtures.production());
            UseCase pin = prompton.useCase("greeting");
            prompton.log(LogRecord.builder()
                    .useCase(pin)
                    .status(LogRecord.Status.OK)
                    .startedAt(Instant.now())
                    .output(Map.of("content", "Hello, Ada!"))
                    .build());

            assertEquals(0, server.requests().size(), "test mode makes no HTTP call");
            Map<String, Object> logged = prompton.capturedLogs().get(0);
            assertEquals("greeting", logged.get("use_case"));
            assertEquals("openai/gpt-4o-mini", logged.get("model"));
            assertEquals("manual", logged.get("source"));
            assertNotNull(logged.get("id"));
            prompton.clearCapturedLogs();
            assertTrue(prompton.capturedLogs().isEmpty());
        }
    }

    @Test
    void trackTimesTheCallAndLogsIt() throws Exception {
        try (PromptOn prompton = PromptOn.create(config().mode(Mode.TEST).build())) {
            prompton.putUseCaseDocument(Fixtures.production());
            UseCase pin = prompton.useCase("greeting");
            List<Message> messages = pin.messages(Map.of("name", "Ada"));

            String answer = pin.track(TrackMeta.builder()
                    .inputMessages(messages)
                    .variables(Map.of("name", "Ada"))
                    .endUserRef("user-42")
                    .traceId("job:1")
                    .sequence(1)
                    .context(Map.of("language", "en"))
                    .metadata(Map.of("job_id", 88))
                    .build(),
                () -> ProviderResult.ok("Hello, Ada!", Result.builder()
                        .content("Hello, Ada!")
                        .finishReason("stop")
                        .usage(new Usage(38, 9, 0.000112, "provider", null))
                        .modelUsed("openai/gpt-4o-mini")
                        .upstreamProvider("OpenAI")
                        .byok(false)
                        .build()));

            assertEquals("Hello, Ada!", answer);
            Map<String, Object> logged = prompton.capturedLogs().get(0);
            assertEquals("ok", logged.get("status"));
            assertEquals("stop", logged.get("stop_kind"));
            assertEquals("chat", logged.get("kind"));
            assertEquals(3, logged.get("deployment_revision"));
            assertEquals("default", logged.get("prompt"));
            assertEquals("OpenAI", logged.get("upstream_provider"));
            assertEquals(Map.of("language", "en"), logged.get("context"));
            assertEquals(Map.of("job_id", 88, "is_byok", false), logged.get("metadata"));
            assertEquals(Map.of("max_tokens", 512, "temperature", 0.2), logged.get("params"));
            assertNotNull(logged.get("latency_ms"));
            Map<String, Object> input = Conformance.map(logged.get("input"));
            assertEquals(Map.of("name", "Ada"), input.get("variables"));
            assertEquals(2, Json.listAt(input, "messages").size());
        }
    }

    @Test
    void trackLogsTheSelectedNamedPromptEvidence() throws Exception {
        try (PromptOn prompton = PromptOn.create(config().mode(Mode.TEST).build())) {
            prompton.putUseCaseDocument(Fixtures.production());
            UseCase korean = prompton.useCase("greeting", "ko");
            List<Message> messages = korean.messages(Map.of("name", "Ada"));

            korean.track(TrackMeta.builder().inputMessages(messages).build(),
                    () -> ProviderResult.ok("안녕하세요, Ada!", Result.ofContent("안녕하세요, Ada!")));

            Map<String, Object> logged = prompton.capturedLogs().get(0);
            assertEquals("greeting", logged.get("use_case"));
            assertEquals("ko", logged.get("prompt"));
            assertEquals(korean.promptVersionId(), logged.get("prompt_version_id"));
        }
    }

    @Test
    void trackLogsAFailureAndKeepsTheUsage() throws Exception {
        try (PromptOn prompton = PromptOn.create(config().mode(Mode.TEST).build())) {
            prompton.putUseCaseDocument(Fixtures.production());
            UseCase pin = prompton.useCase("greeting");

            String value = pin.track(TrackMeta.empty(),
                    () -> ProviderResult.error("fallback",
                            LogError.ofStatus(429, "rate limited by upstream provider"),
                            Result.builder()
                                    .content("partial")
                                    .usage(Usage.ofTokens(38, 4))
                                    .build()));

            assertEquals("fallback", value);
            Map<String, Object> logged = prompton.capturedLogs().get(0);
            assertEquals("error", logged.get("status"));
            assertEquals(Map.of("kind", "rate_limited", "status", 429,
                    "message", "rate limited by upstream provider"), logged.get("error"));
            assertEquals(Map.of("content", "partial"), logged.get("output"));
        }
    }

    @Test
    void anExceptionInsideTheCallIsLoggedAndRethrownUnchanged() {
        try (PromptOn prompton = PromptOn.create(config().mode(Mode.TEST).build())) {
            prompton.putUseCaseDocument(Fixtures.production());
            UseCase pin = prompton.useCase("greeting");
            IllegalStateException boom = new IllegalStateException("provider exploded");

            IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                    pin.trackUnchecked(TrackMeta.empty(), () -> {
                        throw boom;
                    }));

            assertEquals(boom, thrown, "the original exception propagates unchanged");
            Map<String, Object> logged = prompton.capturedLogs().get(0);
            assertEquals("error", logged.get("status"));
            Map<String, Object> error = Conformance.map(logged.get("error"));
            assertEquals("app", error.get("kind"));
            assertTrue(String.valueOf(error.get("message")).contains("provider exploded"));
        }
    }

    @Test
    void samplingDropsThePayloadButKeepsTheRecord() {
        try (PromptOn prompton = PromptOn.create(config()
                .mode(Mode.TEST)
                .payloadDefaults(new PayloadPolicy(PayloadPolicy.Mode.FULL, 0.0, 262144))
                .build())) {
            prompton.log(LogRecord.builder()
                    .key("nowhere").model("m").status(LogRecord.Status.OK)
                    .startedAt(Instant.now())
                    .input(Map.of("text", "secret question"))
                    .output(Map.of("content", "secret answer"))
                    .build());

            Map<String, Object> logged = prompton.capturedLogs().get(0);
            assertFalse(logged.containsKey("input"));
            assertFalse(logged.containsKey("output"));
            assertEquals("nowhere", logged.get("use_case"));
        }
    }

    private static List<String> idsOf(StubServer.Request request) {
        List<String> ids = new ArrayList<>();
        for (Object entry : Json.listAt(Json.parseObject(request.body()), "logs")) {
            ids.add(String.valueOf(Conformance.map(entry).get("id")));
        }
        return ids;
    }
}
