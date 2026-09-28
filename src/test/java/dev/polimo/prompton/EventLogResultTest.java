package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.internal.Json;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventLogResultTest {

    private StubServer server;

    @BeforeEach
    void startServer() {
        server = new StubServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void logEventsReturnsNestedEventAckWithPartialRejections() {
        server.handle(request -> StubServer.Reply.of(202, """
                {
                  "accepted": 9,
                  "duplicates": 0,
                  "rejected": [],
                  "events": {
                    "accepted": 2,
                    "duplicates": 1,
                    "rejected": [
                      {"index": 2, "id": "evt-bad", "code": "invalid_request", "message": "bad event"}
                    ]
                  }
                }
                """));

        try (PromptOn prompton = PromptOn.create(config().build())) {
            EventLogResult result = prompton.logEvents(List.of(event()), "staging");

            assertEquals(2, result.accepted());
            assertEquals(1, result.duplicates());
            assertEquals(1, result.rejected().size());
            assertEquals("evt-bad", result.rejected().get(0).get("id"));
            assertEquals("environment=staging", server.requests("/logs").get(0).query());

            Map<String, Object> body = Json.parseObject(server.requests("/logs").get(0).body());
            assertEquals(List.of(), body.get("logs"));
            assertEquals(1, Json.listAt(body, "events").size());
        }
    }

    @Test
    void logEventsFallsBackToTopLevelAckForOlderResponses() {
        server.handle(request -> StubServer.Reply.of(202,
                "{\"accepted\":1,\"duplicates\":2,\"rejected\":[]}"));

        try (PromptOn prompton = PromptOn.create(config().build())) {
            EventLogResult result = prompton.logEvents(List.of(event()));

            assertEquals(1, result.accepted());
            assertEquals(2, result.duplicates());
            assertEquals(List.of(), result.rejected());
        }
    }

    @Test
    void logEventsUsesTheSameAckShapeInTestMode() {
        try (PromptOn prompton = PromptOn.create(config().mode(Mode.TEST).build())) {
            EventLogResult result = prompton.logEvents(List.of(event(), event()));

            assertEquals(2, result.accepted());
            assertEquals(0, result.duplicates());
            assertEquals(List.of(), result.rejected());
            assertEquals(2, prompton.capturedEvents().size());
            assertEquals(List.of(), server.requests("/logs"));
        }
    }

    @Test
    void logEventsStillThrowsOnHttpErrors() {
        server.handle(request -> StubServer.Reply.of(401,
                "{\"error\":{\"code\":\"unauthorized\",\"message\":\"bad key\"}}"));

        try (PromptOn prompton = PromptOn.create(config().build())) {
            PromptOnException error = assertThrows(
                    PromptOnException.class,
                    () -> prompton.logEvents(List.of(event())));

            assertTrue(error.getMessage().contains("HTTP 401"));
        }
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

    private static Map<String, Object> event() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("trace_id", "trace-1");
        event.put("event_kind", "tool_attempt");
        event.put("status", "ok");
        event.put("tool_call_id", "call_1");
        event.put("tool_name", "search_diary");
        event.put("arguments", Map.of("query", "mood"));
        event.put("result", Map.of("title", "today"));
        return event;
    }
}
