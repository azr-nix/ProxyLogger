package online.rizzlers.proxylogger.audit;

import online.rizzlers.proxylogger.config.PluginConfig;
import online.rizzlers.proxylogger.database.DatabaseManager;
import online.rizzlers.proxylogger.database.models.AuditEvent;
import online.rizzlers.proxylogger.discord.DiscordService;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Handles persistent database auditing, rotating local file logging, and Discord embed dispatch.
 */
public class AuditLogger implements AutoCloseable {
    private final PluginConfig config;
    private final DatabaseManager databaseManager;
    private final DiscordService discordService;
    private final File auditFile;
    private final Logger logger;
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor();

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    public AuditLogger(PluginConfig config,
                       DatabaseManager databaseManager,
                       DiscordService discordService,
                       File dataFolder,
                       Logger logger) {
        this.config = config;
        this.databaseManager = databaseManager;
        this.discordService = discordService;
        this.auditFile = new File(dataFolder, "audit.log");
        this.logger = logger;
    }

    public void log(AuditEvent event) {
        logExecutor.submit(() -> {
            try {
                // 1. Record to database
                databaseManager.recordAudit(event);

                // 2. Local file log
                if (config.isLogLocalFile()) {
                    writeLocalLog(event);
                }

                // 3. Dispatch Discord embed
                if (shouldSendToDiscord(event) && discordService != null) {
                    discordService.sendLogEmbed(event);
                }
            } catch (Exception e) {
                if (logger != null) logger.warning("Failed to process audit event: " + e.getMessage());
            }
        });
    }

    private void writeLocalLog(AuditEvent event) {
        try {
            if (!auditFile.getParentFile().exists()) {
                auditFile.getParentFile().mkdirs();
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(auditFile, true))) {
                String timestamp = FORMATTER.format(event.createdAt());
                String line = String.format("[%s] [%s] [%s] Player: %s (%s) Discord: %s Details: %s%n",
                        timestamp,
                        event.eventType(),
                        event.result(),
                        event.minecraftUsername() != null ? event.minecraftUsername() : "N/A",
                        event.minecraftUuid() != null ? event.minecraftUuid().toString() : "N/A",
                        event.discordId() != null ? event.discordId() : "N/A",
                        event.reason() != null ? event.reason() : "N/A"
                );
                writer.write(line);
            }
        } catch (IOException e) {
            if (logger != null) logger.warning("Could not write to audit.log: " + e.getMessage());
        }
    }

    private boolean shouldSendToDiscord(AuditEvent event) {
        String type = event.eventType().toUpperCase();
        if (type.contains("JOIN") && !config.isLogJoins()) return false;
        if (type.contains("QUIT") && !config.isLogQuits()) return false;
        if (type.contains("DENY") && !config.isLogDeniedAttempts()) return false;
        if (type.contains("LINK") && !config.isLogLinkEvents()) return false;
        if (type.contains("ADMIN") && !config.isLogAdminChanges()) return false;
        return true;
    }

    @Override
    public void close() {
        logExecutor.shutdown();
        try {
            if (!logExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                logExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
