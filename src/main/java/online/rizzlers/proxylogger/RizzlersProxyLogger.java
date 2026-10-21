package online.rizzlers.proxylogger;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import online.rizzlers.proxylogger.access.AccessManager;
import online.rizzlers.proxylogger.audit.AuditLogger;
import online.rizzlers.proxylogger.command.PlogCommand;
import online.rizzlers.proxylogger.config.PluginConfig;
import online.rizzlers.proxylogger.database.DatabaseManager;
import online.rizzlers.proxylogger.discord.DiscordService;
import online.rizzlers.proxylogger.listener.ConnectionListener;
import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Plugin(
        id = "rizzlersproxylogger",
        name = "RIZZLERS ProxyLogger",
        version = "1.1.0",
        description = "Discord role-gated access control and audit logging for Velocity proxy on RIZZLERS network.",
        authors = {"RIZZLERS"}
)
public class RizzlersProxyLogger {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private DatabaseManager databaseManager;
    private DiscordService discordService;
    private AuditLogger auditLogger;
    private AccessManager accessManager;

    @Inject
    public RizzlersProxyLogger(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Starting RIZZLERS ProxyLogger initialization...");

        // 1. Ensure data directory & load config
        File configFile = new File(dataDirectory.toFile(), "config.yml");
        if (!configFile.exists()) {
            try {
                if (!dataDirectory.toFile().exists()) {
                    dataDirectory.toFile().mkdirs();
                }
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile.toPath());
                    }
                }
            } catch (Exception e) {
                logger.error("Could not save default config.yml: {}", e.getMessage());
            }
        }

        this.config = PluginConfig.loadFromFile(configFile);
        List<String> warnings = config.validate();
        for (String w : warnings) {
            logger.warn("[Config Warning] {}", w);
        }

        java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger("RizzlersProxyLogger");

        // 2. Initialize Database Persistence
        try {
            this.databaseManager = new DatabaseManager(config, dataDirectory.toFile(), julLogger);
            logger.info("Database persistence initialized successfully (Driver: {}).", config.getStorageDriver().toUpperCase());
        } catch (Exception e) {
            logger.error("FATAL: Failed to initialize database persistence: {}", e.getMessage(), e);
        }

        // 3. Initialize Discord Integration
        this.discordService = new DiscordService(config, databaseManager, julLogger);

        // 4. Initialize Audit Logging
        this.auditLogger = new AuditLogger(config, databaseManager, discordService, dataDirectory.toFile(), julLogger);

        // 5. Initialize Access Management
        this.accessManager = new AccessManager(config, databaseManager, discordService, julLogger);

        // 6. Register Velocity Event Listener
        ConnectionListener listener = new ConnectionListener(config, accessManager, auditLogger, julLogger);
        server.getEventManager().register(this, listener);

        // 7. Register Command /plog
        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("plog")
                .aliases("proxylogger", "rplog")
                .plugin(this)
                .build();
        PlogCommand command = new PlogCommand(config, accessManager, databaseManager, discordService, auditLogger, this::reloadPlugin);
        commandManager.register(meta, command);

        logger.info("RIZZLERS ProxyLogger v1.1.0 enabled successfully. Pre-login access enforcement is active.");
    }

    public void reloadPlugin() {
        File configFile = new File(dataDirectory.toFile(), "config.yml");
        this.config = PluginConfig.loadFromFile(configFile);
        logger.info("RIZZLERS ProxyLogger configuration reloaded.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down RIZZLERS ProxyLogger services...");
        if (auditLogger != null) {
            auditLogger.close();
        }
        if (discordService != null) {
            discordService.close();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        logger.info("RIZZLERS ProxyLogger shutdown complete.");
    }

    public PluginConfig getConfig() {
        return config;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public DiscordService getDiscordService() {
        return discordService;
    }

    public AccessManager getAccessManager() {
        return accessManager;
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }
}
