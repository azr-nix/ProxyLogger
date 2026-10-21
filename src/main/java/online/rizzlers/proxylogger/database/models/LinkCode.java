package online.rizzlers.proxylogger.database.models;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a one-time account linking code (hashed).
 */
public record LinkCode(
        String codeHash,
        UUID minecraftUuid,
        String minecraftUsername,
        String accountType,
        Instant createdAt,
        Instant expiresAt,
        int attempts
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
