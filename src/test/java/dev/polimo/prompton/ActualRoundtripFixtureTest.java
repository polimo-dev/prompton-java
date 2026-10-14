package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.polimo.prompton.internal.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Optional check against server-captured flat schema-v9 fixtures. */
class ActualRoundtripFixtureTest {

    private static final Path FIXTURES = Path.of(System.getProperty(
            "promptonRoundtripFixtures",
            "/Users/ys/ws/.omx/artifacts/single-prompt-20261014/roundtrip"));

    @Test
    void decodesActualFlatSchemaNineChatAndMatchesServerRenderRequest() throws Exception {
        assumeTrue(Files.isDirectory(FIXTURES));
        UseCase useCase = Resolver.resolve(
                UseCaseDocument.parse(Files.readString(FIXTURES.resolve("chat-config.json"))),
                "support_reply");
        PreparedRequest request = useCase.request(readMap("chat-variables.json"));
        Map<String, Object> expected = Conformance.map(
                Conformance.map(readMap("chat-render.json").get("request")).get("body"));

        assertEquals("chat_completions", request.api());
        assertEquals("/api/v1/chat/completions", request.path());
        assertEquals(expected, request.body());
    }

    @Test
    void decodesActualFlatSchemaNineDecisionAndMatchesServerRenderRequest() throws Exception {
        assumeTrue(Files.isDirectory(FIXTURES));
        UseCase useCase = Resolver.resolve(
                UseCaseDocument.parse(Files.readString(FIXTURES.resolve("decision-config.json"))),
                "decision_triage");
        PreparedRequest request = useCase.request(readMap("decision-variables.json"));
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
