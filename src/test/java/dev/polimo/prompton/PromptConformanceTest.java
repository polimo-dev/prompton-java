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

/** Replays {@code conformance/render.json}: prompt document + prompt (+ prompt) to a prompt. */
class PromptConformanceTest {

    private static final Map<String, Object> FILE = Conformance.load("prompt.json");

    @TestFactory
    List<DynamicTest> promptCases() {
        Map<String, Object> documents = Json.mapAt(FILE, "documents");
        Map<String, PromptDocument> parsed = new LinkedHashMap<>();
        documents.forEach((name, doc) -> parsed.put(name, PromptDocument.fromMap(Conformance.map(doc))));

        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> testCase : Conformance.cases(FILE, "cases")) {
            String name = Json.stringAt(testCase, "name");
            tests.add(DynamicTest.dynamicTest(name, () -> runCase(parsed, testCase)));
        }
        assertEquals(16, tests.size(), "every prompt case must run");
        return tests;
    }

    private void runCase(Map<String, PromptDocument> documents, Map<String, Object> testCase) {
        String name = Json.stringAt(testCase, "name");
        PromptDocument document = documents.get(Json.stringAt(testCase, "document_ref"));
        Map<String, Object> expect = Json.mapAt(testCase, "expect");
        String expectedError = Json.stringAt(expect, "error");
        Map<String, Object> variables = Json.mapAt(testCase, "variables");
        boolean render = testCase.containsKey("variables");

        Prompt prompt;
        try {
            prompt = Resolver.resolve(
                    document,
                    Json.stringAt(testCase, "prompt_key"),
                    Json.stringAt(testCase, "template"),
                    PromptSource.REMOTE,
                    null);
        } catch (PromptException e) {
            assertEquals(expectedError, e.reason().wireName(), name);
            if (expect.containsKey("key")) {
                assertEquals(Json.stringAt(expect, "key"), e.key(), name);
            }
            if (expect.containsKey("template")) {
                assertEquals(Json.stringAt(expect, "template"), e.template(), name);
            }
            if (expect.containsKey("template_names")) {
                assertEquals(Json.listAt(expect, "template_names"), e.templateNames(), name);
            }
            return;
        }

        Object rendered = null;
        if (render) {
            try {
                rendered = switch (prompt.kind()) {
                    case CHAT -> Template.renderMessages(
                            prompt.messages(), variables, prompt.engine());
                    case TEXT -> Template.render(
                            prompt.textTemplate(), variables, prompt.engine());
                    case DECISION -> prompt.decision(variables).toMap();
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

        assertEquals(Json.stringAt(expect, "key"), prompt.key(), name);
        assertEquals(Json.stringAt(expect, "deployment_id"), prompt.deploymentId(), name);
        assertEquals(Json.stringAt(expect, "revision"), prompt.deploymentRevision(), name);
        assertEquals(Json.stringAt(expect, "kind"), prompt.kind().wireName(), name);
        assertEquals(Json.stringAt(expect, "template"), prompt.template(), name);
        assertEquals(Json.listAt(expect, "template_names"), prompt.templateNames(), name);
        assertEquals(Json.stringAt(expect, "model"), prompt.model(), name);
        assertEquals(Json.stringAt(expect, "model_id"), prompt.modelId(), name);
        assertEquals(Json.stringAt(expect, "provider"), prompt.provider(), name);
        assertEquals(PromptSource.from(Json.stringAt(expect, "source")), prompt.source(), name);
        assertEquals(
                Json.canonical(Json.mapAt(expect, "params")),
                Json.canonical(prompt.params()),
                name + " params");
        assertEquals(
                Json.canonical(Json.mapAt(expect, "provider_options")),
                Json.canonical(prompt.providerOptions()),
                name + " provider_options");

        Map<String, Object> version = Json.mapAt(expect, "prompt_version");
        assertEquals(
                version == null ? null : Json.stringAt(version, "id"),
                prompt.promptVersionId(),
                name + " prompt_version.id");
        assertEquals(
                version == null ? null : Json.intAt(version, "number", null),
                prompt.promptVersionNumber(),
                name + " prompt_version.number");

        List<Object> warnings = Json.listAt(expect, "warnings");
        assertEquals(warnings, List.copyOf(prompt.warnings()), name + " warnings");

        List<Object> expectedMessages = Json.listAt(expect, "messages");
        if (expectedMessages != null) {
            List<Message> actual = render
                    ? castMessages(rendered)
                    : prompt.messages();
            List<Object> asMaps = new ArrayList<>();
            for (Message message : actual) {
                asMaps.add(message.toMap());
            }
            assertEquals(Json.canonical(expectedMessages), Json.canonical(asMaps), name + " messages");
        }
        String expectedText = Json.stringAt(expect, "text");
        if (expectedText != null) {
            assertEquals(expectedText, render ? (String) rendered : prompt.textTemplate(), name);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Message> castMessages(Object rendered) {
        return (List<Message>) rendered;
    }
}
