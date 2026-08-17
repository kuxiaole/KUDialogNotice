package dev.kuxiaole.kudialognotice.flow;

import org.junit.jupiter.api.Test;

import static dev.kuxiaole.kudialognotice.flow.FlowPolicy.InitialStep.CHANGELOG;
import static dev.kuxiaole.kudialognotice.flow.FlowPolicy.InitialStep.NONE;
import static dev.kuxiaole.kudialognotice.flow.FlowPolicy.InitialStep.RULES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowPolicyTest {
    @Test
    void newPlayerReadsRulesBeforeChangelog() {
        assertEquals(RULES, FlowPolicy.initialStep(true, false, true, 0, 5));
    }

    @Test
    void returningPlayerSeesUnseenChangelog() {
        assertEquals(CHANGELOG, FlowPolicy.initialStep(true, true, true, 4, 5));
    }

    @Test
    void sameOrNewerRevisionDoesNotShowAgain() {
        assertEquals(NONE, FlowPolicy.initialStep(true, true, true, 5, 5));
        assertEquals(NONE, FlowPolicy.initialStep(true, true, true, 6, 5));
        assertFalse(FlowPolicy.shouldShowChangelog(true, 6, 5));
    }

    @Test
    void bypassedRulesCanStillReceiveChangelog() {
        assertEquals(CHANGELOG, FlowPolicy.initialStep(false, false, true, 1, 2));
        assertTrue(FlowPolicy.shouldShowChangelog(true, 1, 2));
    }

    @Test
    void disabledChangelogFinishesAfterAcceptedRules() {
        assertEquals(NONE, FlowPolicy.initialStep(true, true, false, 0, 10));
    }
}

