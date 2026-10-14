package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.polimo.prompton.internal.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The prompt loading rules that decide what a call sends, and what they refuse to guess. */
class ResolverTest {

    private static final UseCaseDocument SNAPSHOT = UseCaseDocument.parse(Fixtures.production());

    @Test
    void paramsAndProviderOptionsAreLayeredShallowlyWithTheDeploymentOnTop() {
        UseCase pin = Resolver.resolve(SNAPSHOT, "greeting");
        assertEquals(Map.of("max_tokens", 512, "temperature", 0.2), pin.params());
        assertEquals(Map.of("only", List.of("OpenAI"), "allow_fallbacks", true),
                pin.providerOptions());
    }

    @Test
    void anUnpinnedPromptNameIsAnErrorAndNeverFallsBackToDefault() {
        UseCaseException e = assertThrows(UseCaseException.class,
                () -> Resolver.resolve(SNAPSHOT, "greeting", "fr"));
        assertEquals(UseCaseException.Reason.UNKNOWN_PROMPT, e.reason());
        assertEquals("fr", e.prompt());
        assertEquals(List.of("default", "ko"), e.promptNames());
    }

    @Test
    void aUseCaseWithNoLiveDeploymentIsUnresolvedRatherThanUnknown() {
        assertEquals(UseCaseException.Reason.UNRESOLVED,
                assertThrows(UseCaseException.class,
                        () -> Resolver.resolve(SNAPSHOT, "draft")).reason());
        assertEquals(UseCaseException.Reason.UNKNOWN_USE_CASE,
                assertThrows(UseCaseException.class,
                        () -> Resolver.resolve(SNAPSHOT, "nope")).reason());
    }

    @Test
    void anEmbeddingUseCaseIgnoresAPromptName() {
        UseCase pin = Resolver.resolve(SNAPSHOT, "embed", "ko");
        assertEquals(UseCaseKind.EMBEDDING, pin.kind());
        assertNull(pin.prompt());
        assertNull(pin.promptVersionId());
        assertNull(pin.messages());
        assertNull(pin.textTemplate());
        assertEquals("openai/text-embedding-3-small", pin.model());
    }

    @Test
    void anExplicitNullOverrideIsKeptRatherThanRemovingTheKey() {
        UseCaseDocument snapshot = UseCaseDocument.parse("""
            {"schema_version": 4, "project": "p", "environment": "production",
             "prompts": {"greeting": {"id": "u1", "kind": "chat",
                                        "default_params": {"temperature": 0.5, "seed": 7}}},
             "deployments": {"greeting": {"id": "d1", "revision": "v2026.09.30-1", "model_id": "m1",
                                          "params": {"seed": null},
                                          "provider_options": {"only": null},
                                          "template_pins": {"default": "v1"}}},
             "prompt_versions": {"v1": {"id": "v1", "number": 1, "engine": "liquid",
                                        "messages": [{"role": "user", "content": "hi"}]}},
             "models": {"m1": {"id": "m1", "provider": "openrouter", "model_id": "openai/gpt-4o-mini",
                               "provider_options": {"only": ["OpenAI"], "sort": "price"}}}}
            """);
        UseCase pin = Resolver.resolve(snapshot, "greeting");

        assertTrue(pin.params().containsKey("seed"));
        assertNull(pin.params().get("seed"));
        assertEquals(0.5, pin.params().get("temperature"));
        assertTrue(pin.providerOptions().containsKey("only"));
        assertNull(pin.providerOptions().get("only"));
        assertEquals("price", pin.providerOptions().get("sort"));
    }

    @Test
    void deploymentRevisionMustBeAString() {
        PromptOnException e = assertThrows(PromptOnException.class, () -> UseCaseDocument.parse("""
            {"schema_version": 7, "project": "p", "environment": "production",
             "prompts": {"greeting": {"id": "u1", "kind": "chat", "default_params": {}}},
             "deployments": {"greeting": {"id": "d1", "revision": 1, "model_id": "m1",
                                          "params": {}, "provider_options": {},
                                          "template_pins": {"default": "v1"}}}}
            """));
        assertTrue(e.getMessage().contains("deployment revision must be a string"));
    }

    @Test
    void nativeToolMessagesSurviveRenderingAsWholeProviderMaps() {
        UseCaseDocument snapshot = UseCaseDocument.parse("""
            {"schema_version": 7, "project": "p", "environment": "production",
             "prompts": {"tool_chat": {"id": "u1", "kind": "chat", "default_params": {}}},
             "deployments": {"tool_chat": {"id": "d1", "revision": "v2026.09.30-1", "model_id": "m1",
                                          "params": {}, "provider_options": {},
                                          "template_pins": {"default": "v1"}}},
             "prompt_versions": {"v1": {"id": "v1", "number": 1, "engine": "liquid",
                "messages": [
                  {"role":"system","content":"Continue with {{ input }}."},
                  {"role":"assistant","tool_calls":[{"id":"call_search","type":"function","function":{"name":"search","arguments":"{\\\"q\\\":\\\"diary\\\"}"}}],"content":null},
                  {"role":"tool","tool_call_id":"call_search","content":[{"type":"text","text":"found"}]},
                  {"role":"user","content":"Next: {{ input }}"}
                ]}},
             "models": {"m1": {"id": "m1", "provider": "openrouter", "model_id": "openai/gpt-4o-mini",
                               "provider_options": {}, "capabilities": ["tools"], "status": "active"}}}
            }
            """);
        UseCase pin = Resolver.resolve(snapshot, "tool_chat");
        List<Object> actual = pin.messages(Map.of("input", "continue")).stream()
                .map(Message::toMap)
                .map(x -> (Object) x)
                .toList();
        List<Object> expected = Json.listAt(Json.parseObject("""
            {"messages":[
              {"role":"system","content":"Continue with continue."},
              {"role":"assistant","tool_calls":[{"id":"call_search","type":"function","function":{"name":"search","arguments":"{\\\"q\\\":\\\"diary\\\"}"}}],"content":null},
              {"role":"tool","tool_call_id":"call_search","content":[{"type":"text","text":"found"}]},
              {"role":"user","content":"Next: continue"}
            ]}
            """), "messages");
        assertEquals(Json.canonical(expected), Json.canonical(actual));
    }

    @Test
    void promptToolsBecomeProviderParamsAndStripAuthoringMetadata() {
        UseCaseDocument snapshot = UseCaseDocument.parse("""
            {"schema_version": 7, "project": "p", "environment": "production",
             "prompts": {"tool_chat": {"id": "u1", "kind": "chat", "default_params": {}}},
             "deployments": {"tool_chat": {"id": "d1", "revision": "v2026.09.30-1", "model_id": "m1",
                                          "params": {}, "provider_options": {},
                                          "template_pins": {"default": "v1"}}},
             "prompt_versions": {"v1": {"id": "v1", "number": 1, "engine": "liquid",
                "messages": [{"role":"user","content":"hi"}],
                "tools": {"definitions": [{"type":"function","function":{"name":"search"},
                                             "output_schema":{"type":"object"},"output_examples":[{"ok":true}]}],
                          "tool_choice": {"type":"function","function":{"name":"search"}},
                          "parallel_tool_calls": false}}},
             "models": {"m1": {"id": "m1", "provider": "openrouter", "model_id": "openai/gpt-4o-mini",
                               "provider_options": {}, "capabilities": ["tools"], "status": "active"}}}
            }
            """);
        UseCase pin = Resolver.resolve(snapshot, "tool_chat");
        Map<String, Object> params = pin.params();
        List<?> tools = (List<?>) params.get("tools");
        Map<String, Object> tool = Conformance.map(tools.get(0));
        assertFalse(tool.containsKey("output_schema"));
        assertFalse(tool.containsKey("output_examples"));
        assertEquals(Map.of("type", "function", "function", Map.of("name", "search")), tool);
        assertEquals(false, params.get("parallel_tool_calls"));
    }

    @Test
    void promptToolsConflictWithDifferentLegacyProviderParams() {
        UseCaseDocument snapshot = UseCaseDocument.parse("""
            {"schema_version": 7, "project": "p", "environment": "production",
             "prompts": {"tool_chat": {"id": "u1", "kind": "chat", "default_params": {}}},
             "deployments": {"tool_chat": {"id": "d1", "revision": "v2026.09.30-1", "model_id": "m1",
                                          "params": {"parallel_tool_calls": true}, "provider_options": {},
                                          "template_pins": {"default": "v1"}}},
             "prompt_versions": {"v1": {"id": "v1", "number": 1, "engine": "liquid",
                "messages": [{"role":"user","content":"hi"}],
                "tools": {"parallel_tool_calls": false}}},
             "models": {"m1": {"id": "m1", "provider": "openrouter", "model_id": "openai/gpt-4o-mini",
                               "provider_options": {}, "capabilities": ["tools"], "status": "active"}}}
            }
            """);
        assertThrows(PromptOnException.class, () -> Resolver.resolve(snapshot, "tool_chat"));
    }

    @Test
    void promptNamesListsWhatTheLiveDeploymentPins() {
        assertEquals(List.of("default", "ko"), SNAPSHOT.promptNames("greeting"));
        assertEquals(List.of(), SNAPSHOT.promptNames("draft"));
        assertThrows(UseCaseException.class, () -> SNAPSHOT.promptNames("nope"));
    }

    @Test
    void aSnapshotThatReferencesWhatItDoesNotContainStillResolvesWithWarnings() {
        UseCaseDocument degraded = UseCaseDocument.parse("""
            {"schema_version": 4, "project": "p", "environment": "production",
             "prompts": {"greeting": {"id": "u1", "kind": "chat", "default_params": {}}},
             "deployments": {"greeting": {"id": "d1", "revision": "v2026.09.30-1", "model_id": "gone",
                                          "params": {}, "provider_options": {},
                                          "template_pins": {"default": "missing"}}},
             "prompt_versions": {}, "models": {}}
            """);
        UseCase pin = Resolver.resolve(degraded, "greeting");
        assertNull(pin.model());
        assertNull(pin.promptVersionId());
        assertEquals(List.of("missing_prompt_version: missing", "missing_model: gone"),
                pin.warnings());
    }

    @Test
    void schemaEightUsesReadableResourceReferencesWithoutPublicIds() {
        UseCaseDocument snapshot = UseCaseDocument.parse("""
            {"schema_version": 8, "project": "p", "environment": "production",
             "prompts": {
               "greeting": {"kind": "chat", "default_params": {"temperature": 0.5}},
               "classify": {"kind": "decision", "default_params": {}}
             },
             "deployments": {
               "greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                            "model": "shared/model", "prompt_version": 2,
                            "params": {"temperature": 0.2}, "provider_options": {"allow_fallbacks": false},
                            "api": "chat_completions", "request_path": "/api/v1/chat/completions"},
               "classify": {"revision": "v2026.10.14-2", "provider": "anthropic",
                            "model": "shared/model", "prompt_version": 2,
                            "params": {}, "provider_options": {},
                            "api": "decisions", "request_path": "/api/v1/decisions"}
             },
             "prompt_versions": {
               "greeting@2": {"prompt": "greeting", "number": 2, "kind": "chat", "engine": "liquid",
                              "messages": [{"role": "user", "content": "Hello {{ name }}"}]},
               "classify@2": {"prompt": "classify", "number": 2, "kind": "decision", "engine": "liquid",
                               "decision": {"state": {"text": "{{ text }}"},
                                            "questions": {"ok": {"type": "noul", "instructions": "Accept?"}}}}
             },
             "models": {
               "openrouter:shared/model": {"provider": "openrouter", "model": "shared/model",
                                           "display_name": "OpenRouter shared", "provider_options": {"only": ["OpenAI"]},
                                           "metadata": {}, "capabilities": ["tools"], "status": "active"},
               "anthropic:shared/model": {"provider": "anthropic", "model": "shared/model",
                                          "display_name": "Anthropic shared", "provider_options": {},
                                          "metadata": {}, "capabilities": [], "status": "active"}
             }}
            """);

        UseCase greeting = Resolver.resolve(snapshot, "greeting");
        assertEquals(8, snapshot.schemaVersion());
        assertNull(greeting.deploymentId());
        assertNull(greeting.modelId());
        assertNull(greeting.promptVersionId());
        assertEquals(2, greeting.promptVersionNumber());
        assertEquals("openrouter", greeting.provider());
        assertEquals("shared/model", greeting.model());
        assertEquals("Hello Ada", greeting.messages(Map.of("name", "Ada")).get(0).content());

        UseCase classify = Resolver.resolve(snapshot, "classify");
        assertNull(classify.modelId());
        assertNull(classify.promptVersionId());
        assertEquals(2, classify.promptVersionNumber());
        assertEquals("anthropic", classify.provider());
        assertEquals(UseCaseKind.DECISION, classify.kind());
        assertEquals(Map.of("text", "great"), classify.decision(Map.of("text", "great")).state());
    }

    @Test
    void schemaEightWarningsUseReadableMissingReferences() {
        UseCaseDocument degraded = UseCaseDocument.parse("""
            {"schema_version": 8, "project": "p", "environment": "production",
             "prompts": {"greeting": {"kind": "chat", "default_params": {}}},
             "deployments": {"greeting": {"revision": "v2026.10.14-1", "provider": "openrouter",
                                          "model": "missing:model", "prompt_version": 3,
                                          "params": {}, "provider_options": {}}},
             "prompt_versions": {}, "models": {}}
            """);
        UseCase pin = Resolver.resolve(degraded, "greeting");
        assertEquals(List.of("missing_prompt_version: greeting@3", "missing_model: openrouter:missing:model"),
                pin.warnings());
        assertNull(pin.modelId());
        assertNull(pin.promptVersionId());
    }

    @Test
    void schemaNineIsStillUnsupportedUntilTheContractChangesAgain() {
        PromptOnException error = assertThrows(PromptOnException.class, () -> UseCaseDocument.parse("""
            {"schema_version": 9, "project": "p", "environment": "production", "prompts": {}}
            """));
        assertTrue(error.getMessage().contains("this SDK reads version 8"));
    }
}
