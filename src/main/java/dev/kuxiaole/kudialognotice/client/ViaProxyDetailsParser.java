package dev.kuxiaole.kudialognotice.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;

final class ViaProxyDetailsParser {
    private ViaProxyDetailsParser() {
    }

    static OptionalInt parsePlayerProtocol(byte[] message) {
        try {
            JsonObject payload = JsonParser.parseString(new String(message, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!payload.has("specVersion") || payload.get("specVersion").getAsInt() != 1
                    || !payload.has("platformName") || !payload.has("version")) {
                return OptionalInt.empty();
            }
            int protocol = payload.get("version").getAsInt();
            return protocol > 0 ? OptionalInt.of(protocol) : OptionalInt.empty();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }
}

