package online.rizzlers.proxylogger.discord;

import java.util.Set;

/**
 * Interface for querying Discord member roles to allow easy testing and mocking.
 */
public interface DiscordRoleProvider {
    /**
     * Retrieves the set of role IDs (snowflakes) assigned to a Discord member in a guild.
     *
     * @param guildId   Discord guild ID
     * @param discordId Discord user ID
     * @return Set of role IDs as strings
     * @throws Exception if guild/member cannot be fetched or an API outage occurs
     */
    Set<String> getMemberRoles(String guildId, String discordId) throws Exception;

    /**
     * Checks if the Discord provider is initialized and connected.
     */
    boolean isReady();
}
