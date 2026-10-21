package online.rizzlers.proxylogger.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import online.rizzlers.proxylogger.config.PluginConfig;
import online.rizzlers.proxylogger.database.models.AccessRule;
import online.rizzlers.proxylogger.database.models.AuditEvent;
import online.rizzlers.proxylogger.database.models.LinkCode;
import online.rizzlers.proxylogger.database.models.LinkedAccount;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Manages database persistence for linked accounts, link codes, audit logs, and access rules
 * with HikariCP connection pooling supporting both MySQL/MariaDB and SQLite.
 */
public class DatabaseManager implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final boolean isSqlite;
    private final Logger logger;

    public DatabaseManager(PluginConfig config, File dataFolder, Logger logger) throws SQLException {
        this.logger = logger;
        this.isSqlite = "sqlite".equalsIgnoreCase(config.getStorageDriver());

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("RizzlersProxyLoggerPool");

        if (isSqlite) {
            String sqlitePath;
            if (":memory:".equals(config.getSqliteFile())) {
                sqlitePath = ":memory:";
            } else {
                File dbFile = new File(dataFolder, config.getSqliteFile());
                if (!dbFile.getParentFile().exists()) {
                    dbFile.getParentFile().mkdirs();
                }
                sqlitePath = dbFile.getAbsolutePath();
            }
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + sqlitePath);
            hikariConfig.setMaximumPoolSize(1); // SQLite is single-writer
            hikariConfig.setConnectionTimeout(30000);
        } else {
            hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");
            String sslParam = config.isMysqlSsl() ? "true" : "false";
            String url = String.format("jdbc:mariadb://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true",
                    config.getMysqlHost(), config.getMysqlPort(), config.getMysqlDatabase(), sslParam);
            hikariConfig.setJdbcUrl(url);
            hikariConfig.setUsername(config.getMysqlUsername());
            hikariConfig.setPassword(config.getMysqlPassword());
            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setMinimumIdle(2);
            hikariConfig.setConnectionTimeout(10000);
            hikariConfig.setLeakDetectionThreshold(15000);
        }

        this.dataSource = new HikariDataSource(hikariConfig);
        initTables();
    }

    // Direct constructor for unit tests
    public DatabaseManager(HikariDataSource dataSource, boolean isSqlite, Logger logger) throws SQLException {
        this.dataSource = dataSource;
        this.isSqlite = isSqlite;
        this.logger = logger;
        initTables();
    }

    private void initTables() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            // linked_accounts
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS linked_accounts (
                    minecraft_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    minecraft_username VARCHAR(16) NOT NULL,
                    account_type VARCHAR(16) NOT NULL,
                    discord_id VARCHAR(20) NOT NULL UNIQUE,
                    linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_seen TIMESTAMP NULL,
                    link_status VARCHAR(16) NOT NULL DEFAULT 'linked'
                );
            """);

            // link_codes
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS link_codes (
                    code_hash CHAR(64) NOT NULL PRIMARY KEY,
                    minecraft_uuid VARCHAR(36) NOT NULL,
                    minecraft_username VARCHAR(16) NOT NULL,
                    account_type VARCHAR(16) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at TIMESTAMP NOT NULL,
                    attempts INT NOT NULL DEFAULT 0
                );
            """);

            // access_audit
            String autoInc = isSqlite ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
            stmt.executeUpdate(String.format("""
                CREATE TABLE IF NOT EXISTS access_audit (
                    id %s,
                    event_type VARCHAR(40) NOT NULL,
                    minecraft_uuid VARCHAR(36) NULL,
                    minecraft_username VARCHAR(16) NULL,
                    discord_id VARCHAR(20) NULL,
                    guild_id VARCHAR(20) NULL,
                    role_id VARCHAR(20) NULL,
                    result VARCHAR(20) NOT NULL,
                    reason VARCHAR(255) NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
            """, autoInc));

            // access_rules
            stmt.executeUpdate(String.format("""
                CREATE TABLE IF NOT EXISTS access_rules (
                    id %s,
                    rule_type VARCHAR(16) NOT NULL,
                    guild_id VARCHAR(20) NOT NULL,
                    role_id VARCHAR(20) NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT 1,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (rule_type, guild_id, role_id)
                );
            """, autoInc));
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public Optional<LinkedAccount> getLinkedAccount(UUID uuid) throws SQLException {
        String sql = "SELECT * FROM linked_accounts WHERE minecraft_uuid = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapLinkedAccount(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<LinkedAccount> getLinkedAccountByDiscordId(String discordId) throws SQLException {
        String sql = "SELECT * FROM linked_accounts WHERE discord_id = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapLinkedAccount(rs));
                }
            }
        }
        return Optional.empty();
    }

    public void updateLastSeen(UUID uuid) {
        String sql = "UPDATE linked_accounts SET last_seen = CURRENT_TIMESTAMP WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (logger != null) logger.warning("Failed to update last_seen for " + uuid + ": " + e.getMessage());
        }
    }

    public boolean createLinkCode(UUID uuid, String username, String accountType, String codeHash, Instant expiresAt) throws SQLException {
        // Remove existing pending codes for this uuid first
        String deleteSql = "DELETE FROM link_codes WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }

        String insertSql = "INSERT INTO link_codes (code_hash, minecraft_uuid, minecraft_username, account_type, expires_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, codeHash);
            ps.setString(2, uuid.toString());
            ps.setString(3, username);
            ps.setString(4, accountType);
            ps.setTimestamp(5, Timestamp.from(expiresAt));
            return ps.executeUpdate() > 0;
        }
    }

    public Optional<LinkCode> getLinkCode(String codeHash) throws SQLException {
        String sql = "SELECT * FROM link_codes WHERE code_hash = ? LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, codeHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapLinkCode(rs));
                }
            }
        }
        return Optional.empty();
    }

    public void incrementCodeAttempts(String codeHash) {
        String sql = "UPDATE link_codes SET attempts = attempts + 1 WHERE code_hash = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, codeHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (logger != null) logger.warning("Failed to increment attempts for code: " + e.getMessage());
        }
    }

    public synchronized LinkResult consumeLinkCode(String codeHash, String discordId) throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Check if discordId is already linked
                String checkDiscordSql = "SELECT minecraft_username FROM linked_accounts WHERE discord_id = ? AND link_status = 'linked' LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(checkDiscordSql)) {
                    ps.setString(1, discordId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            conn.rollback();
                            return new LinkResult(false, "This Discord account is already linked to player " + rs.getString("minecraft_username"), null);
                        }
                    }
                }

                // 2. Fetch code
                String fetchCodeSql = "SELECT * FROM link_codes WHERE code_hash = ? LIMIT 1";
                LinkCode code;
                try (PreparedStatement ps = conn.prepareStatement(fetchCodeSql)) {
                    ps.setString(1, codeHash);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return new LinkResult(false, "Invalid or expired link code.", null);
                        }
                        code = mapLinkCode(rs);
                    }
                }

                if (code.isExpired()) {
                    // Cleanup expired code
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM link_codes WHERE code_hash = ?")) {
                        ps.setString(1, codeHash);
                        ps.executeUpdate();
                    }
                    conn.commit();
                    return new LinkResult(false, "Link code has expired (valid for 5 minutes). Generate a new one in-game with /plog link.", null);
                }

                if (code.attempts() >= 5) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM link_codes WHERE code_hash = ?")) {
                        ps.setString(1, codeHash);
                        ps.executeUpdate();
                    }
                    conn.commit();
                    return new LinkResult(false, "Too many failed attempts. Code invalidated.", null);
                }

                // 3. Upsert into linked_accounts
                String upsertSql;
                if (isSqlite) {
                    upsertSql = """
                        INSERT INTO linked_accounts (minecraft_uuid, minecraft_username, account_type, discord_id, linked_at, link_status)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, 'linked')
                        ON CONFLICT(minecraft_uuid) DO UPDATE SET
                            minecraft_username = excluded.minecraft_username,
                            account_type = excluded.account_type,
                            discord_id = excluded.discord_id,
                            linked_at = CURRENT_TIMESTAMP,
                            link_status = 'linked';
                    """;
                } else {
                    upsertSql = """
                        INSERT INTO linked_accounts (minecraft_uuid, minecraft_username, account_type, discord_id, linked_at, link_status)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, 'linked')
                        ON DUPLICATE KEY UPDATE
                            minecraft_username = VALUES(minecraft_username),
                            account_type = VALUES(account_type),
                            discord_id = VALUES(discord_id),
                            linked_at = CURRENT_TIMESTAMP,
                            link_status = 'linked';
                    """;
                }

                try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                    ps.setString(1, code.minecraftUuid().toString());
                    ps.setString(2, code.minecraftUsername());
                    ps.setString(3, code.accountType());
                    ps.setString(4, discordId);
                    ps.executeUpdate();
                }

                // 4. Delete consumed link code
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM link_codes WHERE code_hash = ?")) {
                    ps.setString(1, codeHash);
                    ps.executeUpdate();
                }

                conn.commit();
                LinkedAccount linked = new LinkedAccount(code.minecraftUuid(), code.minecraftUsername(), code.accountType(), discordId, Instant.now(), null, "linked");
                return new LinkResult(true, "Successfully linked Discord to " + code.minecraftUsername() + "!", linked);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public boolean unlinkAccount(UUID uuid) throws SQLException {
        String sql = "UPDATE linked_accounts SET link_status = 'unlinked' WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public boolean unlinkAccountByDiscordId(String discordId) throws SQLException {
        String sql = "UPDATE linked_accounts SET link_status = 'unlinked' WHERE discord_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, discordId);
            return ps.executeUpdate() > 0;
        }
    }

    public void recordAudit(AuditEvent event) {
        String sql = """
            INSERT INTO access_audit (event_type, minecraft_uuid, minecraft_username, discord_id, guild_id, role_id, result, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.eventType());
            ps.setString(2, event.minecraftUuid() != null ? event.minecraftUuid().toString() : null);
            ps.setString(3, event.minecraftUsername());
            ps.setString(4, event.discordId());
            ps.setString(5, event.guildId());
            ps.setString(6, event.roleId());
            ps.setString(7, event.result());
            ps.setString(8, event.reason());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (logger != null) logger.warning("Failed to record audit event: " + e.getMessage());
        }
    }

    public List<AccessRule> getAccessRules(String ruleType) throws SQLException {
        List<AccessRule> rules = new ArrayList<>();
        String sql = "SELECT * FROM access_rules WHERE rule_type = ? AND enabled = 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ruleType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rules.add(mapAccessRule(rs));
                }
            }
        }
        return rules;
    }

    public boolean addAccessRule(String ruleType, String guildId, String roleId) throws SQLException {
        String sql;
        if (isSqlite) {
            sql = "INSERT INTO access_rules (rule_type, guild_id, role_id, enabled) VALUES (?, ?, ?, 1) ON CONFLICT(rule_type, guild_id, role_id) DO UPDATE SET enabled = 1";
        } else {
            sql = "INSERT INTO access_rules (rule_type, guild_id, role_id, enabled) VALUES (?, ?, ?, 1) ON DUPLICATE KEY UPDATE enabled = 1";
        }
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ruleType);
            ps.setString(2, guildId);
            ps.setString(3, roleId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean removeAccessRule(String ruleType, String guildId, String roleId) throws SQLException {
        String sql = "DELETE FROM access_rules WHERE rule_type = ? AND guild_id = ? AND role_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ruleType);
            ps.setString(2, guildId);
            ps.setString(3, roleId);
            return ps.executeUpdate() > 0;
        }
    }

    public int countLinkedAccounts() {
        String sql = "SELECT COUNT(*) FROM linked_accounts WHERE link_status = 'linked'";
        try (Connection conn = getConnection(); Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private LinkedAccount mapLinkedAccount(ResultSet rs) throws SQLException {
        String uuidStr = rs.getString("minecraft_uuid");
        UUID uuid = uuidStr != null ? UUID.fromString(uuidStr) : null;
        Timestamp linkedAtTs = rs.getTimestamp("linked_at");
        Timestamp lastSeenTs = rs.getTimestamp("last_seen");
        return new LinkedAccount(
                uuid,
                rs.getString("minecraft_username"),
                rs.getString("account_type"),
                rs.getString("discord_id"),
                linkedAtTs != null ? linkedAtTs.toInstant() : Instant.now(),
                lastSeenTs != null ? lastSeenTs.toInstant() : null,
                rs.getString("link_status")
        );
    }

    private LinkCode mapLinkCode(ResultSet rs) throws SQLException {
        String uuidStr = rs.getString("minecraft_uuid");
        UUID uuid = uuidStr != null ? UUID.fromString(uuidStr) : null;
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp expiresTs = rs.getTimestamp("expires_at");
        return new LinkCode(
                rs.getString("code_hash"),
                uuid,
                rs.getString("minecraft_username"),
                rs.getString("account_type"),
                createdTs != null ? createdTs.toInstant() : Instant.now(),
                expiresTs != null ? expiresTs.toInstant() : Instant.now(),
                rs.getInt("attempts")
        );
    }

    private AccessRule mapAccessRule(ResultSet rs) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        return new AccessRule(
                rs.getLong("id"),
                rs.getString("rule_type"),
                rs.getString("guild_id"),
                rs.getString("role_id"),
                rs.getBoolean("enabled"),
                createdTs != null ? createdTs.toInstant() : Instant.now()
        );
    }

    public record LinkResult(boolean success, String message, LinkedAccount linkedAccount) {}
}
