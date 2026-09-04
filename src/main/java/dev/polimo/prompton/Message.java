package dev.polimo.prompton;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One chat message: a role, its content, and an optional name.
 *
 * @param role the message role, for example {@code system} or {@code user}
 * @param content the message content — the raw template on a {@link Resolution}, the rendered text
 *     after {@code render}
 * @param name the optional {@code name} field, or {@code null}
 */
public record Message(String role, String content, String name) {

    /** A message with no {@code name}. */
    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    /** A message with no {@code name}. */
    public static Message of(String role, String content) {
        return new Message(role, content, null);
    }

    /** This message with {@code content} replaced. */
    public Message withContent(String newContent) {
        return new Message(role, newContent, name);
    }

    /** The wire form: {@code role}, {@code content}, and {@code name} when it is set. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("content", content);
        if (name != null) {
            map.put("name", name);
        }
        return map;
    }

    /** Reads a message out of a snapshot or a request body. */
    public static Message fromMap(Map<String, Object> map) {
        Object role = map.get("role");
        Object content = map.get("content");
        Object name = map.get("name");
        return new Message(
                role == null ? "" : String.valueOf(role),
                content == null ? "" : String.valueOf(content),
                name == null ? null : String.valueOf(name));
    }
}
