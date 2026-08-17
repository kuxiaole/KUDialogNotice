package dev.kuxiaole.kudialognotice.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViaProxyDetailsParserTest {
    @Test
    void parsesPlayerDetailsPayload() {
        byte[] payload = """
                {"specVersion":1,"platformName":"Velocity","platformVersion":"3.4.0",
                 "version":771,"versionName":"1.21.6","versionType":"RELEASE"}
                """.getBytes(StandardCharsets.UTF_8);

        assertEquals(771, ViaProxyDetailsParser.parsePlayerProtocol(payload).orElseThrow());
    }

    @Test
    void ignoresServerDetailsPayload() {
        byte[] payload = """
                {"specVersion":1,"version":1073742123,"versionName":"26.2","versionType":"SNAPSHOT"}
                """.getBytes(StandardCharsets.UTF_8);

        assertTrue(ViaProxyDetailsParser.parsePlayerProtocol(payload).isEmpty());
    }

    @Test
    void ignoresMalformedOrUnsupportedPayload() {
        assertTrue(ViaProxyDetailsParser.parsePlayerProtocol("not-json".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(ViaProxyDetailsParser.parsePlayerProtocol(
                "{\"specVersion\":2,\"platformName\":\"Velocity\",\"version\":771}"
                        .getBytes(StandardCharsets.UTF_8)).isEmpty());
    }
}

