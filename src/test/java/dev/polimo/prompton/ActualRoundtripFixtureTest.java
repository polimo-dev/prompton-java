package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.polimo.prompton.internal.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Optional check against server-captured schema-v1 fixtures. */
class ActualRoundtripFixtureTest {

    private static final Path FIXTURES = Path.of(System.getProperty(
            "promptonRoundtripFixtures",
            System.getenv().getOrDefault("PROMPTON_ROUNDTRIP_FIXTURES", "build/roundtrip-fixtures")));

    @Test
    void decodesActualFlatSingleDetailChatAndMatchesServerRenderRequest() throws Exception {
        assumeTrue(Files.isRegularFile(FIXTURES.resolve("chat-detail.json"))
                && Files.isRegularFile(FIXTURES.resolve("chat-variables.json"))
                && Files.isRegularFile(FIXTURES.resolve("chat-render.json")));
        Prompt prompt = Resolver.resolve(
                PromptDocument.parse(Files.readString(FIXTURES.resolve("chat-detail.json"))),
                "support_reply");
        PreparedRequest request = prompt.request(readMap("chat-variables.json"));
        Map<String, Object> expected = Conformance.map(
                Conformance.map(readMap("chat-render.json").get("request")).get("body"));

        assertEquals("chat_completions", request.api());
        assertEquals("/api/v1/chat/completions", request.path());
        assertEquals(expected, request.body());
    }

    @Test
    void decodesActualFlatSingleDetailDecisionAndMatchesServerRenderRequest() throws Exception {
        assumeTrue(Files.isRegularFile(FIXTURES.resolve("decision-detail.json"))
                && Files.isRegularFile(FIXTURES.resolve("decision-variables.json"))
                && Files.isRegularFile(FIXTURES.resolve("decision-render.json")));
        Prompt prompt = Resolver.resolve(
                PromptDocument.parse(Files.readString(FIXTURES.resolve("decision-detail.json"))),
                "decision_triage");
        PreparedRequest request = prompt.request(readMap("decision-variables.json"));
        Map<String, Object> expected = Conformance.map(
                Conformance.map(readMap("decision-render.json").get("request")).get("body"));

        assertEquals("decisions", request.api());
        assertEquals("/api/v1/systemone", request.path());
        assertEquals(expected, request.body());
    }

    private static Map<String, Object> readMap(String file) throws Exception {
        return Json.parseObject(Files.readString(FIXTURES.resolve(file)));
    }
}
