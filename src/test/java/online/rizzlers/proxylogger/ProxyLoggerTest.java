package online.rizzlers.proxylogger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import online.rizzlers.proxylogger.access.AccessDecision;
import online.rizzlers.proxylogger.access.AccessManager;
import online.rizzlers.proxylogger.config.PluginConfig;
import online.rizzlers.proxylogger.database.DatabaseManager;
import online.rizzlers.proxylogger.database.models.AuditEvent;
import online.rizzlers.proxylogger.database.models.LinkedAccount;
import online.rizzlers.proxylogger.discord.DiscordRoleProvider;
import online.rizzlers.proxylogger.discord.DiscordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ProxyLoggerTest {

    private HikariDataSource dataSource;
    private DatabaseManager databaseManager;
    private PluginConfig config;
    private MockDiscordRoleProvider mockDiscord;
    private AccessManager accessManager;

    private static final String GUILD_ID = "111222333444555666";
    private static final String ALLOW_ROLE_ID = "888999000111222333";
    private static final String BLACKLIST_ROLE_ID = "999888777666555444";

    public static class MockDiscordRoleProvider implements DiscordRoleProvider {
        private final Map<String, Set<String>> userRoles = new HashMap<>();
        private boolean shouldThrow = false;
        private String errorMessage = "Simulated Discord Gateway Outage";

        public void setRoles(String discordId, Set<String> roles) {
            userRoles.put(discordId, new HashSet<>(roles));
        }

        public void setShouldThrow(boolean shouldThrow) {
            this.shouldThrow = shouldThrow;
        }

        @Override
        public Set<String> getMemberRoles(String guildId, String discordId) throws Exception {
            if (shouldThrow) {
                throw new Exception(errorMessage);
            }
            return userRoles.getOrDefault(discordId, Collections.emptySet());
        }

        @Override
        public boolean isReady() {
            return !shouldThrow;
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        // Setup SQLite in-memory database with shared cache for connection pool testing
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setJdbcUrl("jdbc:sqlite:file:testdb_" + UUID.randomUUID() + "?mode=memory&cache=shared");
        hikariConfig.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(hikariConfig);

        databaseManager = new DatabaseManager(dataSource, true, null);

        String yaml = """
            network:
              name: "RIZZLERS"
              require-link: true
              deny-on-provider-error: true
            discord:
              guild-id: "111222333444555666"
              access-role-id: "888999000111222333"
              blacklist-role-id: "999888777666555444"
              log-guild-id: "111222333444555666"
              log-channel-id: "222333444555666777"
            linking:
              code-expiry-seconds: 300
              max-attempts-per-hour: 5
              max-active-links-per-discord: 1
            """;
        config = PluginConfig.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        mockDiscord = new MockDiscordRoleProvider();
        accessManager = new AccessManager(config, databaseManager, mockDiscord, null);
    }

    @AfterEach
    public void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Test
    @DisplayName("Verify unlinked player is denied when require-link is true")
    public void testMissingLinkDeniesWhenRequired() {
        UUID uuid = UUID.randomUUID();
        AccessDecision decision = accessManager.evaluateAccess(uuid, "Steve", "online", "127.0.0.1");

        assertFalse(decision.allowed(), "Unlinked player must be denied");
        assertEquals(AccessDecision.RESULT_UNLINKED, decision.result());
        assertEquals("unlinked", decision.messageKey());
    }

    @Test
    @DisplayName("Verify unlinked player is allowed when require-link is false")
    public void testMissingLinkAllowsWhenNotRequired() {
        String yaml = """
            network:
              require-link: false
              deny-on-provider-error: true
            discord:
              guild-id: "111222333444555666"
            """;
        PluginConfig noLinkConfig = PluginConfig.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        AccessManager permissiveManager = new AccessManager(noLinkConfig, databaseManager, mockDiscord, null);

        UUID uuid = UUID.randomUUID();
        AccessDecision decision = permissiveManager.evaluateAccess(uuid, "Alex", "offline", "127.0.0.1");

        assertTrue(decision.allowed(), "Player should be allowed when linking is optional");
        assertEquals(AccessDecision.RESULT_ALLOWED, decision.result());
    }

    @Test
    @DisplayName("Verify Blacklist Precedence: Member possessing BOTH allow and blacklist roles is DENIED")
    public void testBlacklistPrecedenceOverAllowRole() throws Exception {
        UUID uuid = UUID.randomUUID();
        String discordId = "123456789012345678";

        // Create link
        String code = accessManager.generateLinkCode(uuid, "BlacklistedUser", "online");
        DatabaseManager.LinkResult linkResult = databaseManager.consumeLinkCode(DiscordService.sha256(code), discordId);
        assertTrue(linkResult.success(), "Account linking should succeed");

        // User has BOTH allow role and blacklist role
        mockDiscord.setRoles(discordId, Set.of(ALLOW_ROLE_ID, BLACKLIST_ROLE_ID));

        AccessDecision decision = accessManager.evaluateAccess(uuid, "BlacklistedUser", "online", "127.0.0.1");

        assertFalse(decision.allowed(), "User with blacklisted role must be denied even if possessing allow role");
        assertEquals(AccessDecision.RESULT_BLACKLIST_DENY, decision.result());
        assertEquals("blacklisted", decision.messageKey());
    }

    @Test
    @DisplayName("Verify member possessing required access role is allowed")
    public void testAllowedWhenHasAccessRole() throws Exception {
        UUID uuid = UUID.randomUUID();
        String discordId = "234567890123456789";

        String code = accessManager.generateLinkCode(uuid, "VerifiedUser", "online");
        DatabaseManager.LinkResult linkResult = databaseManager.consumeLinkCode(DiscordService.sha256(code), discordId);
        assertTrue(linkResult.success());

        mockDiscord.setRoles(discordId, Set.of(ALLOW_ROLE_ID));

        AccessDecision decision = accessManager.evaluateAccess(uuid, "VerifiedUser", "online", "127.0.0.1");

        assertTrue(decision.allowed());
        assertEquals(AccessDecision.RESULT_ALLOWED, decision.result());
    }

    @Test
    @DisplayName("Verify member lacking required access role is denied")
    public void testDeniedWhenMissingAccessRole() throws Exception {
        UUID uuid = UUID.randomUUID();
        String discordId = "345678901234567890";

        String code = accessManager.generateLinkCode(uuid, "UnverifiedUser", "online");
        DatabaseManager.LinkResult linkResult = databaseManager.consumeLinkCode(DiscordService.sha256(code), discordId);
        assertTrue(linkResult.success());

        // Member has random role, not the required access role
        mockDiscord.setRoles(discordId, Set.of("777777777777777777"));

        AccessDecision decision = accessManager.evaluateAccess(uuid, "UnverifiedUser", "online", "127.0.0.1");

        assertFalse(decision.allowed());
        assertEquals(AccessDecision.RESULT_MISSING_ROLE, decision.result());
        assertEquals("missing-access-role", decision.messageKey());
    }

    @Test
    @DisplayName("Verify one-time link code generation and expiration calculation")
    public void testOneTimeCodeGenerationAndExpiry() throws Exception {
        UUID uuid = UUID.randomUUID();
        String code = accessManager.generateLinkCode(uuid, "CodeTestUser", "offline");

        assertNotNull(code);
        assertEquals(6, code.length());

        String hash = DiscordService.sha256(code);
        Optional<online.rizzlers.proxylogger.database.models.LinkCode> optCode = databaseManager.getLinkCode(hash);
        assertTrue(optCode.isPresent());
        assertFalse(optCode.get().isExpired());
        assertEquals("offline", optCode.get().accountType());
    }

    @Test
    @DisplayName("Verify replay attacks: Consumed code cannot be used again")
    public void testReplayAttackRejected() throws Exception {
        UUID uuid = UUID.randomUUID();
        String discordId1 = "456789012345678901";
        String discordId2 = "567890123456789012";

        String code = accessManager.generateLinkCode(uuid, "ReplayUser", "online");
        String hash = DiscordService.sha256(code);

        // First consumption succeeds
        DatabaseManager.LinkResult firstResult = databaseManager.consumeLinkCode(hash, discordId1);
        assertTrue(firstResult.success());

        // Second consumption with same code must fail
        DatabaseManager.LinkResult secondResult = databaseManager.consumeLinkCode(hash, discordId2);
        assertFalse(secondResult.success());
        assertTrue(secondResult.message().contains("Invalid or expired"));
    }

    @Test
    @DisplayName("Verify duplicate claims: Discord account cannot link to multiple Minecraft players")
    public void testDuplicateDiscordLinkRejected() throws Exception {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        String discordId = "678901234567890123";

        String code1 = accessManager.generateLinkCode(player1, "PlayerOne", "online");
        DatabaseManager.LinkResult res1 = databaseManager.consumeLinkCode(DiscordService.sha256(code1), discordId);
        assertTrue(res1.success());

        String code2 = accessManager.generateLinkCode(player2, "PlayerTwo", "offline");
        DatabaseManager.LinkResult res2 = databaseManager.consumeLinkCode(DiscordService.sha256(code2), discordId);
        assertFalse(res2.success(), "Same Discord ID should not be linked twice");
        assertTrue(res2.message().contains("already linked to player PlayerOne"));
    }

    @Test
    @DisplayName("Verify Discord role API outage fails closed")
    public void testRoleApiOutageFailsClosed() throws Exception {
        UUID uuid = UUID.randomUUID();
        String discordId = "789012345678901234";

        String code = accessManager.generateLinkCode(uuid, "OutageUser", "online");
        databaseManager.consumeLinkCode(DiscordService.sha256(code), discordId);

        // Simulate Discord gateway/REST failure
        mockDiscord.setShouldThrow(true);

        AccessDecision decision = accessManager.evaluateAccess(uuid, "OutageUser", "online", "127.0.0.1");

        assertFalse(decision.allowed(), "Must fail closed on Discord outage");
        assertEquals(AccessDecision.RESULT_ERROR_DENY, decision.result());
        assertEquals("verification-unavailable", decision.messageKey());
    }

    @Test
    @DisplayName("Verify database outage fails closed")
    public void testDatabaseOutageFailsClosed() {
        // Close data source to simulate database disconnection/crash
        databaseManager.close();

        UUID uuid = UUID.randomUUID();
        AccessDecision decision = accessManager.evaluateAccess(uuid, "DbCrashUser", "online", "127.0.0.1");

        assertFalse(decision.allowed(), "Must fail closed on database outage");
        assertEquals(AccessDecision.RESULT_ERROR_DENY, decision.result());
    }

    @Test
    @DisplayName("Verify link code generation rate limit")
    public void testRateLimitingForCodeGeneration() throws Exception {
        UUID uuid = UUID.randomUUID();

        // Config max attempts = 5
        for (int i = 0; i < 5; i++) {
            assertNotNull(accessManager.generateLinkCode(uuid, "SpamUser", "online"));
        }

        // 6th generation attempt within same hour must throw rate limit exception
        assertThrows(IllegalStateException.class, () ->
                accessManager.generateLinkCode(uuid, "SpamUser", "online"));
    }

    @Test
    @DisplayName("Verify audit logs are recorded in database table")
    public void testAuditLogEntriesRecorded() throws SQLException {
        UUID uuid = UUID.randomUUID();
        AuditEvent event = AuditEvent.create(
                "TEST_EVENT",
                uuid,
                "AuditedUser",
                "111111111111111111",
                GUILD_ID,
                ALLOW_ROLE_ID,
                "ALLOWED",
                "Unit test verification"
        );
        databaseManager.recordAudit(event);

        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM access_audit WHERE minecraft_username = 'AuditedUser'")) {
            assertTrue(rs.next());
            assertEquals("TEST_EVENT", rs.getString("event_type"));
            assertEquals("ALLOWED", rs.getString("result"));
            assertEquals("Unit test verification", rs.getString("reason"));
        }
    }
}
