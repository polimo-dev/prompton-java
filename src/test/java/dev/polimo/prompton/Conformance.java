package dev.polimo.prompton;

import dev.polimo.prompton.internal.Json;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Loads the JSON conformance files copied from the reference implementation. */
final class Conformance {

    private Conformance() {}

    static Map<String, Object> load(String name) {
        try (InputStream in = Conformance.class.getResourceAsStream("/conformance/" + name)) {
            if (in == null) {
                throw new IllegalStateException("conformance/" + name + " is not on the test classpath");
            }
            return Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("could not read conformance/" + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cases(Map<String, Object> file, String key) {
        List<Object> raw = Json.listAt(file, key);
        if (raw == null) {
            throw new IllegalStateException("no " + key + " in this conformance file");
        }
        return (List<Map<String, Object>>) (List<?>) raw;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
