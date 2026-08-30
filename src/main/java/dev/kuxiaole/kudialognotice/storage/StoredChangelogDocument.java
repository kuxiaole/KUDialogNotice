package dev.kuxiaole.kudialognotice.storage;

import java.time.Instant;
import java.util.Objects;

/**
 * The immutable document currently stored in MariaDB.
 *
 * <p>The payload is the canonical UTF-8 YAML representation.  It is kept as
 * text here instead of a mutable byte array so that callers cannot alter a
 * value after it has crossed the storage boundary.</p>
 */
public record StoredChangelogDocument(
        String noticeId,
        long revision,
        String canonicalPayload,
        String sha256,
        String sourceServerId,
        Instant updatedAt
) {
    public StoredChangelogDocument {
        noticeId = Objects.requireNonNull(noticeId, "noticeId");
        canonicalPayload = Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        sourceServerId = Objects.requireNonNull(sourceServerId, "sourceServerId");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
