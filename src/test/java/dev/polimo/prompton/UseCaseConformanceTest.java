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

/** Replays {@code conformance/use_case.json}: use-case document + use case (+ prompt) to a use case. */
class UseCaseConformanceTest {

    private static final Map<String, Object> FILE = Conformance.load("use_case.json");

    @TestFactory
    List<DynamicTest> useCaseCases() {
        Map<String, Object> documents = Json.mapAt(FILE, "documents");
        Map<String, UseCaseDocument> parsed = new LinkedHashMap<>();
        documents.forEach((name, doc) -> parsed.put(name, UseCaseDocument.fromMap(Conformance.map(doc))));

        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> testCase : Conformance.cases(FILE, "cases")) {
            String name = Json.stringAt(testCase, "name");
            tests.add(DynamicTest.dynamicTest(name, () -> runCase(parsed, testCase)));
        }
        assertEquals(15, tests.size(), "every use-case case must run");
        return tests;
    }

    private void runCase(Map<String, UseCaseDocument> documents, Map<String, Object> testCase) {
        String name = Json.stringAt(testCase, "name");
        UseCaseDocument document = documents.get(Json.stringAt(testCase, "document_ref"));
        Map<String, Object> expect = Json.mapAt(testCase, "expect");
        String expectedError = Json.stringAt(expect, "error");
        Map<String, Object> variables = Json.mapAt(testCase, "variables");
        boolean render = testCase.containsKey("variables");

        UseCase useCase;
        try {
            useCase = Resolver.resolve(
                    document,
                    Json.stringAt(testCase, "use_case"),
                    Json.stringAt(testCase, "prompt"),
                    Source.REMOTE,
                    null);
        } catch (UseCaseException e) {
            assertEquals(expectedError, e.reason().wireName(), name);
            if (expect.containsKey("key")) {
                assertEquals(Json.stringAt(expect, "key"), e.key(), name);
            }
            if (expect.containsKey("prompt")) {
                assertEquals(Json.stringAt(expect, "prompt"), e.prompt(), name);
            }
            if (expect.containsKey("prompt_names")) {
                assertEquals(Json.listAt(expect, "prompt_names"), e.promptNames(), name);
            }
            return;
        }

        Object rendered = null;
        if (render) {
            try {
                rendered = switch (useCase.kind()) {
                    case CHAT -> Template.renderMessages(
                            useCase.messages(), variables, useCase.engine());
                    case TEXT -> Template.render(
                            useCase.textTemplate(), variables, useCase.engine());
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

        assertEquals(Json.stringAt(expect, "key"), useCase.key(), name);
        assertEquals(Json.stringAt(expect, "deployment_id"), useCase.deploymentId(), name);
        assertEquals(Json.intAt(expect, "revision", null), useCase.deploymentRevision(), name);
        assertEquals(Json.stringAt(expect, "kind"), useCase.kind().wireName(), name);
        assertEquals(Json.stringAt(expect, "prompt"), useCase.prompt(), name);
        assertEquals(Json.listAt(expect, "prompt_names"), useCase.promptNames(), name);
        assertEquals(Json.stringAt(expect, "model"), useCase.model(), name);
        assertEquals(Json.stringAt(expect, "model_id"), useCase.modelId(), name);
        assertEquals(Json.stringAt(expect, "provider"), useCase.provider(), name);
        assertEquals(Source.from(Json.stringAt(expect, "source")), useCase.source(), name);
        assertEquals(
                Json.canonical(Json.mapAt(expect, "params")),
                Json.canonical(useCase.params()),
                name + " params");
        assertEquals(
                Json.canonical(Json.mapAt(expect, "provider_options")),
                Json.canonical(useCase.providerOptions()),
                name + " provider_options");

        Map<String, Object> version = Json.mapAt(expect, "prompt_version");
        assertEquals(
                version == null ? null : Json.stringAt(version, "id"),
                useCase.promptVersionId(),
                name + " prompt_version.id");
        assertEquals(
                version == null ? null : Json.intAt(version, "number", null),
                useCase.promptVersionNumber(),
                name + " prompt_version.number");

        List<Object> warnings = Json.listAt(expect, "warnings");
        assertEquals(warnings, List.copyOf(useCase.warnings()), name + " warnings");

        List<Object> expectedMessages = Json.listAt(expect, "messages");
        if (expectedMessages != null) {
            List<Message> actual = render
                    ? castMessages(rendered)
                    : useCase.messages();
            List<Object> asMaps = new ArrayList<>();
            for (Message message : actual) {
                asMaps.add(message.toMap());
            }
            assertEquals(Json.canonical(expectedMessages), Json.canonical(asMaps), name + " messages");
        }
        String expectedText = Json.stringAt(expect, "text");
        if (expectedText != null) {
            assertEquals(expectedText, render ? (String) rendered : useCase.textTemplate(), name);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Message> castMessages(Object rendered) {
        return (List<Message>) rendered;
    }
}
