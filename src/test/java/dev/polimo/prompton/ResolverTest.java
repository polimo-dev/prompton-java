package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The resolution rules that decide what a call sends, and what they refuse to guess. */
class ResolverTest {

    private static final Snapshot SNAPSHOT = Snapshot.parse(Fixtures.production());

    @Test
    void paramsAndProviderOptionsAreLayeredShallowlyWithTheDeploymentOnTop() {
        Resolution pin = Resolver.resolve(SNAPSHOT, "greeting");
        assertEquals(Map.of("max_tokens", 512, "temperature", 0.2), pin.effectiveParams());
        assertEquals(Map.of("only", List.of("OpenAI"), "allow_fallbacks", true),
                pin.effectiveProviderOptions());
    }

    @Test
    void anUnpinnedPromptNameIsAnErrorAndNeverFallsBackToDefault() {
        ResolutionException e = assertThrows(ResolutionException.class,
                () -> Resolver.resolve(SNAPSHOT, "greeting", "fr"));
        assertEquals(ResolutionException.Reason.UNKNOWN_PROMPT, e.reason());
        assertEquals("fr", e.prompt());
        assertEquals(List.of("default", "ko"), e.availablePrompts());
    }

    @Test
    void aUseCaseWithNoLiveDeploymentIsUnresolvedRatherThanUnknown() {
        assertEquals(ResolutionException.Reason.UNRESOLVED,
                assertThrows(ResolutionException.class,
                        () -> Resolver.resolve(SNAPSHOT, "draft")).reason());
        assertEquals(ResolutionException.Reason.UNKNOWN_USE_CASE,
                assertThrows(ResolutionException.class,
                        () -> Resolver.resolve(SNAPSHOT, "nope")).reason());
    }

    @Test
    void anEmbeddingUseCaseIgnoresAPromptName() {
        Resolution pin = Resolver.resolve(SNAPSHOT, "embed", "ko");
        assertEquals(UseCaseKind.EMBEDDING, pin.kind());
        assertNull(pin.prompt());
        assertNull(pin.promptVersionId());
        assertNull(pin.messages());
        assertNull(pin.textTemplate());
        assertEquals("openai/text-embedding-3-small", pin.model());
    }

    @Test
    void anExplicitNullOverrideIsKeptRatherThanRemovingTheKey() {
        Snapshot snapshot = Snapshot.parse("""
            {"schema_version": 3, "project": "p", "environment": "production",
             "use_cases": {"greeting": {"id": "u1", "kind": "chat",
                                        "default_params": {"temperature": 0.5, "seed": 7}}},
             "deployments": {"greeting": {"id": "d1", "revision": 1, "model_id": "m1",
                                          "params": {"seed": null},
                                          "provider_options": {"only": null},
                                          "prompt_pins": {"default": "v1"}}},
             "prompt_versions": {"v1": {"id": "v1", "number": 1, "engine": "liquid",
                                        "messages": [{"role": "user", "content": "hi"}]}},
             "models": {"m1": {"id": "m1", "provider": "openrouter", "model_id": "openai/gpt-4o-mini",
                               "provider_options": {"only": ["OpenAI"], "sort": "price"}}}}
            """);
        Resolution pin = Resolver.resolve(snapshot, "greeting");

        assertTrue(pin.effectiveParams().containsKey("seed"));
        assertNull(pin.effectiveParams().get("seed"));
        assertEquals(0.5, pin.effectiveParams().get("temperature"));
        assertTrue(pin.effectiveProviderOptions().containsKey("only"));
        assertNull(pin.effectiveProviderOptions().get("only"));
        assertEquals("price", pin.effectiveProviderOptions().get("sort"));
    }

    @Test
    void promptNamesListsWhatTheLiveDeploymentPins() {
        assertEquals(List.of("default", "ko"), SNAPSHOT.promptNames("greeting"));
        assertEquals(List.of(), SNAPSHOT.promptNames("draft"));
        assertThrows(ResolutionException.class, () -> SNAPSHOT.promptNames("nope"));
    }

    @Test
    void aSnapshotThatReferencesWhatItDoesNotContainStillResolvesWithWarnings() {
        Snapshot degraded = Snapshot.parse("""
            {"schema_version": 3, "project": "p", "environment": "production",
             "use_cases": {"greeting": {"id": "u1", "kind": "chat", "default_params": {}}},
             "deployments": {"greeting": {"id": "d1", "revision": 1, "model_id": "gone",
                                          "params": {}, "provider_options": {},
                                          "prompt_pins": {"default": "missing"}}},
             "prompt_versions": {}, "models": {}}
            """);
        Resolution pin = Resolver.resolve(degraded, "greeting");
        assertNull(pin.model());
        assertNull(pin.promptVersionId());
        assertEquals(List.of("missing_prompt_version: missing", "missing_model: gone"),
                pin.warnings());
    }
}
