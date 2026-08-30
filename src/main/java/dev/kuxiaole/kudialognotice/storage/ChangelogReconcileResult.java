package dev.kuxiaole.kudialognotice.storage;

import java.util.Objects;

/**
 * Result of reconciling a locally loaded changelog document with the
 * authoritative MariaDB row.
 */
public record ChangelogReconcileResult(
        Decision decision,
        StoredChangelogDocument document
) {
    public ChangelogReconcileResult {
        decision = Objects.requireNonNull(decision, "decision");
        document = Objects.requireNonNull(document, "document");
    }

    public boolean changed() {
        return decision == Decision.INSERTED || decision == Decision.UPDATED;
    }

    public enum Decision {
        INSERTED,
        UPDATED,
        UNCHANGED,
        REMOTE_NEWER,
        CONFLICT
    }
}
