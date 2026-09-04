package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Template behaviour the conformance suite does not pin down, but the SDK promises. */
class TemplateTest {

    @Test
    void renderingMessagesKeepsRoleAndName() {
        List<Message> rendered = Template.renderMessages(
                List.of(new Message("system", "Answer in {{ language }}.", null),
                        new Message("user", "{{ question }}", "ada")),
                Map.of("language", "Korean", "question", "why?"),
                Template.Engine.LIQUID);

        assertEquals("system", rendered.get(0).role());
        assertEquals("Answer in Korean.", rendered.get(0).content());
        assertEquals("ada", rendered.get(1).name());
        assertEquals("why?", rendered.get(1).content());
    }

    @Test
    void theRawEngineNeverParses() {
        String source = "{% include \"other\" %} {{ unclosed";
        assertEquals(source, Template.render(source, Map.of(), Template.Engine.RAW));
        assertEquals(Template.Engine.RAW, Template.Engine.from("raw"));
        assertEquals(Template.Engine.LIQUID, Template.Engine.from(null));
        assertEquals(Template.Engine.LIQUID, Template.Engine.from("anything else"));
    }

    @Test
    void aNullVariableIsNotAMissingVariable() {
        Map<String, Object> present = new HashMap<>();
        present.put("x", null);
        assertEquals("", Template.render("{{ x }}", present));
        assertEquals("fallback", Template.render("{{ x | default: \"fallback\" }}", present));

        TemplateException missing = assertThrows(TemplateException.class,
                () -> Template.render("{{ x }}", Map.of()));
        assertEquals(TemplateException.Kind.MISSING_VARIABLE, missing.kind());
        assertEquals("x", missing.variable());
    }

    @Test
    void aFilterOutsideTheSubsetIsRejected() {
        assertEquals(List.of(new Template.LintIssue(
                        Template.LintIssue.Kind.DISALLOWED_FILTER, "upcase")),
                Template.lint("{{ s | upcase }}"));
        assertThrows(TemplateException.class, () -> Template.render("{{ s | upcase }}", Map.of("s", "a")));
    }

    @Test
    void aRealisticMigratedPromptPassesLintAndRenders() {
        String source = """
            {% if language == "fr" %}Reponds en francais.{% else %}Answer in English.{% endif %}
            {% for note in notes %}- {{ note }}
            {% endfor %}Total: {{ notes | size }}""";

        assertEquals(List.of(), Template.lint(source));
        assertEquals(List.of("language", "notes"), Template.variables(source));
        assertEquals("""
            Answer in English.
            - a
            - b
            Total: 2""",
                Template.render(source, Map.of("language", "en", "notes", List.of("a", "b"))));
    }

    @Test
    void aTemplateThatDoesNotParseStillYieldsItsVariables() {
        assertTrue(Template.variables("{% if a %}{{ b }}").containsAll(List.of("a", "b")));
    }
}
