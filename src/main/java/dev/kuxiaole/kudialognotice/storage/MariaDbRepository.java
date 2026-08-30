package dev.kuxiaole.kudialognotice.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.kuxiaole.kudialognotice.config.ChangelogCodec;
import dev.kuxiaole.kudialognotice.config.MainConfig;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.UUID;

final class MariaDbRepository implements AutoCloseable {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    // Keep the DB boundary aligned with the replicated document codec.
    private static final int MAX_DOCUMENT_BYTES = ChangelogCodec.MAX_PAYLOAD_BYTES;

    private final HikariDataSource dataSource;
    private final String playersTable;
    private final String rulesTable;
    private final String changelogTable;
    private final String changelogDocumentTable;

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
        changelogDocumentTable = prefix + "changelog_document";
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        notice_id VARCHAR(64) NOT NULL,
                        revision BIGINT NOT NULL,
                        payload MEDIUMTEXT NOT NULL,
                        payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        source_server_id VARCHAR(64) NOT NULL,
                        updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                            ON UPDATE CURRENT_TIMESTAMP(3),
                        PRIMARY KEY (notice_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.formatted(changelogDocumentTable));
        }
    }

    /**
     * Loads the authoritative changelog document for a notice id.
     *
     * <p>This method performs blocking JDBC work and must only be called from
     * an asynchronous context.</p>
     */
    Optional<StoredChangelogDocument> loadChangelogDocument(String noticeId) throws SQLException {
        validateIdentifier(noticeId, "noticeId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT notice_id, revision, payload, payload_sha256, source_server_id, updated_at
                     FROM %s WHERE notice_id = ?
                     """.formatted(changelogDocumentTable))) {
            statement.setString(1, noticeId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(readChangelogDocument(result))
                        : Optional.empty();
            }
        }
    }

    /**
     * Reconciles a local document against the MariaDB authority.
     *
     * <p>The comparison and write are performed in one transaction while the
     * notice row is locked. A lower local revision never overwrites the
     * authority, an equal revision with a different payload is reported as a
     * conflict, and only a strictly higher revision can update the row.</p>
     *
     * <p>This method performs blocking JDBC work and must only be called from
     * an asynchronous context.</p>
     */
    ChangelogReconcileResult reconcileChangelogDocument(
            String noticeId,
            long revision,
            String canonicalPayload,
            String sha256,
            String sourceServerId
    ) throws SQLException {
        validateIdentifier(noticeId, "noticeId");
        validateIdentifier(sourceServerId, "sourceServerId");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        if (canonicalPayload == null || canonicalPayload.isBlank()) {
            throw new IllegalArgumentException("canonicalPayload must not be blank");
        }
        String canonical = ChangelogCodec.canonicalize(canonicalPayload);
        if (!canonical.equals(canonicalPayload)) {
            throw new IllegalArgumentException("canonicalPayload must use normalized UTF-8 line endings");
        }
        int payloadBytes = canonicalPayload.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("canonicalPayload exceeds " + MAX_DOCUMENT_BYTES + " UTF-8 bytes");
        }
        String normalizedHash = normalizeSha256(sha256);
        String computedHash = ChangelogCodec.sha256(canonicalPayload);
        if (!computedHash.equalsIgnoreCase(normalizedHash)) {
            throw new IllegalArgumentException("sha256 does not match canonicalPayload");
        }

        // A missing row can race with another server's first publish. Retry
        // duplicate-key/deadlock failures so both callers receive a proper
        // monotonic comparison result instead of a transient SQL error.
        SQLException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return reconcileChangelogDocumentOnce(
                        noticeId, revision, canonicalPayload, normalizedHash, sourceServerId);
            } catch (SQLException exception) {
                lastFailure = exception;
                if (!isRetryableReconcileFailure(exception) || attempt == 2) {
                    throw exception;
                }
            }
        }
        throw lastFailure == null
                ? new SQLException("Unable to reconcile changelog document")
                : lastFailure;
    }

    private ChangelogReconcileResult reconcileChangelogDocumentOnce(
            String noticeId,
            long revision,
            String canonicalPayload,
            String sha256,
            String sourceServerId
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                StoredChangelogDocument current = selectChangelogDocument(connection, noticeId, true);
                ChangelogReconcileResult.Decision decision;
                if (current == null) {
                    insertChangelogDocument(connection, noticeId, revision, canonicalPayload, sha256, sourceServerId);
                    decision = ChangelogReconcileResult.Decision.INSERTED;
                } else if (revision < current.revision()) {
                    decision = ChangelogReconcileResult.Decision.REMOTE_NEWER;
                } else if (revision == current.revision()) {
                    decision = current.sha256().equalsIgnoreCase(sha256)
                            && current.canonicalPayload().equals(canonicalPayload)
                            ? ChangelogReconcileResult.Decision.UNCHANGED
                            : ChangelogReconcileResult.Decision.CONFLICT;
                } else {
                    updateChangelogDocument(connection, noticeId, revision, canonicalPayload, sha256, sourceServerId);
                    decision = ChangelogReconcileResult.Decision.UPDATED;
                }

                connection.commit();
                StoredChangelogDocument authoritative = selectChangelogDocument(connection, noticeId, false);
                if (authoritative == null) {
                    throw new SQLException("Changelog document disappeared during reconciliation");
                }
                return new ChangelogReconcileResult(decision, authoritative);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // The connection is about to be closed; preserve the original failure.
                }
            }
        }
    }

    private void insertChangelogDocument(
            Connection connection,
            String noticeId,
            long revision,
            String canonicalPayload,
            String sha256,
            String sourceServerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s (notice_id, revision, payload, payload_sha256, source_server_id)
                VALUES (?, ?, ?, ?, ?)
                """.formatted(changelogDocumentTable))) {
            statement.setString(1, noticeId);
            statement.setLong(2, revision);
            statement.setString(3, canonicalPayload);
            statement.setString(4, sha256);
            statement.setString(5, sourceServerId);
            statement.executeUpdate();
        }
    }

    private void updateChangelogDocument(
            Connection connection,
            String noticeId,
            long revision,
            String canonicalPayload,
            String sha256,
            String sourceServerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE %s
                   SET revision = ?, payload = ?, payload_sha256 = ?, source_server_id = ?,
                       updated_at = CURRENT_TIMESTAMP(3)
                 WHERE notice_id = ? AND revision < ?
                """.formatted(changelogDocumentTable))) {
            statement.setLong(1, revision);
            statement.setString(2, canonicalPayload);
            statement.setString(3, sha256);
            statement.setString(4, sourceServerId);
            statement.setString(5, noticeId);
            statement.setLong(6, revision);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Changelog document changed while its row was locked");
            }
        }
    }

    private StoredChangelogDocument selectChangelogDocument(
            Connection connection,
            String noticeId,
            boolean forUpdate
    ) throws SQLException {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT notice_id, revision, payload, payload_sha256, source_server_id, updated_at
                  FROM %s WHERE notice_id = ?%s
                """.formatted(changelogDocumentTable, suffix))) {
            statement.setString(1, noticeId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readChangelogDocument(result) : null;
            }
        }
    }

    private static StoredChangelogDocument readChangelogDocument(ResultSet result) throws SQLException {
        String noticeId = result.getString("notice_id");
        long revision = result.getLong("revision");
        String payload = result.getString("payload");
        String sha256 = result.getString("payload_sha256");
        String sourceServerId = result.getString("source_server_id");
        Timestamp timestamp = result.getTimestamp("updated_at");
        Instant updatedAt = timestamp == null ? Instant.EPOCH : timestamp.toInstant();
        try {
            validateIdentifier(noticeId, "stored noticeId");
            validateIdentifier(sourceServerId, "stored sourceServerId");
            if (revision < 0L) {
                throw new IllegalArgumentException("stored revision must be non-negative");
            }
            if (payload == null || payload.isBlank()) {
                throw new IllegalArgumentException("stored payload must not be blank");
            }
            String canonical = ChangelogCodec.canonicalize(payload);
            if (!canonical.equals(payload)) {
                throw new IllegalArgumentException("stored payload is not canonical");
            }
            String normalizedHash = normalizeSha256(sha256);
            if (!ChangelogCodec.sha256(canonical).equalsIgnoreCase(normalizedHash)) {
                throw new IllegalArgumentException("stored payload hash does not match content");
            }
            return new StoredChangelogDocument(
                    noticeId, revision, canonical, normalizedHash, sourceServerId, updatedAt);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Invalid changelog document stored in MariaDB", exception);
        }
    }

    private static void validateIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + IDENTIFIER.pattern());
        }
    }

    private static String normalizeSha256(String value) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("sha256 must be a 64-character hexadecimal digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean isRetryableReconcileFailure(SQLException exception) {
        String state = exception.getSQLState();
        int code = exception.getErrorCode();
        return "23000".equals(state)
                || "40001".equals(state)
                || code == 1205
                || code == 1213;
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
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
