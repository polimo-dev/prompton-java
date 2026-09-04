package dev.polimo.prompton;

import dev.polimo.prompton.internal.Json;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The payload policy the SDK applies to a monitoring log before it is queued.
 *
 * <p>The server re-checks with the same rules, but the SDK applies them first so the raw text never
 * travels: if the SDK's setting is more conservative, the SDK wins. The steps run in this order,
 * and they interact, so the order matters:
 *
 * <ol>
 *   <li>the keep decision — errors and {@code stop_kind: "length"} are always kept, everything else
 *       is sampled on a hash of the record id, so a resend decides the same way;
 *   <li>wrapping — a string {@code input} becomes {@code {"text": …}} and a string {@code output}
 *       {@code {"content": …}};
 *   <li>the mode — {@code none} drops input and output, {@code hash} replaces them with digests,
 *       {@code full} truncates;
 *   <li>the fixed 2048-byte cap on {@code error.message};
 *   <li>hashing {@code end_user_ref} when the option is set;
 *   <li>the application's own redact hook, last.
 * </ol>
 *
 * <p>Truncation caps, all derived from the policy's {@code max_bytes} (default 262144) and measured
 * in bytes: one message's {@code content} at {@code max(max_bytes / 8, 64)}; {@code input.messages}
 * as a whole and {@code input.text} at {@code max_bytes}; {@code input.variables},
 * {@code output.content} and {@code output.tool_calls} at {@code max(max_bytes / 4, 64)}. A string
 * over its cap keeps its head and tail around a {@code …[truncated N bytes]…} marker, trimmed back
 * to a UTF-8 character boundary; a map that lost bytes is flagged {@code "truncated": true}.
 */
public final class Payload {

    /** The fixed cap on {@code error.message}, independent of {@code max_bytes}. */
    public static final int ERROR_MESSAGE_MAX = 2048;

    private static final int SAMPLE_SCALE = 10_000;

    /** The SDK-side settings the policy needs beyond the use-case document's own. */
    public record Options(boolean hashEndUser, UnaryOperator<Map<String, Object>> redact) {

        /** No end-user hashing and no redact hook. */
        public static final Options DEFAULT = new Options(false, null);
    }

    private Payload() {}

    /** Applies {@code policy} to a monitoring log record and returns the record to send. */
    public static Map<String, Object> apply(
            Map<String, Object> log, PayloadPolicy policy, Options options) {
        PayloadPolicy effective = policy == null ? PayloadPolicy.DEFAULT : policy;
        Options opts = options == null ? Options.DEFAULT : options;
        Map<String, Object> gen = Json.copyMap(log);

        applyMode(gen, effective);
        capErrorMessage(gen);
        if (opts.hashEndUser()) {
            Object ref = gen.get("end_user_ref");
            if (ref != null) {
                gen.put("end_user_ref", sha256Hex(String.valueOf(ref)));
            }
        }
        return redact(gen, opts.redact());
    }

    private static Map<String, Object> redact(
            Map<String, Object> gen, UnaryOperator<Map<String, Object>> redact) {
        if (redact == null) {
            return gen;
        }
        try {
            Map<String, Object> redacted = redact.apply(gen);
            if (redacted == null) {
                dropPayload(gen);
                return gen;
            }
            return redacted;
        } catch (RuntimeException e) {
            dropPayload(gen);
            return gen;
        }
    }

    private static void applyMode(Map<String, Object> gen, PayloadPolicy policy) {
        if (policy.mode() == PayloadPolicy.Mode.NONE || !keep(gen, policy.sampleRate())) {
            dropPayload(gen);
            return;
        }
        wrapStrings(gen);
        if (policy.mode() == PayloadPolicy.Mode.HASH) {
            hashField(gen, "input");
            hashField(gen, "output");
        } else {
            truncate(gen, policy.maxBytes());
        }
    }

    private static void dropPayload(Map<String, Object> gen) {
        gen.remove("input");
        gen.remove("output");
    }

    private static void wrapStrings(Map<String, Object> gen) {
        if (gen.get("input") instanceof String text) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("text", text);
            gen.put("input", wrapped);
        }
        if (gen.get("output") instanceof String content) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("content", content);
            gen.put("output", wrapped);
        }
    }

    private static void hashField(Map<String, Object> gen, String key) {
        Object value = gen.get(key);
        if (value == null) {
            return;
        }
        String json = Json.canonical(value);
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("sha256", sha256Hex(json));
        digest.put("bytes", Json.byteSize(json));
        digest.put("hashed", true);
        gen.put(key, digest);
    }

    /**
     * Whether this record keeps its raw text. Errors and length truncations always do; everything
     * else is decided by {@link #bucket(String)} against the sample rate.
     */
    public static boolean keep(Map<String, Object> gen, double sampleRate) {
        if ("error".equals(asString(gen.get("status")))) {
            return true;
        }
        if ("length".equals(asString(gen.get("stop_kind")))) {
            return true;
        }
        if (sampleRate >= 1.0) {
            return true;
        }
        if (sampleRate <= 0.0) {
            return false;
        }
        return bucket(asString(gen.get("id"))) < Math.round(sampleRate * SAMPLE_SCALE);
    }

    /**
     * The sampling bucket, 0..9999: the first four bytes of {@code sha256(id)} read as an unsigned
     * big-endian 32-bit integer, modulo 10000. The server uses the same formula, so both sides
     * reach the same answer without talking to each other.
     */
    public static int bucket(String id) {
        byte[] digest = sha256(id == null ? "" : id);
        long value = ((long) (digest[0] & 0xFF) << 24)
                | ((long) (digest[1] & 0xFF) << 16)
                | ((long) (digest[2] & 0xFF) << 8)
                | (digest[3] & 0xFF);
        return (int) (value % SAMPLE_SCALE);
    }

    // ---------------------------------------------------------------------
    // full-mode truncation

    private static void truncate(Map<String, Object> gen, int maxBytes) {
        if (gen.get("input") instanceof Map<?, ?>) {
            gen.put("input", truncateInput(asMap(gen.get("input")), maxBytes));
        }
        if (gen.get("output") instanceof Map<?, ?>) {
            gen.put("output", truncateOutput(asMap(gen.get("output")), maxBytes));
        }
    }

    private static Map<String, Object> truncateInput(Map<String, Object> input, int maxBytes) {
        int perMessage = Math.max(maxBytes / 8, 64);
        int varLimit = Math.max(maxBytes / 4, 64);
        boolean truncated = false;

        if (input.get("messages") instanceof List<?> messages) {
            Result<List<Object>> result = truncateMessages(castList(messages), perMessage, maxBytes);
            input.put("messages", result.value());
            truncated |= result.truncated();
        }
        if (input.get("text") instanceof String text) {
            Result<String> result = truncateString(text, maxBytes);
            input.put("text", result.value());
            truncated |= result.truncated();
        }
        Object variables = input.get("variables");
        if (variables != null) {
            String json = Json.canonical(variables);
            int size = Json.byteSize(json);
            if (size > varLimit) {
                Map<String, Object> digest = new LinkedHashMap<>();
                digest.put("truncated", true);
                digest.put("sha256", sha256Hex(json));
                digest.put("bytes", size);
                input.put("variables", digest);
                truncated = true;
            }
        }
        if (truncated) {
            input.put("truncated", true);
        }
        return input;
    }

    private static Map<String, Object> truncateOutput(Map<String, Object> output, int maxBytes) {
        int limit = Math.max(maxBytes / 4, 64);
        boolean truncated = false;
        if (output.get("content") instanceof String content) {
            Result<String> result = truncateString(content, limit);
            output.put("content", result.value());
            truncated |= result.truncated();
        }
        if (output.get("tool_calls") instanceof List<?> calls) {
            Result<List<Object>> result = truncateToolCalls(castList(calls), limit);
            output.put("tool_calls", result.value());
            truncated |= result.truncated();
        }
        if (truncated) {
            output.put("truncated", true);
        }
        return output;
    }

    private static Result<List<Object>> truncateMessages(
            List<Object> messages, int perMessage, int totalLimit) {
        boolean truncated = false;
        List<Object> capped = new ArrayList<>(messages.size());
        for (Object entry : messages) {
            if (entry instanceof Map<?, ?>) {
                Result<Map<String, Object>> result = truncateMessage(asMap(entry), perMessage);
                capped.add(result.value());
                truncated |= result.truncated();
            } else {
                capped.add(entry);
            }
        }
        if (listJsonSize(capped) <= totalLimit) {
            return new Result<>(capped, truncated);
        }
        List<Object> stubbed = stubMiddle(capped, totalLimit);
        if (listJsonSize(stubbed) <= totalLimit) {
            return new Result<>(stubbed, true);
        }
        return new Result<>(dropMiddle(capped, totalLimit), true);
    }

    private static Result<Map<String, Object>> truncateMessage(Map<String, Object> message, int limit) {
        Object content = message.get("content");
        if (content instanceof String text) {
            Result<String> result = truncateString(text, limit);
            message.put("content", result.value());
            if (result.truncated()) {
                message.put("truncated", true);
            }
            return new Result<>(message, result.truncated());
        }
        if (content == null) {
            return new Result<>(message, false);
        }
        String json = Json.canonical(content);
        if (Json.byteSize(json) > limit) {
            message.put("content", truncateString(json, limit).value());
            message.put("truncated", true);
            return new Result<>(message, true);
        }
        return new Result<>(message, false);
    }

    /**
     * Empties the middle messages into byte-count stubs from the front, stopping as soon as the
     * list fits. The first message (the system prompt) and the last (the newest turn) always
     * survive, so a later middle message can survive intact.
     */
    private static List<Object> stubMiddle(List<Object> messages, int limit) {
        int count = messages.size();
        long running = listJsonSize(messages);
        List<Object> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object entry = messages.get(i);
            if (i > 0 && i < count - 1 && running > limit && entry instanceof Map<?, ?>) {
                Map<String, Object> original = asMap(entry);
                Map<String, Object> stub = Json.copyMap(original);
                stub.put("content", "…[truncated " + messageContentBytes(original) + " bytes]…");
                stub.put("truncated", true);
                running = running - Json.canonicalSize(original) + Json.canonicalSize(stub);
                out.add(stub);
            } else {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * Drops the middle entirely: the first message, one {@code …[N messages truncated]…} marker,
     * and as many original messages from the tail as still fit.
     */
    private static List<Object> dropMiddle(List<Object> messages, int limit) {
        if (messages.isEmpty()) {
            return messages;
        }
        Object first = messages.get(0);
        List<Object> rest = messages.subList(1, messages.size());
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("role", "system");
        marker.put("content", markerText(rest.size()));
        marker.put("truncated", true);

        int base = listJsonSize(List.of(first, marker));
        if (base <= limit) {
            List<Object> tail = tailWithin(rest, limit - base);
            Map<String, Object> sized = new LinkedHashMap<>(marker);
            sized.put("content", markerText(rest.size() - tail.size()));
            List<Object> out = new ArrayList<>();
            out.add(first);
            out.add(sized);
            out.addAll(tail);
            return out;
        }
        Object smaller = shrinkFirst(first);
        if (smaller == first) {
            Map<String, Object> only = new LinkedHashMap<>(marker);
            only.put("content", markerText(rest.size() + 1));
            return listJsonSize(List.of(only)) <= limit ? List.of(only) : List.of();
        }
        List<Object> retry = new ArrayList<>();
        retry.add(smaller);
        retry.addAll(rest);
        return dropMiddle(retry, limit);
    }

    private static Object shrinkFirst(Object message) {
        if (!(message instanceof Map<?, ?>)) {
            return message;
        }
        Map<String, Object> map = asMap(message);
        int bytes = messageContentBytes(map);
        if (bytes == 0) {
            Map<String, Object> minimal = new LinkedHashMap<>();
            Object role = map.get("role");
            if (role != null) {
                minimal.put("role", role);
            }
            minimal.put("truncated", true);
            return minimal.equals(map) ? message : minimal;
        }
        return truncateMessage(Json.copyMap(map), bytes / 2).value();
    }

    private static List<Object> tailWithin(List<Object> messages, int budget) {
        List<Object> kept = new ArrayList<>();
        int left = budget;
        for (int i = messages.size() - 1; i >= 0; i--) {
            int size = Json.canonicalSize(messages.get(i)) + 1;
            if (size > left) {
                break;
            }
            left -= size;
            kept.add(0, messages.get(i));
        }
        return kept;
    }

    private static String markerText(int dropped) {
        return "…[" + dropped + " messages truncated]…";
    }

    private static int messageContentBytes(Map<String, Object> message) {
        Object content = message.get("content");
        if (content == null) {
            return 0;
        }
        if (content instanceof String s) {
            return Json.byteSize(s);
        }
        return Json.canonicalSize(content);
    }

    private static Result<List<Object>> truncateToolCalls(List<Object> calls, int limit) {
        if (Json.canonicalSize(calls) <= limit) {
            return new Result<>(calls, false);
        }
        List<Object> emptied = new ArrayList<>(calls.size());
        for (Object call : calls) {
            emptied.add(withArguments(call, ""));
        }
        int overhead = Json.canonicalSize(emptied);
        int budget = Math.max(limit - overhead, 0) / Math.max(calls.size(), 1);
        return new Result<>(shrinkToolCalls(calls, budget, limit), true);
    }

    private static List<Object> shrinkToolCalls(List<Object> calls, int budget, int limit) {
        while (budget >= 32) {
            List<Object> shrunk = new ArrayList<>(calls.size());
            for (Object call : calls) {
                String arguments = argumentsOf(call);
                shrunk.add(arguments == null
                        ? call
                        : withArguments(call, truncateString(arguments, budget).value()));
            }
            if (Json.canonicalSize(shrunk) <= limit) {
                return shrunk;
            }
            budget = budget / 2;
        }
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("truncated", true);
        marker.put("bytes", Json.canonicalSize(calls));
        return List.of(marker);
    }

    private static String argumentsOf(Object call) {
        if (call instanceof Map<?, ?>) {
            Map<String, Object> function = Json.mapAt(asMap(call), "function");
            Object arguments = function == null ? null : function.get("arguments");
            return arguments instanceof String s ? s : null;
        }
        return null;
    }

    private static Object withArguments(Object call, String arguments) {
        if (argumentsOf(call) == null) {
            return call;
        }
        Map<String, Object> copy = Json.copyMap(asMap(call));
        Map<String, Object> function = Json.mapAt(copy, "function");
        function.put("arguments", arguments);
        return copy;
    }

    private static int listJsonSize(List<Object> list) {
        if (list.isEmpty()) {
            return 2;
        }
        int size = 1;
        for (Object item : list) {
            size += Json.canonicalSize(item) + 1;
        }
        return size;
    }

    private static void capErrorMessage(Map<String, Object> gen) {
        Map<String, Object> error = Json.mapAt(gen, "error");
        if (error == null) {
            return;
        }
        Object message = error.get("message");
        if (message instanceof String text && Json.byteSize(text) > ERROR_MESSAGE_MAX) {
            error.put("message", truncateString(text, ERROR_MESSAGE_MAX).value());
        }
    }

    // ---------------------------------------------------------------------
    // UTF-8-safe head/tail truncation

    /** The result of one truncation step. */
    private record Result<T>(T value, boolean truncated) {}

    /**
     * Truncates {@code text} to at most {@code limit} bytes, keeping its head and tail around a
     * marker. The result is always valid UTF-8 and never longer than the cap.
     *
     * @param text the string to shorten
     * @param limit the byte cap
     * @return the shortened string, or {@code text} when it already fits
     */
    public static String truncateToBytes(String text, int limit) {
        return truncateString(text, limit).value();
    }

    private static Result<String> truncateString(String text, int limit) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) {
            return new Result<>(text, false);
        }
        byte[] marker = ("\n…[truncated " + (bytes.length - limit) + " bytes]…\n")
                .getBytes(StandardCharsets.UTF_8);
        if (marker.length > limit) {
            return new Result<>(trimTrailingPartial(bytes, 0, limit), true);
        }
        int budget = limit - marker.length;
        int headBytes = budget * 6 / 10;
        int tailBytes = budget - headBytes;
        String head = trimTrailingPartial(bytes, 0, headBytes);
        String tail = trimLeadingPartial(bytes, bytes.length - tailBytes, tailBytes);
        return new Result<>(head + new String(marker, StandardCharsets.UTF_8) + tail, true);
    }

    private static String trimTrailingPartial(byte[] bytes, int offset, int length) {
        for (int len = length; len >= 0 && len >= length - 3; len--) {
            String decoded = decodeStrict(bytes, offset, len);
            if (decoded != null) {
                return decoded;
            }
        }
        return "";
    }

    private static String trimLeadingPartial(byte[] bytes, int offset, int length) {
        int start = offset;
        int end = offset + length;
        while (start < end && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        String decoded = decodeStrict(bytes, start, end - start);
        return decoded == null ? "" : decoded;
    }

    private static String decodeStrict(byte[] bytes, int offset, int length) {
        if (length <= 0) {
            return "";
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes, offset, length));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // digests

    /** Lowercase hex of {@code sha256(value)}. */
    public static String sha256Hex(String value) {
        byte[] digest = sha256(value);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(List<?> list) {
        return (List<Object>) list;
    }
}
