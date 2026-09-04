package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Provider result adapters that intentionally avoid provider SDK dependencies. */
class ResultTest {

    @Test
    void fromOpenAIReadsAChatCompletionShape() {
        Result result = Result.fromOpenAI(Map.of(
                "model", "gpt-4o-mini-2024-07-18",
                "choices", List.of(Map.of(
                        "finish_reason", "stop",
                        "message", Map.of(
                                "content", "Hello, Ada!",
                                "tool_calls", List.of(Map.of("id", "call_1"))))),
                "usage", Map.of(
                        "prompt_tokens", 12,
                        "completion_tokens", 4,
                        "total_tokens", 16)));

        assertEquals("Hello, Ada!", result.content());
        assertEquals("stop", result.finishReason());
        assertEquals(StopKind.STOP, result.stopKind());
        assertEquals("gpt-4o-mini-2024-07-18", result.modelUsed());
        assertEquals("OpenAI", result.upstreamProvider());
        assertEquals(12, result.usage().inputTokens());
        assertEquals(4, result.usage().outputTokens());
        assertEquals(1, result.toolCalls().size());
    }

    @Test
    void fromAnthropicReadsAMessageShape() {
        Result result = Result.fromAnthropic(Map.of(
                "model", "claude-3-5-sonnet-20241022",
                "stop_reason", "end_turn",
                "content", List.of(Map.of("type", "text", "text", "Hello, Ada!")),
                "usage", Map.of("input_tokens", 8, "output_tokens", 5)));

        assertEquals("Hello, Ada!", result.content());
        assertEquals("end_turn", result.finishReason());
        assertEquals(StopKind.STOP, result.stopKind());
        assertEquals("claude-3-5-sonnet-20241022", result.modelUsed());
        assertEquals("Anthropic", result.upstreamProvider());
        assertEquals(8, result.usage().inputTokens());
        assertEquals(5, result.usage().outputTokens());
    }
}
