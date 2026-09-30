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
}
