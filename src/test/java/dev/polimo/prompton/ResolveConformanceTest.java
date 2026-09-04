package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.polimo.prompton.internal.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Replays {@code conformance/resolve.json}: snapshot + use case (+ prompt) to a pin. */
class ResolveConformanceTest {

    private static final Map<String, Object> FILE = Conformance.load("resolve.json");

    @TestFactory
    List<DynamicTest> resolveCases() {
        Map<String, Object> snapshots = Json.mapAt(FILE, "snapshots");
        Map<String, Snapshot> parsed = new LinkedHashMap<>();
        snapshots.forEach((name, doc) -> parsed.put(name, Snapshot.fromMap(Conformance.map(doc))));

        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> testCase : Conformance.cases(FILE, "cases")) {
            String name = Json.stringAt(testCase, "name");
            tests.add(DynamicTest.dynamicTest(name, () -> runCase(parsed, testCase)));
        }
        assertEquals(15, tests.size(), "every resolve case must run");
        return tests;
    }

    private void runCase(Map<String, Snapshot> snapshots, Map<String, Object> testCase) {
        String name = Json.stringAt(testCase, "name");
        Snapshot snapshot = snapshots.get(Json.stringAt(testCase, "snapshot_ref"));
        Map<String, Object> expect = Json.mapAt(testCase, "expect");
        String expectedError = Json.stringAt(expect, "error");
        Map<String, Object> variables = Json.mapAt(testCase, "variables");
        boolean render = testCase.containsKey("variables");

        Resolution resolution;
        try {
            resolution = Resolver.resolve(
                    snapshot,
                    Json.stringAt(testCase, "use_case"),
                    Json.stringAt(testCase, "prompt"),
                    ResolutionSource.REMOTE,
                    null);
        } catch (ResolutionException e) {
            assertEquals(expectedError, e.reason().wireName(), name);
            if (expect.containsKey("prompt")) {
                assertEquals(Json.stringAt(expect, "prompt"), e.prompt(), name);
            }
            if (expect.containsKey("available_prompts")) {
                assertEquals(Json.listAt(expect, "available_prompts"), e.availablePrompts(), name);
            }
            return;
        }

        Object rendered = null;
        if (render) {
            try {
                rendered = switch (resolution.kind()) {
                    case CHAT -> Template.renderMessages(
                            resolution.messages(), variables, resolution.engine());
                    case TEXT -> Template.render(
                            resolution.textTemplate(), variables, resolution.engine());
                    case EMBEDDING -> null;
                };
            } catch (TemplateException e) {
                assertEquals(expectedError, e.kind().wireName(), name);
                assertEquals(Json.stringAt(expect, "variable"), e.variable(), name);
                return;
            }
        }
        if (expectedError != null) {
            fail(name + " should have failed with " + expectedError);
        }

        assertEquals(Json.stringAt(expect, "deployment_id"), resolution.deploymentId(), name);
        assertEquals(Json.intAt(expect, "revision", null), resolution.deploymentRevision(), name);
        assertEquals(Json.stringAt(expect, "kind"), resolution.kind().wireName(), name);
        assertEquals(Json.stringAt(expect, "prompt"), resolution.prompt(), name);
        assertEquals(Json.listAt(expect, "prompts"), resolution.availablePrompts(), name);
        assertEquals(Json.stringAt(expect, "model"), resolution.model(), name);
        assertEquals(Json.stringAt(expect, "model_id"), resolution.modelId(), name);
        assertEquals(Json.stringAt(expect, "provider"), resolution.provider(), name);
        assertEquals(
                Json.canonical(Json.mapAt(expect, "effective_params")),
                Json.canonical(resolution.effectiveParams()),
                name + " effective_params");
        assertEquals(
                Json.canonical(Json.mapAt(expect, "effective_provider_options")),
                Json.canonical(resolution.effectiveProviderOptions()),
                name + " effective_provider_options");

        Map<String, Object> version = Json.mapAt(expect, "prompt_version");
        assertEquals(
                version == null ? null : Json.stringAt(version, "id"),
                resolution.promptVersionId(),
                name + " prompt_version.id");
        assertEquals(
                version == null ? null : Json.intAt(version, "number", null),
                resolution.promptVersionNumber(),
                name + " prompt_version.number");

        List<Object> warnings = Json.listAt(expect, "warnings");
        assertEquals(warnings, List.copyOf(resolution.warnings()), name + " warnings");

        List<Object> expectedMessages = Json.listAt(expect, "messages");
        if (expectedMessages != null) {
            List<Message> actual = render
                    ? castMessages(rendered)
                    : resolution.messages();
            List<Object> asMaps = new ArrayList<>();
            for (Message message : actual) {
                asMaps.add(message.toMap());
            }
            assertEquals(Json.canonical(expectedMessages), Json.canonical(asMaps), name + " messages");
        }
        String expectedText = Json.stringAt(expect, "text");
        if (expectedText != null) {
            assertEquals(expectedText, render ? (String) rendered : resolution.textTemplate(), name);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Message> castMessages(Object rendered) {
        return (List<Message>) rendered;
    }
}
