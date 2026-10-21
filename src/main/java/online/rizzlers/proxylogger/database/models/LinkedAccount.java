package online.rizzlers.proxylogger.database.models;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a linked Minecraft and Discord identity.
 */
public record LinkedAccount(
        UUID minecraftUuid,
        String minecraftUsername,
        String accountType,
        String discordId,
        Instant linkedAt,
        Instant lastSeen,
        String linkStatus
) {
    public boolean isLinked() {
        return "linked".equalsIgnoreCase(linkStatus);
    }

    public boolean isOffline() {
        return "offline".equalsIgnoreCase(accountType);
    }
}
