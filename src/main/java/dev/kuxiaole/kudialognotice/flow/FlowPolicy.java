package dev.kuxiaole.kudialognotice.flow;

public final class FlowPolicy {
    private FlowPolicy() {
    }

    public static InitialStep initialStep(
            boolean rulesRequired,
            boolean rulesAccepted,
            boolean changelogRequired,
            long lastSeenRevision,
            long currentRevision
    ) {
        if (rulesRequired && !rulesAccepted) {
            return InitialStep.RULES;
        }
        if (changelogRequired && lastSeenRevision < currentRevision) {
            return InitialStep.CHANGELOG;
        }
        return InitialStep.NONE;
    }

    public static boolean shouldShowChangelog(
            boolean changelogRequired,
            long lastSeenRevision,
            long currentRevision
    ) {
        return changelogRequired && lastSeenRevision < currentRevision;
    }

    public enum InitialStep {
        RULES,
        CHANGELOG,
        NONE
    }
}

