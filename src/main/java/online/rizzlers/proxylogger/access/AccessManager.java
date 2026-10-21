package online.rizzlers.proxylogger.access;

import online.rizzlers.proxylogger.config.PluginConfig;
import online.rizzlers.proxylogger.database.DatabaseManager;
import online.rizzlers.proxylogger.database.models.AccessRule;
import online.rizzlers.proxylogger.database.models.LinkedAccount;
import online.rizzlers.proxylogger.discord.DiscordRoleProvider;
import online.rizzlers.proxylogger.discord.DiscordService;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Evaluates network access decisions according to strict role precedence:
 * Blacklist Deny -> Required Allow Role -> Allow.
 */
public class AccessManager {
    private final PluginConfig config;
    private final DatabaseManager databaseManager;
    private final DiscordRoleProvider discordRoleProvider;
    private final Logger logger;

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    // In-memory rate limiting for code generation: UUID -> list of generation timestamps
    private final Map<UUID, List<Instant>> codeRateLimits = new ConcurrentHashMap<>();

    public AccessManager(PluginConfig config,
                         DatabaseManager databaseManager,
                         DiscordRoleProvider discordRoleProvider,
                         Logger logger) {
        this.config = config;
        this.databaseManager = databaseManager;
        this.discordRoleProvider = discordRoleProvider;
        this.logger = logger;
    }

    public AccessDecision evaluateAccess(UUID uuid, String username, String accountType, String ip) {
        // 1. Check linked account in database
        Optional<LinkedAccount> optLinked;
        try {
            optLinked = databaseManager.getLinkedAccount(uuid);
        } catch (SQLException e) {
            if (logger != null) logger.severe("Database outage during access check for " + username + ": " + e.getMessage());
            if (config.isDenyOnProviderError()) {
                return AccessDecision.errorDeny("Database query failure: " + e.getMessage());
            } else {
                return AccessDecision.allow("Database error ignored by config");
            }
        }

        if (optLinked.isEmpty() || !optLinked.get().isLinked()) {
            if (config.isRequireLink()) {
                return AccessDecision.unlinked("Player " + username + " is not linked to Discord.");
            } else {
                return AccessDecision.allow("Unlinked account permitted by require-link=false");
            }
        }

        LinkedAccount linked = optLinked.get();
        String discordId = linked.discordId();

        // 2. Retrieve member roles from Discord
        Set<String> memberRoles;
        try {
            memberRoles = discordRoleProvider.getMemberRoles(config.getGuildId(), discordId);
        } catch (Exception e) {
            if (logger != null) logger.warning("Discord role API failure for " + username + " (" + discordId + "): " + e.getMessage());
            if (config.isDenyOnProviderError()) {
                return AccessDecision.errorDeny("Discord role API error: " + e.getMessage());
            } else {
                return AccessDecision.allow("Provider error ignored by config");
            }
        }

        // 3. Blacklist Role Precedence Check (FIRST)
        Set<String> blacklistRoles = getEffectiveBlacklistRoles(config.getGuildId());
        for (String roleId : memberRoles) {
            if (blacklistRoles.contains(roleId)) {
                return AccessDecision.blacklistDeny("Discord user " + discordId + " possesses blacklisted role: " + roleId);
            }
        }

        // 4. Required Access Role Check (SECOND)
        Set<String> allowRoles = getEffectiveAllowRoles(config.getGuildId());
        if (!allowRoles.isEmpty()) {
            boolean hasAllowRole = false;
            for (String roleId : memberRoles) {
                if (allowRoles.contains(roleId)) {
                    hasAllowRole = true;
                    break;
                }
            }
            if (!hasAllowRole) {
                return AccessDecision.missingRole("Discord user " + discordId + " does not possess any required access role.");
            }
        }

        // 5. Allowed (THIRD)
        databaseManager.updateLastSeen(uuid);
        return AccessDecision.allow("Access verified for " + username + " [Account: " + accountType + "]");
    }

    public Set<String> getEffectiveBlacklistRoles(String guildId) {
        Set<String> roles = new HashSet<>();
        String configRole = config.getBlacklistRoleId();
        if (configRole != null && !configRole.isBlank()) {
            roles.add(configRole);
        }
        try {
            List<AccessRule> dbRules = databaseManager.getAccessRules("blacklist");
            for (AccessRule r : dbRules) {
                if (r.guildId().equalsIgnoreCase(guildId) || r.guildId().isBlank()) {
                    roles.add(r.roleId());
                }
            }
        } catch (SQLException e) {
            if (logger != null) logger.warning("Could not load blacklist rules from DB: " + e.getMessage());
        }
        return roles;
    }

    public Set<String> getEffectiveAllowRoles(String guildId) {
        Set<String> roles = new HashSet<>();
        String configRole = config.getAccessRoleId();
        if (configRole != null && !configRole.isBlank()) {
            roles.add(configRole);
        }
        try {
            List<AccessRule> dbRules = databaseManager.getAccessRules("allow");
            for (AccessRule r : dbRules) {
                if (r.guildId().equalsIgnoreCase(guildId) || r.guildId().isBlank()) {
                    roles.add(r.roleId());
                }
            }
        } catch (SQLException e) {
            if (logger != null) logger.warning("Could not load allow rules from DB: " + e.getMessage());
        }
        return roles;
    }

    public String generateLinkCode(UUID uuid, String username, String accountType) throws SQLException {
        // Enforce rate limiting
        Instant now = Instant.now();
        Instant oneHourAgo = now.minusSeconds(3600);
        List<Instant> recent = codeRateLimits.computeIfAbsent(uuid, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        recent.removeIf(t -> t.isBefore(oneHourAgo));

        if (recent.size() >= config.getMaxAttemptsPerHour()) {
            throw new IllegalStateException("You have reached the link code generation limit (max "
                    + config.getMaxAttemptsPerHour() + " per hour). Please try again later.");
        }

        // Generate 6-char random alphanumeric code
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        String code = sb.toString();
        String codeHash = DiscordService.sha256(code);
        Instant expiresAt = now.plusSeconds(config.getCodeExpirySeconds());

        boolean created = databaseManager.createLinkCode(uuid, username, accountType, codeHash, expiresAt);
        if (!created) {
            throw new SQLException("Failed to persist link code.");
        }

        recent.add(now);
        return code;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public PluginConfig getConfig() {
        return config;
    }
}
