package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.http.HttpRequest;
import dev.polimo.prompton.http.HttpResponse;
import dev.polimo.prompton.http.JdkHttpClient;
import dev.polimo.prompton.http.PromptOnHttpClient;
import dev.polimo.prompton.internal.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "PTN_HTTP_CREDENTIALS", matches = ".+")
class PreviewHttpContractIT {
    @TempDir
    Path tempDir;

    @Test
    void previewToolPromptRoundTripsAndLogs() throws Exception {
        Path credentialsPath = Path.of(System.getenv("PTN_HTTP_CREDENTIALS"));
        Path dir = credentialsPath.getParent();
        Map<String, Object> credentials = Json.parseObject(Files.readString(credentialsPath));
        Map<String, Object> expectedRender = Json.parseObject(Files.readString(dir.resolve("prompton-sdk-http-render.json")));
        Map<String, Object> logRequest = Json.parseObject(Files.readString(dir.resolve("prompton-sdk-http-logs-request.json")));

        RecordingHttpClient http = new RecordingHttpClient();
        String key = Json.stringAt(credentials, "prompt_key");
        String environment = Json.stringAt(credentials, "environment");
        try (PromptOn client = PromptOn.create(PromptOnConfig.builder()
                .apiKey(Json.stringAt(credentials, "api_key"))
                .host(Json.stringAt(credentials, "base_url"))
                .environment(environment)
                .diskCachePath(tempDir.resolve("preview-prompts.json"))
                .pollingEnabled(false)
                .requestTimeout(Duration.ofSeconds(10))
                .httpClient(http)
                .build())) {
            Map<String, Object> variables = variables();
            List<Message> appHistory = appHistory();

            UseCase local = client.useCase(key);
            assertEquals(key, local.key());
            List<Message> managedMessages = local.messages(variables);
            assertProviderPayload(expectedRender, managedMessages, local.params());
            List<Message> finalMessages = new ArrayList<>(managedMessages);
            finalMessages.addAll(appHistory);
            finalMessages.add(Message.of("user", "Tell me about park walks."));
            assertEquals(managedMessages.size() + appHistory.size() + 1, finalMessages.size());

            UseCase remote = client.useCaseRemote(key, null, variables);
            assertMessages(expectedRender, remote.messages());
            assertFalse(remote.providerPreparedRequest().isEmpty(), "remote render preserves request");
            Map<String, Object> preparedBody = Json.mapAt(remote.providerPreparedRequest(), "body");
            assertEquals(Json.canonical(Json.mapAt(expectedRender, "request")),
                    Json.canonical(remote.providerPreparedRequest()));
            assertEquals(Json.canonical(Json.listAt(Json.mapAt(Json.mapAt(expectedRender, "request"), "body"), "messages")),
                    Json.canonical(Json.listAt(preparedBody, "messages")));
            assertEquals(Json.canonical(Json.listAt(Json.mapAt(Json.mapAt(expectedRender, "request"), "body"), "tools")),
                    Json.canonical(Json.listAt(preparedBody, "tools")));

            @SuppressWarnings("unchecked")
            List<Object> logs = (List<Object>) logRequest.get("logs");
            @SuppressWarnings("unchecked")
            Map<String, Object> log = new LinkedHashMap<>((Map<String, Object>) logs.get(0));
            String generationId = UuidV7.generate();
            log.put("id", generationId);
            log.put("prompt_key", key);
            log.put("template", log.remove("prompt"));
            log.remove("sdk");
            client.log(LogRecord.fromMap(log));
            FlushResult flush = client.flush(Duration.ofSeconds(10));
            assertEquals(1, flush.accepted(), "generation log accepted count");
            assertEquals(0, flush.rejected(), "generation log rejected count");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = new ArrayList<>((List<Map<String, Object>>) logRequest.get("events"));
            for (Map<String, Object> event : events) {
                event.put("event_id", "evt-java-" + UuidV7.generate());
                event.put("generation_id", generationId);
                event.remove("sdk");
            }
            EventLogResult eventsResult = client.logEvents(events, environment);
            assertEquals(2, eventsResult.accepted());
            assertEquals(0, eventsResult.duplicates());
            assertEquals(0, eventsResult.rejected().size());
        }
    }

    private static void assertProviderPayload(Map<String, Object> expectedRender,
                                              List<Message> messages,
                                              Map<String, Object> params) {
        Map<String, Object> requestBody = Json.mapAt(Json.mapAt(expectedRender, "request"), "body");
        assertMessages(expectedRender, messages);
        assertEquals(Json.canonical(Json.listAt(requestBody, "tools")), Json.canonical(Json.listAt(params, "tools")));
        assertEquals("auto", params.get("tool_choice"));
        assertEquals(false, params.get("parallel_tool_calls"));
        Map<String, Object> tool = Json.mapAt(Map.of("tool", Json.listAt(params, "tools").get(0)), "tool");
        assertFalse(tool.containsKey("output_schema"), "authoring output_schema is stripped from provider tool");
        assertFalse(tool.containsKey("output_examples"), "authoring output_examples is stripped from provider tool");
    }

    private static void assertMessages(Map<String, Object> expectedRender, List<Message> messages) {
        Map<String, Object> requestBody = Json.mapAt(Json.mapAt(expectedRender, "request"), "body");
        List<Object> actualMessages = new ArrayList<>();
        for (Message message : messages) {
            actualMessages.add(message.toMap());
        }
        assertEquals(Json.canonical(Json.listAt(requestBody, "messages")), Json.canonical(actualMessages));
    }

    private static Map<String, Object> variables() {
        return Map.of("locale", "ko-KR", "topic", "park walks");
    }

    private static List<Message> appHistory() {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of(Map.of(
                "id", "call_prior_1",
                "type", "function",
                "function", Map.of("name", "search_diaries", "arguments", "{\"query\":\"park walks\",\"limit\":1}"))));
        return List.of(
                Message.of("user", "지난 산책 일기를 찾아줘"),
                Message.fromMap(assistant),
                Message.fromMap(Map.of(
                        "role", "tool",
                        "name", "search_diaries",
                        "tool_call_id", "call_prior_1",
                        "content", List.of(Map.of("type", "text", "text", "{\"entries\":[\"A prior park walk.\"]}")))));
    }

    private static final class RecordingHttpClient implements PromptOnHttpClient {
        private final JdkHttpClient delegate = new JdkHttpClient(Duration.ofSeconds(5));
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<HttpResponse> responses = new ArrayList<>();

        @Override
        public HttpResponse send(HttpRequest request) throws IOException {
            HttpResponse response = delegate.send(request);
            requests.add(request);
            responses.add(response);
            return response;
        }

        Map<String, Object> lastJsonResponseFor(String path) {
            for (int i = requests.size() - 1; i >= 0; i--) {
                if (requests.get(i).url().contains(path)) {
                    return Json.parseObject(responses.get(i).body());
                }
            }
            throw new AssertionError("no response recorded for " + path);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
