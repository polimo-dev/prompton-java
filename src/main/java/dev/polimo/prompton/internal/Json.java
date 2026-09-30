package dev.polimo.prompton.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON helpers shared by the whole SDK.
 *
 * <p>Every document the SDK handles is a plain tree of {@link Map}, {@link List}, {@link String},
 * {@link Number}, {@link Boolean} and {@code null}. The canonical writer sorts map keys, which is
 * what the payload digests in the conformance suite are computed over.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Sorted keys, no whitespace: the canonical form the SDK hashes and measures. */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private Json() {}

    /** Parses a JSON object into a mutable map tree. */
    public static Map<String, Object> parseObject(String json) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON object: " + e.getMessage(), e);
        }
    }

    /** Parses any JSON value into a map/list/scalar tree. */
    public static Object parseValue(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
        }
    }

    /** Writes a value as JSON with no whitespace and no key reordering. */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("value is not JSON-encodable: " + e.getMessage(), e);
        }
    }

    /** Writes a value as canonical JSON: no whitespace, map keys sorted. */
    public static String canonical(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** Byte size of the canonical JSON encoding of {@code value}. */
    public static int canonicalSize(Object value) {
        return canonical(value).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Byte size of a UTF-8 string. */
    public static int byteSize(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** A deep mutable copy of a map/list tree; other values are returned as they are. */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }

    /** A deep mutable copy of a map, with every key stringified. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> copyMap(Map<String, ?> map) {
        return map == null ? new LinkedHashMap<>() : (Map<String, Object>) deepCopy(map);
    }

    /** {@code map.get(key)} as a map, or {@code null} when it is absent or another type. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapAt(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    /** {@code map.get(key)} as a list, or {@code null} when it is absent or another type. */
    @SuppressWarnings("unchecked")
    public static List<Object> listAt(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof List<?> ? (List<Object>) value : null;
    }

    /** {@code map.get(key)} as a string, or {@code null} when it is absent or not a string. */
    public static String stringAt(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String s ? s : null;
    }

    /** {@code map.get(key)} as an int, or {@code fallback}. */
    public static Integer intAt(Map<String, Object> map, String key, Integer fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** {@code map.get(key)} as a double, or {@code fallback}. */
    public static double doubleAt(Map<String, Object> map, String key, double fallback) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }
}
