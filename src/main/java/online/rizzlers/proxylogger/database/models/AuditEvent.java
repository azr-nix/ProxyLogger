package online.rizzlers.proxylogger.database.models;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an audit log entry for joins, denies, links, unlinks, and admin commands.
 */
public record AuditEvent(
        long id,
        String eventType,
        UUID minecraftUuid,
        String minecraftUsername,
        String discordId,
        String guildId,
        String roleId,
        String result,
        String reason,
        Instant createdAt
) {
    public static AuditEvent create(String eventType,
                                    UUID minecraftUuid,
                                    String minecraftUsername,
                                    String discordId,
                                    String guildId,
                                    String roleId,
                                    String result,
                                    String reason) {
        return new AuditEvent(
                0,
                eventType,
                minecraftUuid,
                minecraftUsername,
                discordId,
                guildId,
                roleId,
                result,
                reason,
                Instant.now()
        );
    }
}
