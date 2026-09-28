package dev.polimo.prompton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One chat message: native provider fields plus the common role/content/name convenience API. */
public final class Message {
    private final String role;
    private final String type;
    private final String content;
    private final Object contentValue;
    private final boolean hasContent;
    private final String name;
    private final String toolCallId;
    private final List<Map<String, Object>> toolCalls;
    private final Map<String, Object> extra;

    public Message(String role, String content, String name) {
        this(role, null, Objects.requireNonNull(content, "content"), content, true, name, null, List.of(), Map.of());
    }

    private Message(
            String role,
            String type,
            String content,
            Object contentValue,
            boolean hasContent,
            String name,
            String toolCallId,
            List<Map<String, Object>> toolCalls,
            Map<String, Object> extra) {
        this.role = role;
        this.type = type;
        this.content = content == null ? "" : content;
        this.contentValue = contentValue;
        this.hasContent = hasContent;
        this.name = name;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.extra =
                extra == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    public static Message of(String role, String content) {
        return new Message(role, content, null);
    }

    public String role() { return role; }
    public String type() { return type; }
    public String content() { return content; }
    public Object contentValue() { return hasContent ? contentValue : content; }
    public boolean hasContent() { return hasContent; }
    public String name() { return name; }
    public String toolCallId() { return toolCallId; }
    public List<Map<String, Object>> toolCalls() { return toolCalls; }
    public Map<String, Object> extra() { return extra; }

    public Message withContent(String newContent) {
        return new Message(role, type, newContent, newContent, true, name, toolCallId, toolCalls, extra);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(extra);
        if (type != null) map.put("type", type);
        if (role != null) map.put("role", role);
        if (hasContent || !"slot".equals(type)) map.put("content", contentValue());
        if (name != null) map.put("name", name);
        if (toolCallId != null) map.put("tool_call_id", toolCallId);
        if (!toolCalls.isEmpty()) map.put("tool_calls", toolCalls);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Message fromMap(Map<String, Object> map) {
        Object role = map.get("role");
        Object type = map.get("type");
        Object content = map.get("content");
        Object name = map.get("name");
        Object toolCallId = map.get("tool_call_id");
        List<Map<String, Object>> calls = new ArrayList<>();
        Object rawCalls = map.get("tool_calls");
        if (rawCalls instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> call) calls.add((Map<String, Object>) call);
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>(map);
        extra.keySet().removeAll(List.of("role", "type", "content", "name", "tool_call_id", "tool_calls"));
        return new Message(
                role == null ? null : String.valueOf(role),
                type == null ? null : String.valueOf(type),
                content instanceof String s ? s : "",
                content,
                map.containsKey("content"),
                name == null ? null : String.valueOf(name),
                toolCallId == null ? null : String.valueOf(toolCallId),
                calls,
                extra);
    }
}
