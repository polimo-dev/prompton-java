package dev.polimo.prompton;

import dev.polimo.prompton.internal.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native Decision state and questions for the Decisions API. */
public record Decision(Object state, Map<String, Object> questions) {

    /** Builds a Decision and preserves question keys literally. */
    public Decision {
        state = immutableJson(state);
        questions = questions == null ? Map.of() : immutableStringMap(questions);
    }

    /** Builds a Decision from a decoded prompt document map. */
    static Decision fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Map<String, Object> questions = Json.mapAt(map, "questions");
        if (questions == null) {
            return null;
        }
        return new Decision(map.get("state"), questions);
    }

    /** The wire form sent to the provider. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("state", state);
        map.put("questions", questions);
        return map;
    }

    private static Object immutableJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), immutableJson(item)));
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(immutableJson(item));
            }
            return Collections.unmodifiableList(out);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableStringMap(Map<String, Object> map) {
        return (Map<String, Object>) immutableJson(map);
    }
}
