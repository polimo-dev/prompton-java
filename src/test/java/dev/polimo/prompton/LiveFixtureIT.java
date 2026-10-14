package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.http.HttpRequest;
import dev.polimo.prompton.http.HttpResponse;
import dev.polimo.prompton.http.JdkHttpClient;
import dev.polimo.prompton.http.PromptOnHttpClient;
import dev.polimo.prompton.internal.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The live contract, against a running PromptOn seeded with the {@code sdkfixture} project.
 *
 * <p>Skipped unless {@code PTN_API_KEY} is set. Run it with:
 *
 * <pre>
 * PTN_HOST=http://localhost:4000 PTN_API_KEY=ptn_sdkfixture_... ./gradlew test
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "PTN_API_KEY", matches = ".+")
class LiveFixtureIT {

    private static final String HOST = System.getenv().getOrDefault("PTN_HOST", "http://localhost:4000");
    private static final String KEY = System.getenv("PTN_API_KEY");
    private static final String BASE = HOST + "/api/v1";

    private PromptOnHttpClient http;

    @TempDir
    Path tempDir;

    @BeforeAll
    void openClient() {
        http = new JdkHttpClient(Duration.ofSeconds(5));
    }

    @AfterAll
    void closeClient() {
        http.close();
    }

    private PromptOn prompton(String environment) {
        return PromptOn.create(PromptOnConfig.builder()
                .apiKey(KEY)
                .host(HOST)
                .environment(environment)
                .diskCachePath(tempDir.resolve("snapshot-" + environment + ".json"))
                .pollingEnabled(false)
                .requestTimeout(Duration.ofSeconds(10))
                .build());
    }

    private HttpResponse get(String url, Map<String, String> extraHeaders) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json");
        headers.put("authorization", "Bearer " + KEY);
        headers.putAll(extraHeaders);
        return http.send(new HttpRequest("GET", url, headers, null, Duration.ofSeconds(10)));
    }

    private HttpResponse post(String url, String body) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json");
        headers.put("content-type", "application/json");
        headers.put("authorization", "Bearer " + KEY);
        return http.send(new HttpRequest("POST", url, headers, body, Duration.ofSeconds(10)));
    }

    @Test
    void snapshotFetchAndThenA304OnRepoll() throws Exception {
        HttpResponse first = get(BASE + "/prompts?environment=production", Map.of());
        assertEquals(200, first.status());
        String etag = first.header("etag");
        assertNotNull(etag, "the snapshot must carry an ETag to poll with");

        PromptDocument snapshot = PromptDocument.parse(first.body());
        assertEquals(PromptDocument.SCHEMA_VERSION, snapshot.schemaVersion());
        assertEquals("production", snapshot.environment());
        assertEquals(List.of(), snapshot.warnings());
        assertTrue(snapshot.prompts().keySet().containsAll(List.of("greeting", "summarize", "embed")));

        HttpResponse repoll = get(BASE + "/prompts?environment=production",
                Map.of("if-none-match", etag));
        assertEquals(304, repoll.status(), "an unchanged snapshot answers 304 with no body");
        assertTrue(repoll.body() == null || repoll.body().isEmpty());
    }

    @Test
    void localPromptMatchesTheServerForTheDefaultPrompt() throws Exception {
        try (PromptOn client = prompton("production")) {
            Prompt local = client.prompt("greeting");
            List<Message> rendered = local.messages(Map.of("name", "Ada"));

            Map<String, Object> remote = Json.parseObject(post(BASE + "/prompts/greeting/render",
                    "{\"variables\":{\"name\":\"Ada\"}}").body());

            assertPromptMatches(local, remote);
            assertEquals(Json.canonical(Json.listAt(remote, "messages")),
                    Json.canonical(asMaps(rendered)),
                    "local rendering must produce exactly what the server produces");
        }
    }

    @Test
    void localPromptMatchesTheServerForLanguageBranching() throws Exception {
        try (PromptOn client = prompton("production")) {
            Prompt local = client.prompt("greeting");
            Map<String, Object> variables = Map.of("name", "Ada", "language", "ko");
            List<Message> rendered = local.messages(variables);

            Map<String, Object> remote = Json.parseObject(post(BASE + "/prompts/greeting/render",
                    "{\"variables\":{\"name\":\"Ada\",\"language\":\"ko\"}}")
                    .body());

            assertPromptMatches(local, remote);
            assertEquals("default", local.template());
            assertEquals(Json.canonical(Json.listAt(remote, "messages")),
                    Json.canonical(asMaps(rendered)));
        }
    }

    @Test
    void localPromptMatchesTheServerForATextPrompt() throws Exception {
        try (PromptOn client = prompton("production")) {
            Prompt local = client.prompt("summarize");
            String rendered = local.text(Map.of("items", List.of("alpha", "beta")));

            Map<String, Object> remote = Json.parseObject(post(BASE + "/prompts/summarize/render",
                    "{\"variables\":{\"items\":[\"alpha\",\"beta\"]}}")
                    .body());

            assertPromptMatches(local, remote);
            assertEquals(PromptKind.TEXT, local.kind());
            assertEquals(Json.stringAt(remote, "text"), rendered);
        }
    }

    @Test
    void anEmbeddingPromptResolvesToAModelAndNoPrompt() throws Exception {
        try (PromptOn client = prompton("production")) {
            Prompt local = client.prompt("embed");
            Map<String, Object> remote =
                    Json.parseObject(post(BASE + "/prompts/embed/render", "{}").body());

            assertPromptMatches(local, remote);
            assertEquals(PromptKind.EMBEDDING, local.kind());
            assertEquals(null, local.template());
            assertEquals(null, local.promptVersionId());
            assertEquals(List.of(), local.templateNames());
        }
    }

    @Test
    void aStagingSnapshotResolvesIndependentlyOfProduction() throws Exception {
        HttpResponse staging = get(BASE + "/prompts?environment=staging", Map.of());
        assertEquals(200, staging.status());
        assertEquals("staging", PromptDocument.parse(staging.body()).environment());

        try (PromptOn client = prompton("staging")) {
            Prompt pin = client.prompt("greeting");
            assertNotNull(pin.model());
            assertEquals("staging", client.promptDocumentInfo().environment());
            assertEquals(PromptSource.REMOTE, pin.source());
        }
    }

    @Test
    void theErrorCasesComeBackAsTheContractDescribes() throws Exception {
        assertEquals(404, get(BASE + "/prompts?environment=nope", Map.of()).status());

        HttpResponse unknownPrompt = post(BASE + "/prompts/nope/render", "{}");
        assertEquals(404, unknownPrompt.status());
        assertEquals("not_found", errorCode(unknownPrompt));

        HttpResponse unknownTemplate = post(BASE + "/prompts/greeting/render",
                "{\"template\":\"fr\",\"variables\":{\"name\":\"Ada\"}}");
        assertEquals(400, unknownTemplate.status());
        assertEquals("invalid_request", errorCode(unknownTemplate));

        HttpResponse missingVariable = post(BASE + "/prompts/greeting/render",
                "{\"variables\":{}}");
        assertEquals(400, missingVariable.status());
        assertEquals("name", errorDetail(missingVariable, "missing_variable"));

        HttpResponse missingPromptPath = post(BASE + "/prompts/%20/render", "{}");
        assertEquals(404, missingPromptPath.status());

        Map<String, String> wrongKey = new LinkedHashMap<>();
        wrongKey.put("accept", "application/json");
        wrongKey.put("authorization", "Bearer ptn_sdkfixture_wrong");
        assertEquals(401, http.send(new HttpRequest("GET",
                BASE + "/prompts?environment=production", wrongKey, null, Duration.ofSeconds(10)))
                .status());
    }

    @Test
    void theSdkRaisesTheSameErrorsLocallyThatTheServerReturns() {
        try (PromptOn client = prompton("production")) {
            assertEquals(PromptException.Reason.UNKNOWN_PROMPT,
                    assertThrows(PromptException.class, () -> client.prompt("nope")).reason());

            PromptException unknownTemplate = assertThrows(PromptException.class,
                    () -> client.prompt("greeting", "fr"));
            assertEquals(PromptException.Reason.UNKNOWN_TEMPLATE, unknownTemplate.reason());
            assertEquals(List.of("default", "ko"), unknownTemplate.templateNames());

            Prompt pin = client.prompt("greeting");
            TemplateException missing = assertThrows(TemplateException.class,
                    () -> pin.messages(Map.of()));
            assertEquals("name", missing.variable());
        }
    }

    @Test
    void aLogsBatchIsAcceptedAndThenCountedAsDuplicatesOnResend() throws Exception {
        String batch = Json.write(Map.of("logs", List.of(
                liveRecord("greeting", "default"), liveRecord("greeting", "ko"))));

        HttpResponse first = post(BASE + "/logs?environment=production", batch);
        assertEquals(202, first.status());
        Map<String, Object> accepted = Json.parseObject(first.body());
        assertEquals(2, Json.intAt(accepted, "accepted", -1));
        assertEquals(0, Json.intAt(accepted, "duplicates", -1));
        assertEquals(List.of(), Json.listAt(accepted, "rejected"));

        HttpResponse resend = post(BASE + "/logs?environment=production", batch);
        assertEquals(202, resend.status());
        Map<String, Object> again = Json.parseObject(resend.body());
        assertEquals(0, Json.intAt(again, "accepted", -1));
        assertEquals(2, Json.intAt(again, "duplicates", -1),
                "the record id is the idempotency key, so a resend stores nothing new");
    }

    @Test
    void theSdkSendsAMonitoringLogEndToEnd() throws Exception {
        try (PromptOn client = prompton("production")) {
            Prompt pin = client.prompt("greeting");
            List<Message> messages = pin.messages(Map.of("name", "Ada"));

            String answer = pin.track(TrackMeta.builder()
                            .inputMessages(messages)
                            .variables(Map.of("name", "Ada"))
                            .traceId("java-sdk-live-test")
                            .endUserRef("user-42")
                            .build(),
                    () -> ProviderResult.ok("Hello, Ada!", Result.builder()
                            .content("Hello, Ada!")
                            .finishReason("stop")
                            .usage(new Usage(38, 6, 0.000012, "provider", null))
                            .build()));

            assertEquals("Hello, Ada!", answer);
            FlushResult result = client.flush(Duration.ofSeconds(10));
            assertEquals(1, result.records());
            assertEquals(0, result.rejected());
            assertEquals(0, result.remaining());
        }
    }

    @Test
    void theSnapshotIsMirroredToDiskAndResolvesWithoutTheServer() {
        Path cache = tempDir.resolve("mirror.json");
        try (PromptOn client = PromptOn.create(PromptOnConfig.builder()
                .apiKey(KEY).host(HOST).environment("production")
                .diskCachePath(cache).pollingEnabled(false).build())) {
            assertEquals(PromptSource.REMOTE, client.prompt("greeting").source());
        }
        try (PromptOn offline = PromptOn.create(PromptOnConfig.builder()
                .apiKey(KEY).host("http://127.0.0.1:1").environment("production")
                .diskCachePath(cache).pollingEnabled(false)
                .initialFetchTimeout(Duration.ofMillis(500))
                .requestTimeout(Duration.ofMillis(300)).build())) {
            Prompt pin = offline.prompt("greeting");
            assertEquals(PromptSource.DISK, pin.source());
            assertFalse(pin.model().isBlank());
        }
    }

    private static Map<String, Object> liveRecord(String promptKey, String template) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", UuidV7.generate());
        record.put("prompt_key", promptKey);
        record.put("kind", "chat");
        record.put("model", "openai/gpt-4o-mini");
        record.put("template", template);
        record.put("provider", "openrouter");
        record.put("source", "remote");
        record.put("status", "ok");
        record.put("started_at", Instant.now().toString());
        record.put("finish_reason", "stop");
        record.put("stop_kind", "stop");
        record.put("latency_ms", 842);
        record.put("trace_id", "java-sdk-live-test");
        record.put("sdk", Map.of("name", "prompton-java", "version", PromptOnConfig.SDK_VERSION));
        return record;
    }

    private static void assertPromptMatches(Prompt local, Map<String, Object> remote) {
        Map<String, Object> deployment = Json.mapAt(remote, "deployment");
        assertEquals(Json.stringAt(remote, "key"), local.key());
        assertEquals(Json.stringAt(remote, "kind"), local.kind().wireName());
        assertEquals(Json.stringAt(deployment, "id"), local.deploymentId());
        assertEquals(Json.stringAt(deployment, "revision"), local.deploymentRevision());
        assertEquals(Json.stringAt(remote, "template"), local.template());
        assertEquals(Json.listAt(remote, "template_names"), local.templateNames());
        assertEquals(Json.stringAt(remote, "model"), local.model());
        assertEquals(Json.stringAt(remote, "model_id"), local.modelId());
        assertEquals(Json.stringAt(remote, "provider"), local.provider());
        assertEquals(Json.canonical(Json.mapAt(remote, "params")),
                Json.canonical(local.params()));
        assertEquals(Json.canonical(Json.mapAt(remote, "provider_options")),
                Json.canonical(local.providerOptions()));
        Map<String, Object> version = Json.mapAt(remote, "prompt_version");
        assertEquals(version == null ? null : Json.stringAt(version, "id"), local.promptVersionId());
        assertEquals(version == null ? null : Json.intAt(version, "number", null),
                local.promptVersionNumber());
    }

    private static List<Object> asMaps(List<Message> messages) {
        List<Object> maps = new ArrayList<>(messages.size());
        messages.forEach(message -> maps.add(message.toMap()));
        return maps;
    }

    private static String errorCode(HttpResponse response) {
        return Json.stringAt(Json.mapAt(Json.parseObject(response.body()), "error"), "code");
    }

    private static String errorDetail(HttpResponse response, String key) {
        Map<String, Object> error = Json.mapAt(Json.parseObject(response.body()), "error");
        return Json.stringAt(Json.mapAt(error, "details"), key);
    }
}
