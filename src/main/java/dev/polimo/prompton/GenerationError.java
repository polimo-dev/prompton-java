package dev.polimo.prompton;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What went wrong on a failed generation.
 *
 * @param kind the classification PromptOn groups error rates by
 * @param status the provider's HTTP status, when there was one
 * @param message a short description — never a secret, and capped at 2048 bytes before sending
 */
public record GenerationError(ErrorKind kind, Integer status, String message) {

    /** An error with no HTTP status. */
    public static GenerationError of(ErrorKind kind, String message) {
        return new GenerationError(kind, null, message);
    }

    /** An error whose kind follows from the provider's status. */
    public static GenerationError ofStatus(int status, String message) {
        return new GenerationError(ErrorKind.fromStatus(status), status, message);
    }

    /** The wire form; absent fields are omitted. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", (kind == null ? ErrorKind.APP : kind).wireName());
        if (status != null) {
            map.put("status", status);
        }
        if (message != null) {
            map.put("message", message);
        }
        return map;
    }
}
