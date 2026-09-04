package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The use-case loading rules that decide what a call sends, and what they refuse to guess. */
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
        UseCase pin = Resolver.resolve(snapshot, "greeting");

        assertTrue(pin.params().containsKey("seed"));
        assertNull(pin.params().get("seed"));
        assertEquals(0.5, pin.params().get("temperature"));
        assertTrue(pin.providerOptions().containsKey("only"));
        assertNull(pin.providerOptions().get("only"));
        assertEquals("price", pin.providerOptions().get("sort"));
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
             "use_cases": {"greeting": {"id": "u1", "kind": "chat", "default_params": {}}},
             "deployments": {"greeting": {"id": "d1", "revision": 1, "model_id": "gone",
                                          "params": {}, "provider_options": {},
                                          "prompt_pins": {"default": "missing"}}},
             "prompt_versions": {}, "models": {}}
            """);
        UseCase pin = Resolver.resolve(degraded, "greeting");
        assertNull(pin.model());
        assertNull(pin.promptVersionId());
        assertEquals(List.of("missing_prompt_version: missing", "missing_model: gone"),
                pin.warnings());
    }
}
