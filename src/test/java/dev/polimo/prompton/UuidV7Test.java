package dev.polimo.prompton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The record id must be a UUIDv7: a v4 is accepted by validation and then fails on write. */
class UuidV7Test {

    @Test
    void carriesVersionSevenAndTheRfcVariant() {
        UUID id = UUID.fromString(UuidV7.generate());
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void encodesTheUnixMillisecondsItWasStampedWith() {
        long millis = 1_777_000_000_123L;
        String id = UuidV7.generate(millis);
        assertEquals(millis, UuidV7.timestampMillis(id));
        assertTrue(UuidV7.isUuidV7(id));
    }

    @Test
    void sortsByTimeAcrossMilliseconds() {
        String earlier = UuidV7.generate(1_777_000_000_000L);
        String later = UuidV7.generate(1_777_000_000_001L);
        assertTrue(earlier.compareTo(later) < 0, earlier + " should sort before " + later);
    }

    @Test
    void isUniqueWithinAMillisecond() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(ids.add(UuidV7.generate(1_777_000_000_000L)), "generated a duplicate id");
        }
    }

    @Test
    void recognisesWhatIsNotAUuidV7() {
        assertFalse(UuidV7.isUuidV7(UUID.randomUUID().toString()), "a v4 is not a v7");
        assertFalse(UuidV7.isUuidV7("not-a-uuid"));
        assertFalse(UuidV7.isUuidV7(null));
        assertNull(UuidV7.timestampMillis("not-a-uuid"));
    }
}
