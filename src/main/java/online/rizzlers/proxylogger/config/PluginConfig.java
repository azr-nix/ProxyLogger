package online.rizzlers.proxylogger.config;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Strongly-typed configuration parser and validator for RizzlersProxyLogger.
 */
public class PluginConfig {
    private String networkName = "RIZZLERS";
    private boolean requireLink = true;
    private boolean denyOnProviderError = true;

    private String botToken = "";
    private String guildId = "";
    private String accessRoleId = "";
    private String blacklistRoleId = "";
    private String logGuildId = "";
    private String logChannelId = "";
    private String linkInstructions = "Join the RIZZLERS Discord and run /link <code>.";
    private int cacheTtlSeconds = 180;

    private int codeExpirySeconds = 300;
    private int maxAttemptsPerHour = 5;
    private int maxActiveLinksPerDiscord = 1;
    private boolean allowUnlink = true;

    private String storageDriver = "sqlite";
    private String mysqlHost = "127.0.0.1";
    private int mysqlPort = 3306;
    private String mysqlDatabase = "rizzlers_proxylogger";
    private String mysqlUsername = "root";
    private String mysqlPassword = "";
    private boolean mysqlSsl = true;
    private String sqliteFile = "proxylogger.db";

    private boolean logLocalFile = true;
    private boolean logJoins = true;
    private boolean logQuits = true;
    private boolean logDeniedAttempts = true;
    private boolean logRoleCheckFailures = true;
    private boolean logLinkEvents = true;
    private boolean logAdminChanges = true;
    private boolean includeIpAddress = false;

    private String msgUnlinked = "<red>You must link your Discord account before joining RIZZLERS.<newline><yellow>Run <bold>/plog link</bold> in-game or visit our Discord.</yellow></red>";
    private String msgMissingAccessRole = "<red>Access denied: You do not have the required RIZZLERS Discord role.</red>";
    private String msgBlacklisted = "<red><bold>ACCESS DENIED</bold></red><newline><red>Your linked Discord account has been blacklisted from the RIZZLERS network.</red>";
    private String msgVerificationUnavailable = "<red>RIZZLERS access verification is temporarily unavailable. Please try again later.</red>";

    @SuppressWarnings("unchecked")
    public static PluginConfig load(InputStream inputStream) {
        PluginConfig config = new PluginConfig();
        if (inputStream == null) return config;

        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(inputStream);
        if (data == null) return config;

        // Network
        Map<String, Object> network = (Map<String, Object>) data.get("network");
        if (network != null) {
            config.networkName = getString(network, "name", config.networkName);
            config.requireLink = getBoolean(network, "require-link", config.requireLink);
            config.denyOnProviderError = getBoolean(network, "deny-on-provider-error", config.denyOnProviderError);
        }

        // Discord
        Map<String, Object> discord = (Map<String, Object>) data.get("discord");
        if (discord != null) {
            String tokenEnv = getString(discord, "bot-token-env", "RIZZLERS_DISCORD_TOKEN");
            String envToken = System.getenv(tokenEnv);
            if (envToken != null && !envToken.isBlank()) {
                config.botToken = envToken.trim();
            } else {
                config.botToken = getString(discord, "bot-token", "");
            }

            config.guildId = sanitizeSnowflake(getString(discord, "guild-id", ""));
            config.accessRoleId = sanitizeSnowflake(getString(discord, "access-role-id", ""));
            config.blacklistRoleId = sanitizeSnowflake(getString(discord, "blacklist-role-id", ""));
            config.logGuildId = sanitizeSnowflake(getString(discord, "log-guild-id", ""));
            config.logChannelId = sanitizeSnowflake(getString(discord, "log-channel-id", ""));
            config.linkInstructions = getString(discord, "link-instructions", config.linkInstructions);
            config.cacheTtlSeconds = getInt(discord, "cache-ttl-seconds", config.cacheTtlSeconds);
        }

        // Linking
        Map<String, Object> linking = (Map<String, Object>) data.get("linking");
        if (linking != null) {
            config.codeExpirySeconds = getInt(linking, "code-expiry-seconds", config.codeExpirySeconds);
            config.maxAttemptsPerHour = getInt(linking, "max-attempts-per-hour", config.maxAttemptsPerHour);
            config.maxActiveLinksPerDiscord = getInt(linking, "max-active-links-per-discord", config.maxActiveLinksPerDiscord);
            config.allowUnlink = getBoolean(linking, "allow-unlink", config.allowUnlink);
        }

        // Storage
        Map<String, Object> storage = (Map<String, Object>) data.get("storage");
        if (storage != null) {
            config.storageDriver = getString(storage, "driver", config.storageDriver).toLowerCase();
            Map<String, Object> mysql = (Map<String, Object>) storage.get("mysql");
            if (mysql != null) {
                config.mysqlHost = getString(mysql, "host", config.mysqlHost);
                config.mysqlPort = getInt(mysql, "port", config.mysqlPort);
                config.mysqlDatabase = getString(mysql, "database", config.mysqlDatabase);
                config.mysqlUsername = getString(mysql, "username", config.mysqlUsername);
                String passEnv = getString(mysql, "password-env", "RIZZLERS_DB_PASSWORD");
                String envPass = System.getenv(passEnv);
                if (envPass != null) {
                    config.mysqlPassword = envPass;
                } else {
                    config.mysqlPassword = getString(mysql, "password", "");
                }
                config.mysqlSsl = getBoolean(mysql, "ssl", config.mysqlSsl);
            }
            Map<String, Object> sqlite = (Map<String, Object>) storage.get("sqlite");
            if (sqlite != null) {
                config.sqliteFile = getString(sqlite, "file", config.sqliteFile);
            }
        }

        // Logging
        Map<String, Object> logging = (Map<String, Object>) data.get("logging");
        if (logging != null) {
            config.logLocalFile = getBoolean(logging, "local-file", config.logLocalFile);
            config.logJoins = getBoolean(logging, "log-joins", config.logJoins);
            config.logQuits = getBoolean(logging, "log-quits", config.logQuits);
            config.logDeniedAttempts = getBoolean(logging, "log-denied-attempts", config.logDeniedAttempts);
            config.logRoleCheckFailures = getBoolean(logging, "log-role-check-failures", config.logRoleCheckFailures);
            config.logLinkEvents = getBoolean(logging, "log-link-events", config.logLinkEvents);
            config.logAdminChanges = getBoolean(logging, "log-admin-changes", config.logAdminChanges);
            config.includeIpAddress = getBoolean(logging, "include-ip-address", config.includeIpAddress);
        }

        // Messages
        Map<String, Object> messages = (Map<String, Object>) data.get("messages");
        if (messages != null) {
            config.msgUnlinked = getString(messages, "unlinked", config.msgUnlinked);
            config.msgMissingAccessRole = getString(messages, "missing-access-role", config.msgMissingAccessRole);
            config.msgBlacklisted = getString(messages, "blacklisted", config.msgBlacklisted);
            config.msgVerificationUnavailable = getString(messages, "verification-unavailable", config.msgVerificationUnavailable);
        }

        return config;
    }

    public static PluginConfig loadFromFile(File file) {
        if (!file.exists()) {
            return new PluginConfig();
        }
        try (InputStream in = new FileInputStream(file)) {
            return load(in);
        } catch (Exception e) {
            return new PluginConfig();
        }
    }

    public List<String> validate() {
        List<String> warnings = new ArrayList<>();
        if (botToken.isBlank() || botToken.contains("REPLACE")) {
            warnings.add("Discord bot token is not configured (check RIZZLERS_DISCORD_TOKEN). Bot integration disabled.");
        }
        if (guildId.isBlank() || guildId.contains("REPLACE")) {
            warnings.add("discord.guild-id is not configured.");
        }
        if (logChannelId.isBlank() || logChannelId.contains("REPLACE")) {
            warnings.add("discord.log-channel-id is not configured.");
        }
        return warnings;
    }

    private static String sanitizeSnowflake(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.startsWith("REPLACE") ? "" : s;
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object o = map.get(key);
        return o != null ? o.toString().trim() : def;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean def) {
        Object o = map.get(key);
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(o.toString().trim());
        return def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object o = map.get(key);
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try { return Integer.parseInt(o.toString().trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    // Getters and Setters
    public String getNetworkName() { return networkName; }
    public boolean isRequireLink() { return requireLink; }
    public boolean isDenyOnProviderError() { return denyOnProviderError; }
    public String getBotToken() { return botToken; }
    public String getGuildId() { return guildId; }
    public void setGuildId(String guildId) { this.guildId = guildId; }
    public String getAccessRoleId() { return accessRoleId; }
    public void setAccessRoleId(String accessRoleId) { this.accessRoleId = accessRoleId; }
    public String getBlacklistRoleId() { return blacklistRoleId; }
    public void setBlacklistRoleId(String blacklistRoleId) { this.blacklistRoleId = blacklistRoleId; }
    public String getLogGuildId() { return logGuildId; }
    public void setLogGuildId(String logGuildId) { this.logGuildId = logGuildId; }
    public String getLogChannelId() { return logChannelId; }
    public void setLogChannelId(String logChannelId) { this.logChannelId = logChannelId; }
    public String getLinkInstructions() { return linkInstructions; }
    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public int getCodeExpirySeconds() { return codeExpirySeconds; }
    public int getMaxAttemptsPerHour() { return maxAttemptsPerHour; }
    public int getMaxActiveLinksPerDiscord() { return maxActiveLinksPerDiscord; }
    public boolean isAllowUnlink() { return allowUnlink; }
    public String getStorageDriver() { return storageDriver; }
    public String getMysqlHost() { return mysqlHost; }
    public int getMysqlPort() { return mysqlPort; }
    public String getMysqlDatabase() { return mysqlDatabase; }
    public String getMysqlUsername() { return mysqlUsername; }
    public String getMysqlPassword() { return mysqlPassword; }
    public boolean isMysqlSsl() { return mysqlSsl; }
    public String getSqliteFile() { return sqliteFile; }
    public boolean isLogLocalFile() { return logLocalFile; }
    public boolean isLogJoins() { return logJoins; }
    public boolean isLogQuits() { return logQuits; }
    public boolean isLogDeniedAttempts() { return logDeniedAttempts; }
    public boolean isLogRoleCheckFailures() { return logRoleCheckFailures; }
    public boolean isLogLinkEvents() { return logLinkEvents; }
    public boolean isLogAdminChanges() { return logAdminChanges; }
    public boolean isIncludeIpAddress() { return includeIpAddress; }
    public String getMsgUnlinked() { return msgUnlinked; }
    public String getMsgMissingAccessRole() { return msgMissingAccessRole; }
    public String getMsgBlacklisted() { return msgBlacklisted; }
    public String getMsgVerificationUnavailable() { return msgVerificationUnavailable; }
}
