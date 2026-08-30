package dev.kuxiaole.kudialognotice.storage;

import dev.kuxiaole.kudialognotice.config.MainConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChangelogSynchronizerTest {
    @Test
    void authorityNamespaceIsStableForEquivalentDatabaseSettings() {
        MainConfig.MariaDbConfig first = mariaDb(
                "jdbc:mariadb://db.example/minecraft", "kudn_");
        MainConfig.MariaDbConfig equivalent = mariaDb(
                "jdbc:mariadb://db.example/minecraft", "kudn_");
        MainConfig.MariaDbConfig differentDatabase = mariaDb(
                "jdbc:mariadb://other.example/minecraft", "kudn_");
        MainConfig.MariaDbConfig differentPrefix = mariaDb(
                "jdbc:mariadb://db.example/minecraft", "other_");

        assertEquals(
                ChangelogSynchronizer.namespaceFor(first),
                ChangelogSynchronizer.namespaceFor(equivalent));
        assertNotEquals(
                ChangelogSynchronizer.namespaceFor(first),
                ChangelogSynchronizer.namespaceFor(differentDatabase));
        assertNotEquals(
                ChangelogSynchronizer.namespaceFor(first),
                ChangelogSynchronizer.namespaceFor(differentPrefix));
    }

    private static MainConfig.MariaDbConfig mariaDb(String jdbcUrl, String prefix) {
        return new MainConfig.MariaDbConfig(jdbcUrl, "user", "password", prefix, 2, 5_000, 15_000);
    }
}
