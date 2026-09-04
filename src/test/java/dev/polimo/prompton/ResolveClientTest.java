package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The {@code POST /resolve} path: its cache, and how it turns PromptOn's errors into exceptions. */
class ResolveClientTest {

    private static final String GREETING = """
        {"use_case":"greeting","kind":"chat",
         "deployment":{"id":"0198f2a1-0000-7000-8000-00000000d001","revision":3},
         "prompt":"default","prompts":["default","ko"],
         "model_id":"0198f2a1-0000-7000-8000-00000000e001","model":"openai/gpt-4o-mini",
         "provider":"openrouter",
         "effective_params":{"temperature":0.2},
         "effective_provider_options":{"only":["OpenAI"]},
         "prompt_version":{"id":"0198f2a1-0000-7000-8000-00000000a001","number":2},
         "messages":[{"role":"system","content":"You are a friendly greeter."},
                     {"role":"user","content":"Say hello to {{ name }}."}],
         "warnings":[],"etag":"sha256-v1"}
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

    @Test
    void aResolveWithoutVariablesIsCachedForTheTtl() {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> {
            if (request.path().endsWith("/resolve")) {
                calls.incrementAndGet();
                return StubServer.Reply.ok(GREETING);
            }
            return StubServer.Reply.of(503, "{}");
        });
        try (PromptOn prompton = client()) {
            for (int i = 0; i < 5; i++) {
                Resolution pin = prompton.resolveRemote("greeting", null);
                assertEquals("openai/gpt-4o-mini", pin.model());
                assertEquals(List.of("default", "ko"), pin.availablePrompts());
                assertEquals("Say hello to {{ name }}.", pin.messages().get(1).content());
            }
            assertEquals(1, calls.get(), "the answer is cached per use case, prompt and environment");
        }
    }

    @Test
    void aCachedResolveIsServedWhenTheServerRateLimitsOrFails() {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> {
            if (request.path().endsWith("/resolve")) {
                return calls.incrementAndGet() == 1
                        ? StubServer.Reply.ok(GREETING)
                        : StubServer.Reply.of(429, "{\"error\":{\"code\":\"rate_limited\"}}");
            }
            return StubServer.Reply.of(503, "{}");
        });
        try (PromptOn prompton = PromptOn.create(PromptOnConfig.builder()
                .apiKey("k").baseUrl(server.baseUrl()).environment("production")
                .diskCacheEnabled(false).pollingEnabled(false)
                .cacheTtl(Duration.ofMillis(1)).build())) {
            assertEquals("openai/gpt-4o-mini", prompton.resolveRemote("greeting", null).model());
            sleep(10);
            assertEquals("openai/gpt-4o-mini", prompton.resolveRemote("greeting", null).model());
            assertTrue(calls.get() >= 2, "the second call did reach the server");
        }
    }

    @Test
    void aResolveWithVariablesIsRenderedByTheServerAndNotCached() {
        AtomicInteger calls = new AtomicInteger();
        server.handle(request -> {
            if (!request.path().endsWith("/resolve")) {
                return StubServer.Reply.of(503, "{}");
            }
            calls.incrementAndGet();
            assertTrue(request.body().contains("\"variables\""));
            return StubServer.Reply.ok(GREETING.replace(
                    "Say hello to {{ name }}.", "Say hello to Ada."));
        });
        try (PromptOn prompton = client()) {
            for (int i = 0; i < 3; i++) {
                Resolution pin = prompton.resolveRemote("greeting", null, Map.of("name", "Ada"));
                assertEquals("Say hello to Ada.", pin.messages().get(1).content());
            }
            assertEquals(3, calls.get(), "a rendered answer depends on the variables, so it is not cached");
        }
    }

    @Test
    void anUnknownPromptBecomesAResolutionExceptionListingWhatIsPinned() {
        server.handle(request -> StubServer.Reply.of(404, """
            {"error":{"code":"not_found",
             "message":"the live deployment pins no prompt named \\"fr\\"",
             "details":{"reason":"unknown_prompt","prompt":"fr","use_case":"greeting",
                        "available_prompts":["default","ko"]}}}
            """));
        try (PromptOn prompton = client()) {
            ResolutionException e = assertThrows(ResolutionException.class,
                    () -> prompton.resolveRemote("greeting", "fr"));
            assertEquals(ResolutionException.Reason.UNKNOWN_PROMPT, e.reason());
            assertEquals("fr", e.prompt());
            assertEquals(List.of("default", "ko"), e.availablePrompts());
        }
    }

    @Test
    void noLiveDeploymentBecomesUnresolved() {
        server.handle(request -> StubServer.Reply.of(404,
                "{\"error\":{\"code\":\"not_found\",\"message\":\"no live deployment\","
                        + "\"details\":{\"reason\":\"unresolved\",\"use_case\":\"draft\"}}}"));
        try (PromptOn prompton = client()) {
            ResolutionException e = assertThrows(ResolutionException.class,
                    () -> prompton.resolveRemote("draft", null));
            assertEquals(ResolutionException.Reason.UNRESOLVED, e.reason());
        }
    }

    @Test
    void aMissingVariableBecomesATemplateException() {
        server.handle(request -> StubServer.Reply.of(400,
                "{\"error\":{\"code\":\"invalid_request\",\"message\":\"missing variable: name\","
                        + "\"details\":{\"missing_variable\":\"name\"}}}"));
        try (PromptOn prompton = client()) {
            TemplateException e = assertThrows(TemplateException.class,
                    () -> prompton.resolveRemote("greeting", null, Map.of()));
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
                    () -> prompton.resolveRemote("greeting", null));
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
