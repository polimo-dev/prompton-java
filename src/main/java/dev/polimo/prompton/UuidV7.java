package dev.polimo.prompton;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * RFC 9562 UUIDv7 generator.
 *
 * <p>A monitoring log's {@code id} is an idempotency key the app issues before the provider call,
 * and PromptOn stores it in a UUIDv7 column: a v4 id is accepted by request validation and then
 * fails on write, coming back in {@code rejected} with a message that does not say why. So the SDK
 * generates v7 — 48 bits of unix milliseconds, the version nibble 7, then random bits — which also
 * makes ids sort by time.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private UuidV7() {}

    /** A new UUIDv7 as lowercase hex with dashes. */
    public static String generate() {
        return generate(System.currentTimeMillis());
    }

    /** A new UUIDv7 stamped with the given unix milliseconds (visible for testing). */
    public static String generate(long unixMillis) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        long ms = unixMillis & 0xFFFFFFFFFFFFL;
        bytes[0] = (byte) (ms >>> 40);
        bytes[1] = (byte) (ms >>> 32);
        bytes[2] = (byte) (ms >>> 24);
        bytes[3] = (byte) (ms >>> 16);
        bytes[4] = (byte) (ms >>> 8);
        bytes[5] = (byte) ms;
        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x70);
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);

        StringBuilder out = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) {
                out.append('-');
            }
            int b = bytes[i] & 0xFF;
            out.append(HEX[b >>> 4]).append(HEX[b & 0x0F]);
        }
        return out.toString();
    }

    /** Whether {@code id} looks like a UUID whose version nibble is 7. */
    public static boolean isUuidV7(String id) {
        if (id == null || id.length() != 36) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 36; i++) {
            char c = lower.charAt(i);
            boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
            if (dash != (c == '-')) {
                return false;
            }
            if (!dash && Character.digit(c, 16) < 0) {
                return false;
            }
        }
        return lower.charAt(14) == '7';
    }

    /** The unix milliseconds encoded in a UUIDv7, or {@code null} when {@code id} is not one. */
    public static Long timestampMillis(String id) {
        if (!isUuidV7(id)) {
            return null;
        }
        String hex = id.substring(0, 8) + id.substring(9, 13);
        return Long.parseLong(hex, 16);
    }
}
