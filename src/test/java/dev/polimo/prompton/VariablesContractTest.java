package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.*;

import dev.polimo.prompton.internal.Json;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

final class VariablesContractTest {
    static final String DETAIL = """
        {"schema_version":1,"project":"p","environment":"production",
         "prompt":{"key":"hello","kind":"chat","variables":[
           {"name":"name","type":"string","required":true,"description":"Display name","example":"Ada"}]},
         "deployment":{"revision":"v2026.10.14-1","provider":"openrouter","model":"m","prompt_version":1},
         "prompt_version":{"prompt":"hello","number":1,"kind":"chat","engine":"liquid",
                           "messages":[{"role":"user","content":"Hello {{ name }}"}]},
         "model":{"provider":"openrouter","model":"m"}}
        """;

    @Test void schemaOneDetailRenders() {
        PromptDocument doc = PromptDocument.parse(DETAIL);
        assertEquals(1, doc.schemaVersion());
        assertEquals("Hello Ada", Resolver.resolve(doc, "hello").messages(Map.of("name", "Ada")).get(0).content());
        Map<String, Object> definition = Map.of("name", "name", "type", "string", "required", true,
                "description", "Display name", "example", "Ada");
        assertEquals(List.of(definition), doc.prompts().get("hello").variables());
        assertEquals(List.of(definition), Resolver.resolve(doc, "hello").variables());
    }

    @Test void oldMetadataIsNotAnAliasAndEmptyVariablesStayEmpty() {
        PromptDocument old = PromptDocument.parse(DETAIL.replace("\"variables\"", "\"input_schema\""));
        assertEquals(List.of(), old.prompts().get("hello").variables());
        String empty = DETAIL.replaceAll("(?s)\"variables\":\\[.*?]", "\"variables\":[]");
        assertEquals(List.of(), PromptDocument.parse(empty).prompts().get("hello").variables());
    }

    @Test void offlineBundleRetainsTheSameVariableDefinitions() {
        Map<String, Object> detail = Json.parseObject(DETAIL);
        Map<String, Object> bundle = Map.of("schema_version", 1, "project", "p", "environment", "production",
                "prompts", Map.of("hello", detail.get("prompt")),
                "deployments", Map.of("hello", detail.get("deployment")),
                "prompt_versions", Map.of("hello@1", detail.get("prompt_version")),
                "models", Map.of("openrouter:m", detail.get("model")));
        assertEquals(PromptDocument.parse(DETAIL).prompts().get("hello").variables(),
                PromptDocument.fromMap(bundle).prompts().get("hello").variables());
    }

    @Test void onlyIntegerSchemaOneIsAccepted() {
        for (String value : new String[] {"\"1\"", "1.0", "true", "null"}) {
            assertThrows(PromptOnException.class,
                    () -> PromptDocument.parse(DETAIL.replace("\"schema_version\":1", "\"schema_version\":" + value)));
        }
    }

    @Test void rejectsEveryPreviousSchemaVersion() {
        for (int version : new int[] {0, 2, 3, 4, 5, 6, 7, 8, 9, 10}) {
            String body = DETAIL.replace("\"schema_version\":1", "\"schema_version\":" + version);
            assertThrows(PromptOnException.class, () -> PromptDocument.parse(body), "schema " + version);
        }
    }

    @Test void discoveryIndexIsNotConfiguration() {
        for (String entries : new String[] {"[]", "[{\"key\":\"hello\",\"kind\":\"chat\"}]"}) {
            String body = "{\"schema_version\":1,\"project\":\"p\",\"environment\":\"production\",\"prompts\":" + entries + "}";
            assertThrows(PromptOnException.class, () -> PromptDocument.parse(body));
        }
    }
}
