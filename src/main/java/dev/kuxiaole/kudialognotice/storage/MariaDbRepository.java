package dev.kuxiaole.kudialognotice.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.kuxiaole.kudialognotice.config.MainConfig;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

final class MariaDbRepository implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final String playersTable;
    private final String rulesTable;
    private final String changelogTable;

    MariaDbRepository(MainConfig.MariaDbConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("KUDialogNotice-MariaDB");
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setMinimumIdle(0);
        hikari.setConnectionTimeout(config.connectionTimeoutMillis());
        hikari.setValidationTimeout(Math.min(config.connectionTimeoutMillis(), 5_000L));
        hikari.setInitializationFailTimeout(-1L);
        hikari.setAutoCommit(true);
        hikari.addDataSourceProperty("connectTimeout", config.connectionTimeoutMillis());
        hikari.addDataSourceProperty("socketTimeout", config.socketTimeoutMillis());
        dataSource = new HikariDataSource(hikari);

        String prefix = config.tablePrefix();
        playersTable = prefix + "players";
        rulesTable = prefix + "rules";
        changelogTable = prefix + "changelog_seen";
    }

    void initializeSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_uuid BINARY(16) NOT NULL,
                        last_name VARCHAR(16) NOT NULL,
                        updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                            ON UPDATE CURRENT_TIMESTAMP(3),
                        PRIMARY KEY (player_uuid),
                        KEY idx_last_name (last_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.formatted(playersTable));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_uuid BINARY(16) NOT NULL,
                        accepted_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        PRIMARY KEY (player_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.formatted(rulesTable));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_uuid BINARY(16) NOT NULL,
                        stream_id VARCHAR(64) NOT NULL,
                        last_seen_revision BIGINT UNSIGNED NOT NULL,
                        seen_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                            ON UPDATE CURRENT_TIMESTAMP(3),
                        PRIMARY KEY (player_uuid, stream_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.formatted(changelogTable));
        }
    }

    PlayerNoticeState load(PlayerIdentity player, String streamId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            upsertPlayer(connection, player);
            boolean accepted;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM " + rulesTable + " WHERE player_uuid = ? LIMIT 1")) {
                statement.setBytes(1, uuidBytes(player.uniqueId()));
                try (ResultSet result = statement.executeQuery()) {
                    accepted = result.next();
                }
            }

            long revision = 0L;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT last_seen_revision FROM " + changelogTable
                            + " WHERE player_uuid = ? AND stream_id = ?")) {
                statement.setBytes(1, uuidBytes(player.uniqueId()));
                statement.setString(2, streamId);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        revision = result.getLong(1);
                    }
                }
            }
            return new PlayerNoticeState(accepted, revision);
        }
    }

    void acceptRules(PlayerIdentity player) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            upsertPlayer(connection, player);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT IGNORE INTO " + rulesTable + " (player_uuid) VALUES (?)")) {
                statement.setBytes(1, uuidBytes(player.uniqueId()));
                statement.executeUpdate();
            }
        }
    }

    void markChangelogSeen(PlayerIdentity player, String streamId, long revision) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            upsertPlayer(connection, player);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO %s (player_uuid, stream_id, last_seen_revision)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        last_seen_revision = GREATEST(last_seen_revision, VALUES(last_seen_revision)),
                        seen_at = IF(VALUES(last_seen_revision) >= last_seen_revision,
                            CURRENT_TIMESTAMP(3), seen_at)
                    """.formatted(changelogTable))) {
                statement.setBytes(1, uuidBytes(player.uniqueId()));
                statement.setString(2, streamId);
                statement.setLong(3, revision);
                statement.executeUpdate();
            }
        }
    }

    Optional<PlayerIdentity> resolvePlayer(String input) throws SQLException {
        UUID uuid = parseUuid(input);
        String sql = uuid == null
                ? "SELECT player_uuid, last_name FROM " + playersTable
                    + " WHERE last_name = ? ORDER BY updated_at DESC LIMIT 1"
                : "SELECT player_uuid, last_name FROM " + playersTable + " WHERE player_uuid = ? LIMIT 1";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (uuid == null) {
                statement.setString(1, input);
            } else {
                statement.setBytes(1, uuidBytes(uuid));
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerIdentity(bytesUuid(result.getBytes(1)), result.getString(2)));
            }
        }
    }

    PlayerStatus loadStatus(PlayerIdentity player, String streamId) throws SQLException {
        PlayerNoticeState state = load(player, streamId);
        return new PlayerStatus(player, state.rulesAccepted(), state.lastSeenRevision());
    }

    private void upsertPlayer(Connection connection, PlayerIdentity player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s (player_uuid, last_name) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE last_name = VALUES(last_name), updated_at = CURRENT_TIMESTAMP(3)
                """.formatted(playersTable))) {
            statement.setBytes(1, uuidBytes(player.uniqueId()));
            statement.setString(2, player.name());
            statement.executeUpdate();
        }
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private static UUID bytesUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID parseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
