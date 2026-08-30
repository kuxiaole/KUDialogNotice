package dev.kuxiaole.kudialognotice.storage;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogReconcileResultTest {
    @Test
    void changedOnlyMeansTheLocalDocumentWasCommitted() {
        StoredChangelogDocument document = document(2L);

        assertTrue(new ChangelogReconcileResult(
                ChangelogReconcileResult.Decision.INSERTED, document).changed());
        assertTrue(new ChangelogReconcileResult(
                ChangelogReconcileResult.Decision.UPDATED, document).changed());
        assertFalse(new ChangelogReconcileResult(
                ChangelogReconcileResult.Decision.UNCHANGED, document).changed());
        assertFalse(new ChangelogReconcileResult(
                ChangelogReconcileResult.Decision.REMOTE_NEWER, document).changed());
        assertFalse(new ChangelogReconcileResult(
                ChangelogReconcileResult.Decision.CONFLICT, document).changed());
    }

    @Test
    void resultAndStoredDocumentRejectNullRequiredFields() {
        StoredChangelogDocument document = document(1L);

        assertThrows(NullPointerException.class,
                () -> new ChangelogReconcileResult(null, document));
        assertThrows(NullPointerException.class,
                () -> new ChangelogReconcileResult(
                        ChangelogReconcileResult.Decision.INSERTED, null));
        assertThrows(NullPointerException.class,
                () -> new StoredChangelogDocument(
                        null, 1L, "payload", "a".repeat(64), "server", Instant.EPOCH));
        assertThrows(NullPointerException.class,
                () -> new StoredChangelogDocument(
                        "changelog", 1L, "payload", "a".repeat(64), "server", null));
    }

    private static StoredChangelogDocument document(long revision) {
        return new StoredChangelogDocument(
                "changelog",
                revision,
                "revision: " + revision + "\n",
                "a".repeat(64),
                "survival",
                Instant.EPOCH);
    }
}
