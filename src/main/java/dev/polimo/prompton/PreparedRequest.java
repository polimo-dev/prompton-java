package dev.polimo.prompton;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A provider request prepared by the SDK. The SDK does not send it. */
public record PreparedRequest(String api, String method, String path, Map<String, Object> body) {

    /** Builds a prepared request, normalising {@code method} to uppercase. */
    public PreparedRequest {
        method = method == null ? "POST" : method.toUpperCase(Locale.ROOT);
        body = body == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(body));
    }

    /** The wire form returned by the render endpoint and useful for tests. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("api", api);
        map.put("method", method);
        map.put("path", path);
        map.put("body", body);
        return map;
    }
}
