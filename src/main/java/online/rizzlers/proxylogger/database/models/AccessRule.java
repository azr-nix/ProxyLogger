package online.rizzlers.proxylogger.database.models;

import java.time.Instant;

/**
 * Represents a Discord role access rule (allow or blacklist).
 */
public record AccessRule(
        long id,
        String ruleType,
        String guildId,
        String roleId,
        boolean enabled,
        Instant createdAt
) {
    public boolean isBlacklist() {
        return "blacklist".equalsIgnoreCase(ruleType);
    }

    public boolean isAllow() {
        return "allow".equalsIgnoreCase(ruleType);
    }
}
