package dev.kuxiaole.kudialognotice.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Canonicalization and hashing helpers for the replicated changelog document.
 *
 * <p>The document is deliberately kept as text rather than re-serialized from
 * {@link YamlConfiguration}: comments and formatting are part of the file that
 * is replicated. Only the platform line ending and an optional UTF-8 BOM are
 * normalized, so Windows and Linux instances produce the same digest.</p>
 */
public final class ChangelogCodec {
    /** Maximum replicated document size. This bounds both database and Redis work. */
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;

    private static final String UTF_8_BOM = "\uFEFF";

    private ChangelogCodec() {
    }

    /**
     * Canonicalize a UTF-8 payload and reject malformed input or oversized data.
     *
     * @param payload raw UTF-8 bytes
     * @return canonical text
     * @throws IllegalArgumentException when the payload is malformed or too large
     */
    public static String canonicalize(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "changelog.yml exceeds the maximum size of " + MAX_PAYLOAD_BYTES + " bytes");
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return canonicalize(decoder.decode(ByteBuffer.wrap(payload)).toString());
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("changelog.yml must be valid UTF-8", exception);
        }
    }

    /**
     * Canonicalize already decoded text. The returned value is safe to hash and
     * write back as UTF-8.
     */
    public static String canonicalize(String payload) {
        Objects.requireNonNull(payload, "payload");
        String canonical = payload;
        if (canonical.startsWith(UTF_8_BOM)) {
            canonical = canonical.substring(UTF_8_BOM.length());
        }
        canonical = canonical.replace("\r\n", "\n").replace('\r', '\n');

        byte[] encoded = encodeUtf8(canonical);
        if (encoded.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "changelog.yml exceeds the maximum size of " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return canonical;
    }

    /** Encode canonical text using a strict UTF-8 encoder. */
    public static byte[] utf8(String payload) {
        return encodeUtf8(canonicalize(payload));
    }

    /** Return the lowercase SHA-256 digest of canonical text. */
    public static String sha256(String payload) {
        return digest(utf8(payload));
    }

    /** Return the lowercase SHA-256 digest of bytes after canonicalization. */
    public static String sha256(byte[] payload) {
        return digest(encodeUtf8(canonicalize(payload)));
    }

    /**
     * Replace a changelog file only after the complete canonical payload has
     * reached disk. The temporary file is created beside the destination so a
     * move cannot cross file systems.
     */
    public static void writeAtomically(Path destination, String payload) throws IOException {
        Objects.requireNonNull(destination, "destination");
        String canonical = canonicalize(payload);
        byte[] bytes = encodeUtf8(canonical);
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("changelog destination has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".changelog.yml.", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException
                     | UnsupportedOperationException unsupported) {
                // Some filesystems do not advertise ATOMIC_MOVE. REPLACE keeps
                // the old file intact until the final rename operation.
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /**
     * Produce a valid payload for the legacy four-argument
     * {@link PluginConfiguration} constructor. Normal loading preserves the
     * operator's original text; this path is only for callers that construct a
     * configuration object directly (for example tests or integrations).
     */
    static String serialize(ChangelogConfig changelog) {
        Objects.requireNonNull(changelog, "changelog");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", changelog.enabled());
        yaml.set("revision", changelog.revision());
        yaml.set("version-label", changelog.versionLabel());
        yaml.set("title", changelog.title());
        yaml.set("body", changelog.body());
        yaml.set("acknowledge-button", changelog.acknowledgeButton());
        yaml.set("acknowledge-tooltip", changelog.acknowledgeTooltip());
        return canonicalize(yaml.saveToString());
    }

    private static byte[] encodeUtf8(String payload) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(payload));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("changelog.yml contains invalid Unicode", exception);
        }
    }

    private static String digest(byte[] canonicalBytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }
}
