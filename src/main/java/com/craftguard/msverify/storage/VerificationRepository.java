package com.craftguard.msverify.storage;

import com.craftguard.msverify.security.IssuedChallenge;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class VerificationRepository {
    private final JavaPlugin plugin;
    private final String databaseFileName;
    private Connection connection;

    public VerificationRepository(JavaPlugin plugin, String databaseFileName) {
        this.plugin = plugin;
        this.databaseFileName = databaseFileName;
    }

    public synchronized void open() throws SQLException {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new SQLException("无法创建插件数据目录：" + plugin.getDataFolder());
        }

        File databaseFile = new File(plugin.getDataFolder(), databaseFileName);
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        createSchema();
    }

    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            plugin.getLogger().warning("关闭 SQLite 连接失败：" + exception.getMessage());
        }
    }

    public synchronized Map<UUID, VerifiedPlayer> loadVerifiedPlayers() throws SQLException {
        Map<UUID, VerifiedPlayer> players = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT minecraft_uuid, minecraft_name, xuid, verified_at FROM verified_players"
        );
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                UUID uuid = UUID.fromString(resultSet.getString("minecraft_uuid"));
                players.put(uuid, new VerifiedPlayer(
                        uuid,
                        resultSet.getString("minecraft_name"),
                        resultSet.getString("xuid"),
                        resultSet.getLong("verified_at")
                ));
            }
        }
        return players;
    }

    public synchronized void saveChallenge(IssuedChallenge challenge) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT OR REPLACE INTO pending_challenges (challenge_id, minecraft_uuid, expires_at, used)
                VALUES (?, ?, ?, 0)
                """
        )) {
            statement.setString(1, challenge.challengeId());
            statement.setString(2, challenge.minecraftUuid().toString());
            statement.setLong(3, challenge.expiresAt());
            statement.executeUpdate();
        }
    }

    public synchronized Optional<VerifiedPlayer> findVerified(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT minecraft_uuid, minecraft_name, xuid, verified_at FROM verified_players WHERE minecraft_uuid = ?"
        )) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readVerified(resultSet));
            }
        }
    }

    public synchronized Optional<VerifiedPlayer> findVerifiedByName(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT minecraft_uuid, minecraft_name, xuid, verified_at
                FROM verified_players
                WHERE LOWER(minecraft_name) = LOWER(?)
                ORDER BY verified_at DESC
                LIMIT 1
                """
        )) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readVerified(resultSet));
            }
        }
    }

    public synchronized boolean removeVerified(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM verified_players WHERE minecraft_uuid = ?"
        )) {
            statement.setString(1, uuid.toString());
            return statement.executeUpdate() > 0;
        }
    }

    public synchronized String getCursor(String serverId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT cursor FROM sync_state WHERE server_id = ?"
        )) {
            statement.setString(1, serverId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return "0";
                }
                return resultSet.getString("cursor");
            }
        }
    }

    public synchronized int deleteExpiredChallenges(long nowMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM pending_challenges WHERE used = 1 OR expires_at < ?"
        )) {
            statement.setLong(1, nowMillis);
            return statement.executeUpdate();
        }
    }

    public synchronized BatchApplyResult applyCompletedBatch(
            String serverId,
            List<CompletedVerification> items,
            String nextCursor,
            long nowMillis
    ) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        List<VerifiedPlayer> verifiedPlayers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            for (CompletedVerification item : items) {
                PendingChallenge pendingChallenge = findPendingChallengeInTransaction(item.challengeId());
                if (pendingChallenge == null) {
                    warnings.add("未找到待处理 challenge：" + item.challengeId());
                    continue;
                }
                if (pendingChallenge.used()) {
                    warnings.add("challenge 已被使用：" + item.challengeId());
                    continue;
                }
                if (pendingChallenge.expiresAt() < nowMillis) {
                    markChallengeUsedInTransaction(item.challengeId());
                    warnings.add("challenge 在完成前已过期：" + item.challengeId());
                    continue;
                }
                if (!pendingChallenge.minecraftUuid().equals(item.minecraftUuid())) {
                    markChallengeUsedInTransaction(item.challengeId());
                    warnings.add("完成验证记录的 UUID 与 challenge 不匹配：" + item.challengeId());
                    continue;
                }

                VerifiedPlayer verifiedPlayer = new VerifiedPlayer(
                        item.minecraftUuid(),
                        item.minecraftName(),
                        item.xuid(),
                        item.verifiedAt()
                );
                upsertVerifiedInTransaction(verifiedPlayer);
                markChallengeUsedInTransaction(item.challengeId());
                verifiedPlayers.add(verifiedPlayer);
            }

            if (nextCursor != null && !nextCursor.isBlank()) {
                upsertCursorInTransaction(serverId, nextCursor);
            }
            connection.commit();
            return new BatchApplyResult(List.copyOf(verifiedPlayers), List.copyOf(warnings));
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS verified_players (
                        minecraft_uuid TEXT PRIMARY KEY,
                        minecraft_name TEXT NOT NULL,
                        xuid TEXT NOT NULL,
                        verified_at INTEGER NOT NULL
                    )
                    """
            );
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pending_challenges (
                        challenge_id TEXT PRIMARY KEY,
                        minecraft_uuid TEXT NOT NULL,
                        expires_at INTEGER NOT NULL,
                        used INTEGER NOT NULL DEFAULT 0
                    )
                    """
            );
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sync_state (
                        server_id TEXT PRIMARY KEY,
                        cursor TEXT NOT NULL
                    )
                    """
            );
            statement.execute("CREATE INDEX IF NOT EXISTS idx_pending_challenges_uuid ON pending_challenges (minecraft_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_pending_challenges_expires ON pending_challenges (expires_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_verified_players_name ON verified_players (minecraft_name)");
        }
    }

    private VerifiedPlayer readVerified(ResultSet resultSet) throws SQLException {
        UUID uuid = UUID.fromString(resultSet.getString("minecraft_uuid"));
        return new VerifiedPlayer(
                uuid,
                resultSet.getString("minecraft_name"),
                resultSet.getString("xuid"),
                resultSet.getLong("verified_at")
        );
    }

    private PendingChallenge findPendingChallengeInTransaction(String challengeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT challenge_id, minecraft_uuid, expires_at, used FROM pending_challenges WHERE challenge_id = ?"
        )) {
            statement.setString(1, challengeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new PendingChallenge(
                        resultSet.getString("challenge_id"),
                        UUID.fromString(resultSet.getString("minecraft_uuid")),
                        resultSet.getLong("expires_at"),
                        resultSet.getInt("used") != 0
                );
            }
        }
    }

    private void upsertVerifiedInTransaction(VerifiedPlayer player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO verified_players (minecraft_uuid, minecraft_name, xuid, verified_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(minecraft_uuid) DO UPDATE SET
                    minecraft_name = excluded.minecraft_name,
                    xuid = excluded.xuid,
                    verified_at = excluded.verified_at
                """
        )) {
            statement.setString(1, player.minecraftUuid().toString());
            statement.setString(2, player.minecraftName());
            statement.setString(3, player.xuid());
            statement.setLong(4, player.verifiedAt());
            statement.executeUpdate();
        }
    }

    private void markChallengeUsedInTransaction(String challengeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pending_challenges SET used = 1 WHERE challenge_id = ?"
        )) {
            statement.setString(1, challengeId);
            statement.executeUpdate();
        }
    }

    private void upsertCursorInTransaction(String serverId, String cursor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO sync_state (server_id, cursor)
                VALUES (?, ?)
                ON CONFLICT(server_id) DO UPDATE SET cursor = excluded.cursor
                """
        )) {
            statement.setString(1, serverId);
            statement.setString(2, cursor);
            statement.executeUpdate();
        }
    }

    private record PendingChallenge(
            String challengeId,
            UUID minecraftUuid,
            long expiresAt,
            boolean used
    ) {
    }
}
