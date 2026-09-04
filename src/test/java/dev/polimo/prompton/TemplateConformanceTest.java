package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.polimo.prompton.internal.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Replays {@code conformance/template.json}: the Liquid subset PromptOn allows. */
class TemplateConformanceTest {

    private static final Map<String, Object> FILE = Conformance.load("template.json");

    @TestFactory
    List<DynamicTest> renderCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> testCase : Conformance.cases(FILE, "cases")) {
            String name = Json.stringAt(testCase, "name");
            tests.add(DynamicTest.dynamicTest(name, () -> runRenderCase(testCase)));
        }
        assertEquals(72, tests.size(), "every template case must run");
        return tests;
    }

    private void runRenderCase(Map<String, Object> testCase) {
        String name = Json.stringAt(testCase, "name");
        String source = Json.stringAt(testCase, "template");
        Template.Engine engine = Template.Engine.from(Json.stringAt(testCase, "engine"));
        Map<String, Object> variables = Json.mapAt(testCase, "variables");
        Map<String, Object> expect = Json.mapAt(testCase, "expect");
        boolean normative = !Boolean.FALSE.equals(testCase.get("normative"));

        String expectedOutput = Json.stringAt(expect, "output");
        String expectedError = Json.stringAt(expect, "error");

        if (expectedOutput != null) {
            String actual;
            try {
                actual = Template.render(source, variables, engine);
            } catch (TemplateException e) {
                if (normative) {
                    throw new AssertionError(name + " threw " + e.kind() + ": " + e.getMessage(), e);
                }
                // Documented alternative behaviour for a non-normative case.
                return;
            }
            if (normative) {
                assertEquals(expectedOutput, actual, name);
            }
            return;
        }

        try {
            String rendered = Template.render(source, variables, engine);
            fail(name + " should have failed with " + expectedError + " but rendered " + rendered);
        } catch (TemplateException e) {
            assertEquals(expectedError, e.kind().wireName(), name);
            String variable = Json.stringAt(expect, "variable");
            if (variable != null) {
                assertEquals(variable, e.variable(), name + " variable");
            }
        }
    }

    @TestFactory
    List<DynamicTest> lintCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> testCase : Conformance.cases(FILE, "lint_cases")) {
            String name = Json.stringAt(testCase, "name");
            tests.add(DynamicTest.dynamicTest(name, () -> {
                Map<String, Object> expect = Json.mapAt(testCase, "expect");
                List<Template.LintIssue> issues = Template.lint(Json.stringAt(testCase, "template"));
                if ("ok".equals(Json.stringAt(expect, "lint"))) {
                    assertEquals(List.of(), issues, name);
                    return;
                }
                List<Object> reasons = Json.listAt(expect, "reasons");
                assertEquals(reasons.size(), issues.size(), name + " reason count: " + issues);
                for (int i = 0; i < reasons.size(); i++) {
                    Map<String, Object> reason = Conformance.map(reasons.get(i));
                    assertEquals(Json.stringAt(reason, "kind"), issues.get(i).kind().wireName(), name);
                    assertEquals(Json.stringAt(reason, "value"), issues.get(i).value(), name);
                }
            }));
        }
        assertEquals(10, tests.size(), "every lint case must run");
        return tests;
    }

    @TestFactory
    List<DynamicTest> variableCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> testCase : Conformance.cases(FILE, "variables_cases")) {
            String name = Json.stringAt(testCase, "name");
            tests.add(DynamicTest.dynamicTest(name, () -> {
                Map<String, Object> expect = Json.mapAt(testCase, "expect");
                assertEquals(
                        Json.listAt(expect, "variables"),
                        Template.variables(Json.stringAt(testCase, "template")),
                        name);
            }));
        }
        assertEquals(5, tests.size(), "every variables case must run");
        return tests;
    }

    @Test
    void allowedTagsAndFiltersMatchTheContract() {
        assertTrue(Template.ALLOWED_FILTERS.containsAll(
                List.copyOf(Json.listAt(FILE, "allowed_filters").stream().map(String::valueOf).toList())));
        for (Object tag : Json.listAt(FILE, "allowed_tags")) {
            assertTrue(Template.ALLOWED_TAGS.contains(String.valueOf(tag)), "tag " + tag);
        }
    }
}
