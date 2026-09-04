package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.polimo.prompton.internal.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Replays {@code conformance/truncation.json}: the payload policy applied before sending. */
class TruncationConformanceTest {

    private static final Map<String, Object> FILE = Conformance.load("truncation.json");

    @TestFactory
    List<DynamicTest> truncationCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> testCase : Conformance.cases(FILE, "cases")) {
            String name = Json.stringAt(testCase, "name");
            tests.add(DynamicTest.dynamicTest(name, () -> {
                Map<String, Object> config = Json.mapAt(testCase, "config");
                Payload.Options options = new Payload.Options(
                        config != null && Boolean.TRUE.equals(config.get("hash_end_user")), null);
                Map<String, Object> actual = Payload.apply(
                        Json.mapAt(testCase, "generation"),
                        PayloadPolicy.fromMap(Json.mapAt(testCase, "policy")),
                        options);
                Map<String, Object> expected =
                        Json.mapAt(Json.mapAt(testCase, "expect"), "generation");
                assertEquals(Json.canonical(expected), Json.canonical(actual), name);
            }));
        }
        assertEquals(19, tests.size(), "every truncation case must run");
        return tests;
    }

    @TestFactory
    List<DynamicTest> samplingBuckets() {
        List<Object> buckets = Json.listAt(Json.mapAt(FILE, "sampling"), "buckets");
        List<DynamicTest> tests = new ArrayList<>();
        for (Object entry : buckets) {
            Map<String, Object> bucket = Conformance.map(entry);
            String id = Json.stringAt(bucket, "id");
            tests.add(DynamicTest.dynamicTest("bucket(\"" + id + "\")", () ->
                    assertEquals(Json.intAt(bucket, "bucket", null), Payload.bucket(id))));
        }
        assertEquals(5, tests.size(), "every sampling bucket must run");
        return tests;
    }
}
