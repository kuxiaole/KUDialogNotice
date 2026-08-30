package dev.kuxiaole.kudialognotice.storage;

import java.util.Objects;
import java.util.UUID;

/** Immutable, small metadata message used to invalidate changelog replicas. */
public record ChangelogEvent(
        String namespace,
        String noticeId,
        long revision,
        String sha256,
        String sourceServerId,
        UUID sourceNodeId,
        UUID eventId
) {
    public ChangelogEvent {
        namespace = Objects.requireNonNull(namespace, "namespace");
        noticeId = Objects.requireNonNull(noticeId, "noticeId");
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(java.util.Locale.ROOT);
        sourceServerId = Objects.requireNonNull(sourceServerId, "sourceServerId");
        sourceNodeId = Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        eventId = Objects.requireNonNull(eventId, "eventId");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }
}
