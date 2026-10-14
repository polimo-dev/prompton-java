package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The {@code POST /prompts/{key}/render} path: its cache, and how it turns PromptOn's errors into exceptions. */
class PromptRenderClientTest {

    private static final String GREETING = """
        {"key":"greeting","kind":"chat",
         "deployment":{"id":"0198f2a1-0000-7000-8000-00000000d001","revision":"v2026.09.30-3"},
         "template":"default","template_names":["default","ko"],
         "model_id":"0198f2a1-0000-7000-8000-00000000e001","model":"openai/gpt-4o-mini",
         "provider":"openrouter",
         "params":{"temperature":0.2},
         "provider_options":{"only":["OpenAI"]},
         "prompt_version":{"id":"0198f2a1-0000-7000-8000-00000000a001","number":2},
         "messages":[{"role":"system","content":"You are a friendly greeter."},
                     {"role":"user","content":"Say hello to {{ name }}."}],
         "warnings":[],"etag":"sha256-v1","source":"disk"}
        """;

    private static final String DECISION = """
        {"key":"sentiment","kind":"decision",
         "deployment":{"id":"0198f2a1-0000-7000-8000-00000000d004","revision": "v2026.10.14-1"},
         "template":"default","template_names":["default"],
         "model_id":"0198f2a1-0000-7000-8000-00000000e003","model":"typesafe/jev-1.13",
         "provider":"openrouter","api":"decisions","request_path":"/api/v1/systemone",
         "params":{},"provider_options":{"allow_fallbacks":false},
         "prompt_version":{"id":"0198f2a1-0000-7000-8000-00000000a004","number":1,"kind":"decision"},
         "decision":{"state":{"diary":"Today was bright"},
                     "questions":{"accept":{"type":"noul","instructions":"Accept?"}}},
         "request":{"api":"decisions","method":"post","path":"/api/v1/systemone",
                    "body":{"model":"typesafe/jev-1.13","state":{"diary":"Today was bright"},
                            "questions":{"accept":{"type":"noul","instructions":"Accept?"}}}},
         "warnings":[],"etag":"sha256-v1","source":"remote"}
        """;

    private StubServer server;

    @BeforeEach
    void startServer() {
        server = new StubServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private PromptOn client() {
        return PromptOn.create(PromptOnConfig.builder()
                .apiKey("ptn_sdkfixture_test")
                .baseUrl(server.baseUrl())
                .environment("production")
                .diskCacheEnabled(false)
                .pollingEnabled(false)
                .cacheTtl(Duration.ofMinutes(5))
                .requestTimeout(Duration.ofSeconds(3))
                .build());
    }

    private static boolean isPromptRequest(StubServer.Request request) {
        return request.method().equals("POST") && request.path().endsWith("/render");
    }

    @Test
    void aResolveWithoutVariablesIsCachedForTheTtl() {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> {
            if (isPromptRequest(request)) {
                calls.incrementAndGet();
                return StubServer.Reply.ok(GREETING);
            }
            return StubServer.Reply.of(503, "{}");
        });
        try (PromptOn prompton = client()) {
            for (int i = 0; i < 5; i++) {
                Prompt pin = prompton.promptRemote("greeting", null);
                assertEquals("greeting", pin.key());
                assertEquals("openai/gpt-4o-mini", pin.model());
                assertEquals(PromptSource.DISK, pin.source());
                assertEquals(List.of("default", "ko"), pin.templateNames());
                assertEquals("Say hello to {{ name }}.", pin.messages().get(1).content());
            }
            assertEquals(1, calls.get(), "the answer is cached per prompt, prompt and environment");
        }
    }

    @Test
    void remoteDecisionRenderingKeepsNativeDecisionAndPreparedRequest() {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> {
            if (isPromptRequest(request)) {
                calls.incrementAndGet();
                return StubServer.Reply.ok(DECISION);
            }
            return StubServer.Reply.of(503, "{}");
        });

        try (PromptOn prompton = client()) {
            Prompt first = prompton.promptRemote("sentiment", null, Map.of("diary", "Today was bright"));
            assertEquals(PromptKind.DECISION, first.kind());
            assertEquals("decisions", first.api());
            assertEquals("/api/v1/systemone", first.requestPath());
            assertEquals(Map.of("diary", "Today was bright"), first.decision().state());
            assertEquals("decisions", first.providerPreparedRequest().get("api"));
            assertEquals("/api/v1/systemone", first.providerPreparedRequest().get("path"));

            Prompt second = prompton.promptRemote("sentiment", null, Map.of("diary", "Today was bright"));
            assertEquals(first.providerPreparedRequest(), second.providerPreparedRequest());
            assertEquals(2, calls.get(), "server rendering with variables is not cached");
        }
    }

    @Test
    void remoteRenderingAcceptsReadableResponsesWithoutResourceIds() {
        server.handle(request -> StubServer.Reply.ok("""
            {"key":"greeting","kind":"chat",
             "deployment":{"revision": "v2026.10.14-1"},
             "template":"default","template_names":["default"],
             "model":"openai/gpt-4o-mini","provider":"openrouter",
             "api":"chat_completions","request_path":"/api/v1/chat/completions",
             "params":{"temperature":0.2},"provider_options":{"allow_fallbacks":false},
             "prompt_version":{"number":2,"kind":"chat"},
             "messages":[{"role":"user","content":"Say hello"}],
             "warnings":[],"etag":"sha256-v8","source":"remote"}
            """));
        try (PromptOn prompton = client()) {
            Prompt pin = prompton.promptRemote("greeting", null);
            assertNull(pin.deploymentId());
            assertNull(pin.modelId());
            assertNull(pin.promptVersionId());
            assertEquals(2, pin.promptVersionNumber());
            assertEquals("openai/gpt-4o-mini", pin.model());
            assertEquals("openrouter", pin.provider());
            assertEquals("Say hello", pin.messages().get(0).content());
        }
    }

    @Test
    void aRateLimitedResolveServesTheCacheAndThenLeavesTheServerAlone() {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> {
            if (isPromptRequest(request)) {
                return calls.incrementAndGet() == 1
                        ? StubServer.Reply.ok(GREETING)
                        : StubServer.Reply.of(429, "{\"error\":{\"code\":\"rate_limited\","
                                        + "\"details\":{\"retry_after\":60}}}")
                                .withHeader("retry-after", "60");
            }
            return StubServer.Reply.of(503, "{}");
        });
        try (PromptOn prompton = shortCache()) {
            assertEquals("openai/gpt-4o-mini", prompton.promptRemote("greeting", null).model());
            sleep(10);
            assertEquals("openai/gpt-4o-mini", prompton.promptRemote("greeting", null).model());
            assertEquals(2, calls.get(), "the expired entry did go back to the server, and got a 429");

            for (int i = 0; i < 25; i++) {
                sleep(2);
                assertEquals("openai/gpt-4o-mini", prompton.promptRemote("greeting", null).model());
            }
            assertEquals(2, calls.get(),
                    "Retry-After: 60 was read, so no request may be made before it has elapsed");
        }
    }

    @Test
    void aFailingOrUnreachableResolveServesTheCacheAndBacksOff() {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> {
            if (isPromptRequest(request)) {
                return calls.incrementAndGet() == 1
                        ? StubServer.Reply.ok(GREETING)
                        : StubServer.Reply.of(503, "{\"error\":{\"code\":\"unavailable\"}}")
                                .withHeader("retry-after", "30");
            }
            return StubServer.Reply.of(503, "{}");
        });
        try (PromptOn prompton = shortCache()) {
            assertEquals("openai/gpt-4o-mini", prompton.promptRemote("greeting", null).model());
            sleep(10);
            assertEquals("openai/gpt-4o-mini", prompton.promptRemote("greeting", null).model());
            assertEquals(2, calls.get());

            server.close();
            for (int i = 0; i < 10; i++) {
                sleep(2);
                assertEquals("openai/gpt-4o-mini", prompton.promptRemote("greeting", null).model());
            }
            assertEquals(2, calls.get(), "a 5xx pauses the endpoint the same way a 429 does");
        }
    }

    @Test
    void withNothingCachedAndTheServerRefusingTheCallerIsToldWhy() {
        server.handle(request -> StubServer.Reply.of(429,
                        "{\"error\":{\"code\":\"rate_limited\",\"details\":{\"retry_after\":60}}}")
                .withHeader("retry-after", "60"));
        try (PromptOn prompton = shortCache()) {
            assertThrows(ApiException.class, () -> prompton.promptRemote("greeting", null));
            PromptOnException second = assertThrows(PromptOnException.class,
                    () -> prompton.promptRemote("greeting", null));
            assertTrue(second.getMessage().contains("wait until"), second.getMessage());
            assertEquals(1, server.requests("/render").size(), "the pause holds with no cache too");
        }
    }

    private PromptOn shortCache() {
        return PromptOn.create(PromptOnConfig.builder()
                .apiKey("k").baseUrl(server.baseUrl()).environment("production")
                .diskCacheEnabled(false).pollingEnabled(false)
                .requestTimeout(Duration.ofSeconds(3))
                .cacheTtl(Duration.ofMillis(1)).build());
    }

    @Test
    void aResolveWithVariablesIsRenderedByTheServerAndNotCached() {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> {
            if (!isPromptRequest(request)) {
                return StubServer.Reply.of(503, "{}");
            }
            calls.incrementAndGet();
            assertTrue(request.body().contains("\"variables\""));
            return StubServer.Reply.ok(GREETING.replace(
                    "Say hello to {{ name }}.", "Say hello to Ada."));
        });
        try (PromptOn prompton = client()) {
            for (int i = 0; i < 3; i++) {
                Prompt pin = prompton.promptRemote("greeting", null, Map.of("name", "Ada"));
                assertEquals("Say hello to Ada.", pin.messages().get(1).content());
            }
            assertEquals(3, calls.get(), "a rendered answer depends on the variables, so it is not cached");
        }
    }

    @Test
    void aNumericDeploymentRevisionFromTheServerIsInvalid() {
        server.handle(request -> StubServer.Reply.ok(GREETING.replace(
                "\"revision\":\"v2026.09.30-3\"", "\"revision\":3")));
        try (PromptOn prompton = client()) {
            PromptOnException e = assertThrows(PromptOnException.class,
                    () -> prompton.promptRemote("greeting", null));
            assertTrue(e.getMessage().contains("deployment revision must be a string"));
        }
    }

    @Test
    void anUnknownPromptBecomesAPromptExceptionListingWhatIsPinned() {
        server.handle(request -> StubServer.Reply.of(404, """
            {"error":{"code":"not_found",
             "message":"the live deployment pins no template named \\"fr\\"",
             "details":{"reason":"unknown_template","template":"fr","key":"greeting",
                        "template_names":["default","ko"]}}}
            """));
        try (PromptOn prompton = client()) {
            PromptException e = assertThrows(PromptException.class,
                    () -> prompton.promptRemote("greeting", "fr"));
            assertEquals(PromptException.Reason.UNKNOWN_TEMPLATE, e.reason());
            assertEquals("greeting", e.key());
            assertEquals("fr", e.template());
            assertEquals(List.of("default", "ko"), e.templateNames());
        }
    }

    @Test
    void anUnknownPromptErrorUsesTheCanonicalKeyDetail() {
        server.handle(request -> StubServer.Reply.of(404,
                "{\"error\":{\"code\":\"not_found\",\"message\":\"unknown prompt\","
                        + "\"details\":{\"reason\":\"unknown_prompt\",\"key\":\"nope\"}}}"));
        try (PromptOn prompton = client()) {
            PromptException e = assertThrows(PromptException.class,
                    () -> prompton.promptRemote("nope", null));
            assertEquals(PromptException.Reason.UNKNOWN_PROMPT, e.reason());
            assertEquals("nope", e.key());
        }
    }

    @Test
    void noLiveDeploymentBecomesUnresolved() {
        server.handle(request -> StubServer.Reply.of(404,
                "{\"error\":{\"code\":\"not_found\",\"message\":\"no live deployment\","
                        + "\"details\":{\"reason\":\"unresolved\"}}}"));
        try (PromptOn prompton = client()) {
            PromptException e = assertThrows(PromptException.class,
                    () -> prompton.promptRemote("draft", null));
            assertEquals(PromptException.Reason.UNRESOLVED, e.reason());
            assertEquals("draft", e.key());
        }
    }

    @Test
    void aMissingVariableBecomesATemplateException() {
        server.handle(request -> StubServer.Reply.of(400,
                "{\"error\":{\"code\":\"invalid_request\",\"message\":\"missing variable: name\","
                        + "\"details\":{\"missing_variable\":\"name\"}}}"));
        try (PromptOn prompton = client()) {
            TemplateException e = assertThrows(TemplateException.class,
                    () -> prompton.promptRemote("greeting", null, Map.of()));
            assertEquals(TemplateException.Kind.MISSING_VARIABLE, e.kind());
            assertEquals("name", e.variable());
        }
    }

    @Test
    void anUnauthorizedKeyBecomesAnApiException() {
        server.handle(request -> StubServer.Reply.of(401,
                "{\"error\":{\"code\":\"unauthorized\",\"message\":\"invalid or missing API key\","
                        + "\"details\":{}}}"));
        try (PromptOn prompton = client()) {
            ApiException e = assertThrows(ApiException.class,
                    () -> prompton.promptRemote("greeting", null));
            assertEquals(401, e.status());
            assertEquals("unauthorized", e.code());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
