package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.internal.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Native Decision prompt resolution, request preparation and logging. */
class DecisionSupportTest {

    @Test
    void rendersAndPreparesNativeDecisionRequests() {
        UseCase useCase = Resolver.resolve(UseCaseDocument.parse(Fixtures.decisionDocument()), "sentiment");

        assertEquals(UseCaseKind.DECISION, useCase.kind());
        assertEquals("decisions", useCase.api());
        assertEquals("/api/v1/systemone", useCase.requestPath());
        assertEquals(UseCaseKind.DECISION, useCase.pinnedKind());

        Decision rendered = useCase.decision(Map.of("diary", "Today was bright"));
        assertEquals(Map.of("diary", "Today was bright", "literal", "kept"), rendered.state());
        Map<String, Object> mood = Conformance.map(rendered.questions().get("mood"));
        assertEquals("Classify Today was bright.", mood.get("instructions"));
        assertEquals("negative", Conformance.map(mood.get("criteria")).keySet().stream()
                .filter(key -> Conformance.map(mood.get("criteria")).get(key) == null)
                .findFirst().orElseThrow());

        Map<String, Object> providerOverrides = new LinkedHashMap<>();
        providerOverrides.put("order", List.of("Together"));
        providerOverrides.put("allow_fallbacks", null);
        PreparedRequest request = useCase.request(Map.of("diary", "Today was bright"),
                RequestOptions.builder()
                        .params(Map.of("session_id", "session-1", "trace", Map.of("id", "trace-1")))
                        .providerOptions(providerOverrides)
                        .user("user-42")
                        .build());

        assertEquals("decisions", request.api());
        assertEquals("POST", request.method());
        assertEquals("/api/v1/systemone", request.path());
        assertEquals("typesafe/jev-1.13", request.body().get("model"));
        assertEquals(rendered.state(), request.body().get("state"));
        assertEquals(rendered.questions(), request.body().get("questions"));
        assertEquals("session-1", request.body().get("session_id"));
        assertEquals("user-42", request.body().get("user"));
        assertEquals(Map.of("id", "trace-1"), request.body().get("trace"));
        Map<String, Object> provider = Conformance.map(request.body().get("provider"));
        assertEquals(List.of("Together"), provider.get("order"));
        assertTrue(provider.containsKey("allow_fallbacks"));
        assertEquals(null, provider.get("allow_fallbacks"));
        assertFalse(request.body().containsKey("messages"));
        assertFalse(request.body().containsKey("usage"));
    }


    @Test
    void rawDecisionAccessCannotPoisonLaterPreparedRequests() {
        UseCase useCase = Resolver.resolve(UseCaseDocument.parse(Fixtures.decisionDocument()), "sentiment");
        Decision raw = useCase.decision();

        Map<String, Object> state = Conformance.map(raw.state());
        Map<String, Object> mood = Conformance.map(raw.questions().get("mood"));
        Map<String, Object> criteria = Conformance.map(mood.get("criteria"));

        assertThrows(UnsupportedOperationException.class, () -> state.put("diary", "poisoned"));
        assertThrows(UnsupportedOperationException.class, () -> mood.put("instructions", "poisoned"));
        assertThrows(UnsupportedOperationException.class, () -> criteria.put("positive", "poisoned"));

        PreparedRequest request = useCase.request(Map.of("diary", "Today was bright"));

        assertEquals(Map.of("diary", "Today was bright", "literal", "kept"), request.body().get("state"));
        Map<String, Object> renderedMood = Conformance.map(
                Conformance.map(request.body().get("questions")).get("mood"));
        assertEquals("Classify Today was bright.", renderedMood.get("instructions"));
        assertEquals("The diary feels upbeat.",
                Conformance.map(renderedMood.get("criteria")).get("positive"));
    }

    @Test
    void parsesDecisionResultsAndLogsNativeInput() {
        try (PromptOn prompton = PromptOn.create(PromptOnConfig.builder()
                .mode(Mode.TEST)
                .diskCacheEnabled(false)
                .pollingEnabled(false)
                .build())) {
            prompton.putUseCaseDocument(Fixtures.decisionDocument());
            UseCase useCase = prompton.useCase("sentiment");
            Decision input = useCase.decision(Map.of("diary", "Today was bright"));
            Map<String, Object> raw = Json.parseObject("""
                {"model":"typesafe/jev-1.13",
                 "provider":"Fireworks",
                 "answers":{
                   "mood":{"type":"choice","choice":"positive","probabilities":{"positive":0.8,"negative":0.2}},
                   "urgent":{"type":"noul","noul":0},
                   "intensity":{"type":"score","score":3}
                 },
                 "usage":{"input_tokens":7,"output_tokens":3,"cost":0.0,
                   "is_byok":true,"cost_details":{"upstream_inference_cost":0.02}}}
                """);

            Map<String, Object> returned = useCase.trackUnchecked(
                    TrackMeta.builder().inputDecision(input).variables(Map.of("diary", "Today was bright")).build(),
                    () -> ProviderResult.ok(raw, Result.fromDecisions(raw)));

            assertSame(raw, returned);
            Result result = Result.fromDecisions(raw);
            assertSame(raw, result.rawResult());
            assertEquals(Json.canonical(Json.mapAt(raw, "answers")),
                    Json.canonical(Json.parseObject(result.content())));
            assertEquals(7, result.usage().inputTokens());
            assertEquals(3, result.usage().outputTokens());
            assertEquals(0.02, result.usage().costUsd());
            assertEquals("provider", result.usage().costSource());
            assertEquals("Fireworks", result.upstreamProvider());
            assertEquals(true, result.byok());

            Map<String, Object> logged = prompton.capturedLogs().get(0);
            assertEquals("decision", logged.get("kind"));
            assertEquals("sentiment", logged.get("prompt_key"));
            assertEquals("v2026.10.14-1", logged.get("deployment_revision"));
            assertEquals("typesafe/jev-1.13", logged.get("model"));
            assertEquals("openrouter", logged.get("provider"));
            assertFalse(logged.containsKey("deployment_id"));
            assertFalse(logged.containsKey("prompt_version_id"));
            assertFalse(logged.containsKey("model_id"));
            assertEquals(Json.canonical(input.toMap()),
                    Json.canonical(Conformance.map(logged.get("input")).get("decision")));
            assertEquals(Json.canonical(Json.mapAt(raw, "answers")),
                    Json.canonical(Json.parseObject(String.valueOf(
                            Conformance.map(logged.get("output")).get("content")))));
            Map<String, Object> metadata = Conformance.map(logged.get("metadata"));
            assertEquals(true, metadata.get("is_byok"));
            assertEquals("stop", logged.get("finish_reason"));
            assertEquals("stop", logged.get("stop_kind"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }


    @Test
    void preservesDecisionByokZeroUsageCosts() {
        Map<String, Object> raw = Json.parseObject("""
            {"model":"typesafe/jev-1.13",
             "answers":{"mood":{"type":"choice","choice":"positive"}},
             "usage":{"input_tokens":0,"output_tokens":0,"cost":0.0,
               "is_byok":true,"cost_details":{"upstream_inference_cost":0.0}}}
            """);

        Result result = Result.fromDecisions(raw);

        assertEquals(0, result.usage().inputTokens());
        assertEquals(0, result.usage().outputTokens());
        assertEquals(0.0, result.usage().costUsd());
        assertEquals(true, result.byok());
    }

    @Test
    void rejectsMalformedDecisionRequestsInsteadOfFallingBackToChat() {
        UseCase useCase = Resolver.resolve(UseCaseDocument.parse(Fixtures.decisionDocument()), "sentiment");

        assertThrows(PromptOnException.class,
                () -> useCase.request(Map.of("diary", "x"), RequestOptions.builder()
                        .params(Map.of("temperature", 0)).build()));
        assertThrows(PromptOnException.class,
                () -> useCase.request(Map.of("diary", "x"), RequestOptions.builder()
                        .params(Conformance.map(Json.parseObject("{\"trace\":null}"))).build()));

        Map<String, Object> badPath = Json.parseObject(Fixtures.decisionDocument());
        Conformance.map(Conformance.map(badPath.get("deployments")).get("sentiment"))
                .put("request_path", "/api/v1/chat/completions");
        UseCase wrongPath = Resolver.resolve(UseCaseDocument.fromMap(badPath), "sentiment");
        assertThrows(PromptOnException.class, () -> wrongPath.request(Map.of("diary", "x")));

        Map<String, Object> missingProtocol = Json.parseObject(Fixtures.decisionDocument());
        Conformance.map(Conformance.map(missingProtocol.get("deployments")).get("sentiment")).remove("api");
        UseCase noApi = Resolver.resolve(UseCaseDocument.fromMap(missingProtocol), "sentiment");
        assertThrows(PromptOnException.class, () -> noApi.request(Map.of("diary", "x")));

        Map<String, Object> malformed = Json.parseObject(Fixtures.decisionDocument());
        Map<String, Object> versions = Conformance.map(malformed.get("prompt_versions"));
        Map<String, Object> version = Conformance.map(versions.values().iterator().next());
        Conformance.map(Conformance.map(version.get("decision")).get("questions")).put("bad",
                Map.of("type", "score", "instructions", "Rate", "criteria", List.of("only-one")));
        UseCase badQuestion = Resolver.resolve(UseCaseDocument.fromMap(malformed), "sentiment");
        assertThrows(PromptOnException.class, () -> badQuestion.request(Map.of("diary", "x")));
    }

    @Test
    void rejectsMalformedDecisionProviderResponses() {
        assertThrows(PromptOnException.class, () -> Result.fromDecisions(Json.parseObject("""
            {"model":"","answers":{"mood":{"type":"choice","choice":"positive"}},
             "usage":{"input_tokens":1,"output_tokens":1}}
            """)));
        assertThrows(PromptOnException.class, () -> Result.fromDecisions(Json.parseObject("""
            {"model":123,"answers":{"mood":{"type":"choice","choice":"positive"}},
             "usage":{"input_tokens":1,"output_tokens":1}}
            """)));
        assertThrows(PromptOnException.class, () -> Result.fromDecisions(Json.parseObject("""
            {"model":"typesafe/jev-1.13","answers":{},
             "usage":{"input_tokens":1,"output_tokens":1}}
            """)));
        assertThrows(PromptOnException.class, () -> Result.fromDecisions(Json.parseObject("""
            {"model":"typesafe/jev-1.13","answers":{"mood":{"type":"choice"}},
             "usage":{"input_tokens":1,"output_tokens":1}}
            """)));
        assertThrows(PromptOnException.class, () -> Result.fromDecisions(Json.parseObject("""
            {"model":"typesafe/jev-1.13","answers":{"mood":{"type":"noul","noul":false}},
             "usage":{"input_tokens":1,"output_tokens":1}}
            """)));
        assertThrows(PromptOnException.class, () -> Result.fromDecisions(Json.parseObject("""
            {"model":"typesafe/jev-1.13","answers":{"mood":{"type":"score","score":3}},
             "usage":{"input_tokens":1.5,"output_tokens":1}}
            """)));
        assertThrows(PromptOnException.class, () -> Result.fromDecisions(Json.parseObject("""
            {"model":"typesafe/jev-1.13",
             "answers":{"mood":{"type":"choice","choice":"positive","probabilities":{"positive":"bad"}}},
             "usage":{"input_tokens":1,"output_tokens":1}}
            """)));
    }

    @Test
    void acceptsLegacyOpenRouterDecisionPathFromCachedDocuments() {
        Map<String, Object> legacy = Json.parseObject(Fixtures.decisionDocument());
        Conformance.map(Conformance.map(legacy.get("deployments")).get("sentiment"))
                .put("request_path", "/api/alpha/decisions");
        UseCase useCase = Resolver.resolve(UseCaseDocument.fromMap(legacy), "sentiment");

        assertEquals("/api/alpha/decisions", useCase.request(Map.of("diary", "offline")).path());
    }
}
