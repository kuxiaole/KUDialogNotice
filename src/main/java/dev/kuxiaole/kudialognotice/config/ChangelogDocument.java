package dev.kuxiaole.kudialognotice.config;

import java.util.Objects;

/**
 * Immutable representation of the complete changelog file used for
 * replication. The parsed configuration is kept alongside the canonical YAML
 * so consumers never have to parse the same payload twice.
 */
public record ChangelogDocument(
        ChangelogConfig configuration,
        String canonicalPayload,
        String sha256
) {
    public ChangelogDocument {
        configuration = Objects.requireNonNull(configuration, "configuration");
        canonicalPayload = ChangelogCodec.canonicalize(
                Objects.requireNonNull(canonicalPayload, "canonicalPayload"));
        String computedHash = ChangelogCodec.sha256(canonicalPayload);
        if (sha256 != null && !computedHash.equalsIgnoreCase(sha256)) {
            throw new IllegalArgumentException("changelog payload SHA-256 does not match its content");
        }
        sha256 = computedHash;
    }

    /** Construct a document and calculate its digest. */
    public ChangelogDocument(ChangelogConfig configuration, String canonicalPayload) {
        this(configuration, canonicalPayload, null);
    }

    /** Convenience accessor for storage code that does not need the record name. */
    public ChangelogConfig config() {
        return configuration;
    }

    public long revision() {
        return configuration.revision();
    }

    public String versionLabel() {
        return configuration.versionLabel();
    }

    /** Return a defensive UTF-8 copy suitable for JDBC/file I/O. */
    public byte[] payloadBytes() {
        return ChangelogCodec.utf8(canonicalPayload);
    }

    public boolean hasSameContent(ChangelogDocument other) {
        return other != null
                && sha256.equalsIgnoreCase(other.sha256)
                && canonicalPayload.equals(other.canonicalPayload);
    }
}
