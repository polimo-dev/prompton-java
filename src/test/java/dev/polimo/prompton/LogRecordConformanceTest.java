package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.internal.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Replays {@code conformance/log_record.json}: the golden monitoring logs and the envelope. */
class LogRecordConformanceTest {

    private static final Map<String, Object> FILE = Conformance.load("log_record.json");
    private static final Set<String> ERROR_KINDS = Set.of(
            "http_4xx", "http_5xx", "rate_limited", "timeout", "transport", "parse", "app");
    private static final Set<String> SOURCES = Set.of("remote", "disk", "bundle", "manual");
    private static final Set<String> KINDS = Set.of("chat", "text", "embedding");

    @TestFactory
    List<DynamicTest> goldenRecordsSatisfyTheFieldRules() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> entry : Conformance.cases(FILE, "records")) {
            String name = Json.stringAt(entry, "name");
            Map<String, Object> record = Json.mapAt(entry, "record");
            tests.add(DynamicTest.dynamicTest(name, () -> {
                for (String required : List.of("id", "use_case", "model", "status", "started_at")) {
                    assertNotNull(record.get(required), name + " is missing " + required);
                }
                assertTrue(UuidV7.isUuidV7(Json.stringAt(record, "id")),
                        name + ": the id must be a UUIDv7, since a v4 fails on write");
                assertTrue(Set.of("ok", "error").contains(Json.stringAt(record, "status")), name);
                if (record.containsKey("kind")) {
                    assertTrue(KINDS.contains(Json.stringAt(record, "kind")), name);
                }
                if (record.containsKey("source")) {
                    assertTrue(SOURCES.contains(Json.stringAt(record, "source")), name);
                }
                Map<String, Object> error = Json.mapAt(record, "error");
                if (error != null) {
                    assertTrue(ERROR_KINDS.contains(Json.stringAt(error, "kind")), name);
                    assertEquals(ErrorKind.from(Json.stringAt(error, "kind")).wireName(),
                            Json.stringAt(error, "kind"), name + ": the kind round-trips");
                }
                if (record.containsKey("stop_kind")) {
                    assertEquals(Json.stringAt(record, "stop_kind"),
                            StopKind.normalize(Json.stringAt(record, "stop_kind")).wireName(),
                            name + ": stop_kind normalisation is idempotent");
                }
                record.forEach((key, value) ->
                        assertNotNull(value, name + ": a top-level null must be omitted, not sent: " + key));
                assertNotNull(Instant.parse(Json.stringAt(record, "started_at")), name);
            }));
        }
        assertEquals(5, tests.size(), "every golden record must be checked");
        return tests;
    }

    @Test
    void theBatchEnvelopeIsOneLogsArray() {
        Map<String, Object> envelope = Json.mapAt(FILE, "batch_envelope");
        Map<String, Object> request = Json.mapAt(envelope, "request");
        assertEquals(Set.of("logs"), request.keySet());
        assertEquals(5, Json.listAt(request, "logs").size());

        Map<String, Object> response = Json.mapAt(envelope, "response_example");
        assertEquals(5, Json.intAt(response, "accepted", -1));
        Map<String, Object> resend = Json.mapAt(envelope, "response_on_resend");
        assertEquals(0, Json.intAt(resend, "accepted", -1));
        assertEquals(5, Json.intAt(resend, "duplicates", -1),
                "the id is the idempotency key, so a resend stores nothing new");
    }

    @Test
    void theSdkReproducesTheGoldenChatSuccessRecord() throws Exception {
        Map<String, Object> golden = goldenRecord("chat/success");
        Map<String, Object> actual = buildChatSuccess(golden);

        Set<String> perRun = Set.of("sdk", "id", "latency_ms", "started_at");
        golden.forEach((key, value) -> {
            if (!perRun.contains(key)) {
                assertEquals(Json.canonical(value), Json.canonical(actual.get(key)),
                        "field " + key);
            }
        });
        assertEquals(Map.of("name", "prompton-java", "version", "0.2.0"), actual.get("sdk"));
        assertTrue(UuidV7.isUuidV7(Json.stringAt(actual, "id")));
        assertNotNull(actual.get("latency_ms"));

        // The one field this SDK adds on top of the reference record: the catalog model UUID, which
        // the contract lists and which lets the server join a log to the model it used.
        Set<String> extra = new java.util.LinkedHashSet<>(actual.keySet());
        extra.removeAll(golden.keySet());
        assertEquals(Set.of("model_id"), extra);
        assertEquals("0198f2a1-0000-7000-8000-00000000e001", actual.get("model_id"));
    }

    private Map<String, Object> buildChatSuccess(Map<String, Object> golden) throws Exception {
        UseCaseDocument document = UseCaseDocument.parse(goldenDocument());
        try (PromptOn prompton = PromptOn.create(PromptOnConfig.builder()
                .mode(Mode.TEST)
                .environment("production")
                .diskCacheEnabled(false)
                .build())) {
            prompton.putUseCaseDocument(document, Source.REMOTE);
            UseCase pin = prompton.useCase("greeting");
            List<Message> messages = pin.messages(Map.of("name", "Ada"));

            pin.track(TrackMeta.builder()
                            .inputMessages(messages)
                            .variables(Map.of("name", "Ada"))
                            .endUserRef("user-42")
                            .traceId("oban:8842")
                            .sequence(1)
                            .context(Map.of("language", "en", "plan", "pro"))
                            .metadata(Map.of("job_id", 8842, "attempt", 1))
                            .build(),
                    () -> ProviderResult.ok("ok", Result.builder()
                            .content("Hello, Ada! Lovely to see you.")
                            .finishReason("stop")
                            .modelUsed("openai/gpt-4o-mini")
                            .upstreamProvider("OpenAI")
                            .byok(false)
                            .usage(new Usage(38, 9, 0.000112, "provider", Map.of(
                                    "prompt_tokens", 38, "completion_tokens", 9, "total_tokens", 47)))
                            .build()));

            Map<String, Object> actual = prompton.capturedLogs().get(0);
            assertFalse(actual.isEmpty());
            assertEquals(Json.stringAt(golden, "use_case"), actual.get("use_case"));
            return actual;
        }
    }

    private static Map<String, Object> goldenRecord(String name) {
        for (Map<String, Object> entry : Conformance.cases(FILE, "records")) {
            if (name.equals(Json.stringAt(entry, "name"))) {
                return Json.mapAt(entry, "record");
            }
        }
        throw new IllegalStateException("no golden record named " + name);
    }

    /** The use-case document the golden records were produced from, as {@code use_case.json} records it. */
    private static String goldenDocument() {
        Map<String, Object> useCaseContract = Conformance.load("use_case.json");
        return Json.write(Json.mapAt(Json.mapAt(useCaseContract, "documents"), "production"));
    }
}
