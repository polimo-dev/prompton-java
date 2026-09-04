package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.polimo.prompton.internal.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Replays {@code conformance/stop_kind.json}: provider finish reason to PromptOn stop kind. */
class StopKindConformanceTest {

    @TestFactory
    List<DynamicTest> stopKindCases() {
        Map<String, Object> file = Conformance.load("stop_kind.json");
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> testCase : Conformance.cases(file, "cases")) {
            String raw = Json.stringAt(testCase, "finish_reason");
            String label = (raw == null ? "<null>" : "\"" + raw + "\"")
                    + " -> " + Json.stringAt(testCase, "stop_kind");
            tests.add(DynamicTest.dynamicTest(label, () -> {
                StopKind kind = StopKind.normalize(raw);
                assertEquals(Json.stringAt(testCase, "stop_kind"), kind.wireName(), label);
                assertEquals(testCase.get("truncated"), kind.truncated(), label + " truncated");
                assertEquals(kind, StopKind.normalize(kind.wireName()), label + " is idempotent");
            }));
        }
        assertEquals(22, tests.size(), "every stop_kind case must run");
        return tests;
    }
}
