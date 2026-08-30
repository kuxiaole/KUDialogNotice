package dev.kuxiaole.kudialognotice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogCodecTest {
    @Test
    void canonicalizesBomAndAllCommonLineEndingsBeforeHashing() {
        String unix = "\nrevision: 2\nbody:\n  - 更新\n";
        String mixed = "\uFEFF\rrevision: 2\r\nbody:\r\n  - 更新\r";

        assertEquals(unix, ChangelogCodec.canonicalize(mixed));
        assertEquals(ChangelogCodec.sha256(unix), ChangelogCodec.sha256(mixed));
        assertArrayEquals(
                unix.getBytes(StandardCharsets.UTF_8),
                ChangelogCodec.utf8(mixed));
    }

    @Test
    void hashesUseTheStandardSha256Digest() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ChangelogCodec.sha256("abc"));
        assertEquals(
                ChangelogCodec.sha256("中文 <click:open_url:'https://example.test'>链接</click>"),
                ChangelogCodec.sha256(
                        "中文 <click:open_url:'https://example.test'>链接</click>"
                                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMalformedUtf8InsteadOfReplacingBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> ChangelogCodec.canonicalize(new byte[]{(byte) 0xC3, 0x28}));
    }

    @Test
    void rejectsPayloadsOverTheReplicationLimit() {
        byte[] oversized = new byte[ChangelogCodec.MAX_PAYLOAD_BYTES + 1];
        Arrays.fill(oversized, (byte) 'x');

        assertThrows(IllegalArgumentException.class,
                () -> ChangelogCodec.canonicalize(oversized));
        assertThrows(IllegalArgumentException.class,
                () -> ChangelogCodec.canonicalize(
                        "x".repeat(ChangelogCodec.MAX_PAYLOAD_BYTES + 1)));
    }

    @Test
    void writesCanonicalPayloadAndLeavesNoTemporaryFile(@TempDir Path directory) throws IOException {
        Path destination = directory.resolve("changelog.yml");

        ChangelogCodec.writeAtomically(destination, "\uFEFFrevision: 2\r\n");

        assertEquals("revision: 2\n", Files.readString(destination, StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void invalidOrUnreplaceablePayloadDoesNotDestroyTheExistingFile(@TempDir Path directory) throws IOException {
        Path destination = directory.resolve("changelog.yml");
        Files.writeString(destination, "revision: 1\n", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> ChangelogCodec.writeAtomically(
                        destination, "x".repeat(ChangelogCodec.MAX_PAYLOAD_BYTES + 1)));
        assertEquals("revision: 1\n", Files.readString(destination, StandardCharsets.UTF_8));

        Path directoryDestination = directory.resolve("existing-directory");
        Files.createDirectory(directoryDestination);
        assertThrows(IOException.class,
                () -> ChangelogCodec.writeAtomically(directoryDestination, "revision: 2\n"));
        assertTrue(Files.isDirectory(directoryDestination));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(2L, files.count());
        }
    }
}
