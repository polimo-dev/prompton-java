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
    void messageSlotsAreRejectedBeforeRenderingForEveryEngine() {
        Message slotWithRole = Message.fromMap(Map.of(
                "type", "slot",
                "role", "system",
                "name", "history",
                "content", "ignored"));

        for (Template.Engine engine : Template.Engine.values()) {
            TemplateException thrown = assertThrows(TemplateException.class,
                    () -> Template.renderMessages(
                            List.of(slotWithRole),
                            Map.of("history", List.of(Map.of("role", "user", "content", "hi"))),
                            engine));
            assertEquals(TemplateException.Kind.RENDER_ERROR, thrown.kind());
            assertEquals(Template.MESSAGE_SLOT_ERROR, thrown.getMessage());
        }
    }

    @Test
    void historyIsAnOrdinaryVariableName() {
        List<Message> rendered = Template.renderMessages(
                List.of(new Message("system", "Remember {{ history }}.", null)),
                Map.of("history", "the user prefers concise replies"),
                Template.Engine.LIQUID);

        assertEquals("Remember the user prefers concise replies.", rendered.get(0).content());
    }

    @Test
    void nativeMessageTypeNameAndStructuredContentSurviveRendering() {
        Message nativeMessage = Message.fromMap(Map.of(
                "type", "message",
                "role", "assistant",
                "name", "lookup",
                "content", List.of(Map.of("type", "text", "text", "found")),
                "provider_extra", Map.of("opaque", true)));

        Message rendered = Template.renderMessages(
                List.of(nativeMessage),
                Map.of("history", "ignored"),
                Template.Engine.LIQUID).get(0);

        assertEquals(nativeMessage.toMap(), rendered.toMap());
    }

    @Test
    void nativeMessagesWithoutContentKeepContentAbsentDuringRenderingAndRequestSerialization() {
        Message absent = Message.fromMap(Map.of(
                "role", "assistant",
                "type", "native",
                "name", "helper",
                "reasoning", "opaque"));
        Map<String, Object> explicitNull = new java.util.LinkedHashMap<>();
        explicitNull.put("role", "assistant");
        explicitNull.put("content", null);
        Message nullContent = Message.fromMap(explicitNull);
        Message emptyString = Message.of("assistant", "");
        Message emptyArray = Message.fromMap(Map.of("role", "assistant", "content", List.of()));

        List<Message> rendered = Template.renderMessages(
                List.of(absent, nullContent, emptyString, emptyArray),
                Map.of("history", "ignored"),
                Template.Engine.LIQUID);

        Map<String, Object> absentRequest = rendered.get(0).toMap();
        assertEquals(Map.of("role", "assistant", "type", "native", "name", "helper", "reasoning", "opaque"),
                absentRequest);
        assertTrue(!absentRequest.containsKey("content"));
        assertTrue(rendered.get(1).toMap().containsKey("content"));
        assertEquals(null, rendered.get(1).toMap().get("content"));
        assertEquals("", rendered.get(2).toMap().get("content"));
        assertEquals(List.of(), rendered.get(3).toMap().get("content"));
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
