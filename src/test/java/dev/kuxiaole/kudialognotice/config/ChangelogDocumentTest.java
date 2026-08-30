package dev.kuxiaole.kudialognotice.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogDocumentTest {
    @Test
    void calculatesAndNormalizesItsDigestFromCanonicalPayload() {
        String payload = "\uFEFFrevision: 7\r\nversion-label: \"v7\"\n";
        ChangelogDocument document = new ChangelogDocument(configuration(7), payload);

        assertEquals("revision: 7\nversion-label: \"v7\"\n", document.canonicalPayload());
        assertEquals(7L, document.revision());
        assertEquals("v7", document.versionLabel());
        assertEquals(ChangelogCodec.sha256(document.canonicalPayload()), document.sha256());
    }

    @Test
    void acceptsCaseInsensitiveMatchingDigestAndRejectsAFalseDigest() {
        String payload = "revision: 3\n";
        String digest = ChangelogCodec.sha256(payload);

        ChangelogDocument accepted = new ChangelogDocument(configuration(3), payload, digest.toUpperCase());
        assertEquals(digest, accepted.sha256());
        assertThrows(IllegalArgumentException.class,
                () -> new ChangelogDocument(configuration(3), payload, "0".repeat(64)));
    }

    @Test
    void payloadBytesAreDefensiveCopiesAndContentComparisonIsDigestBased() {
        String payload = "revision: 4\n";
        ChangelogDocument first = new ChangelogDocument(configuration(4), payload);
        ChangelogDocument second = new ChangelogDocument(configuration(4), payload.replace("\n", "\r\n"));

        byte[] bytes = first.payloadBytes();
        byte[] original = bytes.clone();
        bytes[0] ^= 0x01;

        assertArrayEquals(original, first.payloadBytes());
        assertNotSame(first.payloadBytes(), first.payloadBytes());
        assertTrue(first.hasSameContent(second));
    }

    @Test
    void legacyConfigurationConstructorProducesAParseableCompleteDocument() throws ConfigurationException {
        ChangelogConfig config = configuration(9);
        String serialized = ChangelogCodec.serialize(config);

        assertEquals(config,
                new ConfigurationLoader(null).loadChangelogDocument(serialized).configuration());
    }

    @Test
    void remotePayloadStillRunsTheFullYamlAndMiniMessageValidation() {
        ConfigurationLoader loader = new ConfigurationLoader(null);

        assertThrows(ConfigurationException.class,
                () -> loader.loadChangelogDocument("revision: [\n"));
        assertThrows(ConfigurationException.class,
                () -> loader.loadChangelogDocument("""
                        enabled: true
                        revision: 1
                        version-label: "v1"
                        title: "<green>unterminated"
                        body:
                          - "<white>ok</white>"
                        acknowledge-button: "<green>ok</green>"
                        acknowledge-tooltip: "<gray>ok</gray>"
                        """));
        assertThrows(ConfigurationException.class,
                () -> loader.loadChangelogDocument("""
                        enabled: true
                        version-label: "v1"
                        title: "<green>title</green>"
                        body:
                          - "<white>ok</white>"
                        acknowledge-button: "<green>ok</green>"
                        acknowledge-tooltip: "<gray>ok</gray>"
                        """));
    }

    private static ChangelogConfig configuration(long revision) {
        return new ChangelogConfig(
                true,
                revision,
                "v" + revision,
                "<aqua>更新日志</aqua>",
                List.of("<white>内容 " + revision + "</white>"),
                "<green>已知晓</green>",
                "<gray>确认</gray>");
    }
}
