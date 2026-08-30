package dev.kuxiaole.kudialognotice.storage;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogEventCodecTest {
    private static final String HASH = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    void eventRoundTripPreservesAllWireFields() {
        UUID node = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ChangelogEvent event = new ChangelogEvent(
                "authority:namespace",
                "global",
                42L,
                HASH.toUpperCase(),
                "survival-1",
                node,
                eventId);

        String encoded = ChangelogEventCodec.encode(event);
        ChangelogEvent decoded = ChangelogEventCodec.decode(encoded).orElseThrow();

        assertEquals(event, decoded);
        assertEquals(HASH, decoded.sha256());
    }

    @Test
    void malformedOrUnsafeEventsAreIgnored() {
        assertEmpty(null);
        assertEmpty("");
        assertEmpty("0|namespace|global|1|" + HASH + "|survival-1|"
                + UUID.randomUUID() + "|" + UUID.randomUUID());
        assertEmpty("1|namespace|global|-1|" + HASH + "|survival-1|"
                + UUID.randomUUID() + "|" + UUID.randomUUID());
        assertEmpty("1|namespace|global|1|not-a-hash|survival-1|"
                + UUID.randomUUID() + "|" + UUID.randomUUID());
        assertEmpty("1|namespace|global|1|" + HASH + "|UPPER|"
                + UUID.randomUUID() + "|" + UUID.randomUUID());
        assertEmpty("1|namespace|contains|delimiter|1|" + HASH + "|survival-1|"
                + UUID.randomUUID() + "|" + UUID.randomUUID());
        assertEmpty("1|namespace|global|1|" + HASH + "|survival-1|not-a-uuid|"
                + UUID.randomUUID());
    }

    @Test
    void decoderRejectsOversizedMessagesAndEncoderRejectsDelimiterInjection() {
        assertEmpty("x".repeat(1025));

        ChangelogEvent unsafe = new ChangelogEvent(
                "namespace|injected",
                "global",
                1L,
                HASH,
                "survival-1",
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> ChangelogEventCodec.encode(unsafe));

        ChangelogEvent badHash = new ChangelogEvent(
                "namespace",
                "global",
                1L,
                "not-a-sha256",
                "survival-1",
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> ChangelogEventCodec.encode(badHash));
    }

    @Test
    void eventConstructorRejectsNegativeRevisionAndNulls() {
        assertThrows(IllegalArgumentException.class, () -> new ChangelogEvent(
                "namespace", "global", -1L, HASH, "survival-1",
                UUID.randomUUID(), UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> new ChangelogEvent(
                null, "global", 1L, HASH, "survival-1",
                UUID.randomUUID(), UUID.randomUUID()));
    }

    private static void assertEmpty(String value) {
        Optional<ChangelogEvent> decoded = ChangelogEventCodec.decode(value);
        assertTrue(decoded.isEmpty(), () -> "expected malformed event: " + value);
    }
}
