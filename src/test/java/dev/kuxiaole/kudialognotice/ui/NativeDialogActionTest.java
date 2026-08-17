package dev.kuxiaole.kudialognotice.ui;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeDialogActionTest {
    @Test
    void everyActionRoundTripsWithItsSessionToken() {
        UUID token = UUID.randomUUID();

        for (UserAction action : UserAction.values()) {
            assertEquals(
                    new NativeDialogAction(token, action),
                    NativeDialogAction.parse(NativeDialogAction.key(action, token)).orElseThrow());
        }
    }

    @Test
    void rejectsMissingForeignOrMalformedSessionTokens() {
        assertTrue(NativeDialogAction.parse(Key.key("other", "rules_accept/" + UUID.randomUUID())).isEmpty());
        assertTrue(NativeDialogAction.parse(Key.key("kudialognotice", "rules_accept")).isEmpty());
        assertTrue(NativeDialogAction.parse(Key.key("kudialognotice", "rules_accept/not-a-uuid")).isEmpty());
        assertTrue(NativeDialogAction.parse(
                Key.key("kudialognotice", "unknown/" + UUID.randomUUID())).isEmpty());
    }
}
