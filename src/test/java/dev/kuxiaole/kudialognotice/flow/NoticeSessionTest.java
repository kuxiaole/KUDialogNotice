package dev.kuxiaole.kudialognotice.flow;

import dev.kuxiaole.kudialognotice.config.ChangelogConfig;
import dev.kuxiaole.kudialognotice.config.MainConfig;
import dev.kuxiaole.kudialognotice.config.MessagesConfig;
import dev.kuxiaole.kudialognotice.config.PluginConfiguration;
import dev.kuxiaole.kudialognotice.config.RulesConfig;
import dev.kuxiaole.kudialognotice.storage.PlayerIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoticeSessionTest {
    @Test
    void onlyOneConcurrentTransitionWins() {
        NoticeSession session = new NoticeSession(
                new PlayerIdentity(UUID.randomUUID(), "Tester"), configuration(), false, false, false);
        session.forcePhase(FlowPhase.RULES);

        assertTrue(session.transition(FlowPhase.RULES, FlowPhase.SAVING_RULES));
        assertFalse(session.transition(FlowPhase.RULES, FlowPhase.REJECTION_CONFIRMATION));
    }

    @Test
    void fallbackReopenIsDeduplicated() {
        NoticeSession session = new NoticeSession(
                new PlayerIdentity(UUID.randomUUID(), "Tester"), configuration(), false, false, false);

        assertTrue(session.markReopenScheduled());
        assertFalse(session.markReopenScheduled());
        session.clearReopenScheduled();
        assertTrue(session.markReopenScheduled());
    }

    @Test
    void presentationModeCannotChangeDuringASession() {
        NoticeSession session = new NoticeSession(
                new PlayerIdentity(UUID.randomUUID(), "Tester"), configuration(), false, false, false);

        assertFalse(session.presentationPrepared());
        session.fallback(true);
        session.fallback(false);

        assertTrue(session.presentationPrepared());
        assertTrue(session.fallback());
    }

    private static PluginConfiguration configuration() {
        MainConfig main = new MainConfig(
                "test", 30,
                new MainConfig.MariaDbConfig(
                        "jdbc:mariadb://localhost/test", "u", "p", "kudn_", 2, 5000, 15000),
                new MainConfig.RedisConfig(false, "redis://localhost", "kudn:", 300),
                new MainConfig.ClientConfig(771, true));
        RulesConfig rules = new RulesConfig(true, "t", List.of("b"), "c", "l",
                new RulesConfig.Button("a", "a"), new RulesConfig.Button("r", "r"),
                new RulesConfig.RejectionConfirmation("t", "b", "x", "y"));
        ChangelogConfig changelog = new ChangelogConfig(true, 1, "v", "t", List.of("b"), "a", "a");
        MessagesConfig messages = new MessagesConfig(
                "", "", "", "", "", "", "", "", "", "", "");
        return new PluginConfiguration(main, rules, changelog, messages);
    }
}
