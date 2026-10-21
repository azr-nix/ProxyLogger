package online.rizzlers.proxylogger.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import online.rizzlers.proxylogger.access.AccessDecision;
import online.rizzlers.proxylogger.access.AccessManager;
import online.rizzlers.proxylogger.audit.AuditLogger;
import online.rizzlers.proxylogger.config.PluginConfig;
import online.rizzlers.proxylogger.database.models.AuditEvent;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Velocity event listener handling pre-login access gatekeeping and disconnection audits.
 * Uses asynchronous continuation semantics via EventTask to prevent blocking the event loop.
 */
public class ConnectionListener {
    private final PluginConfig config;
    private final AccessManager accessManager;
    private final AuditLogger auditLogger;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ConnectionListener(PluginConfig config,
                              AccessManager accessManager,
                              AuditLogger auditLogger,
                              Logger logger) {
        this.config = config;
        this.accessManager = accessManager;
        this.auditLogger = auditLogger;
        this.logger = logger;
    }

    @Subscribe(order = PostOrder.FIRST)
    public EventTask onLogin(LoginEvent event) {
        return EventTask.async(() -> {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            String username = player.getUsername();
            String accountType = player.isOnlineMode() ? "online" : "offline";
            String ip = config.isIncludeIpAddress() ? player.getRemoteAddress().toString() : "REDACTED";

            try {
                AccessDecision decision = accessManager.evaluateAccess(uuid, username, accountType, ip);

                if (decision.allowed()) {
                    event.setResult(ResultedEvent.ComponentResult.allowed());
                    auditLogger.log(AuditEvent.create(
                            "LOGIN_ALLOW",
                            uuid,
                            username,
                            null,
                            config.getGuildId(),
                            null,
                            decision.result(),
                            decision.reason()
                    ));
                } else {
                    String kickTemplate = getKickMessage(decision.messageKey());
                    Component kickComponent = miniMessage.deserialize(kickTemplate);
                    event.setResult(ResultedEvent.ComponentResult.denied(kickComponent));
                    auditLogger.log(AuditEvent.create(
                            "LOGIN_DENIED",
                            uuid,
                            username,
                            null,
                            config.getGuildId(),
                            null,
                            decision.result(),
                            decision.reason()
                    ));
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.severe("Unhandled exception during login evaluation for " + username + ": " + t.getMessage());
                }
                if (config.isDenyOnProviderError()) {
                    Component fallbackKick = miniMessage.deserialize(config.getMsgVerificationUnavailable());
                    event.setResult(ResultedEvent.ComponentResult.denied(fallbackKick));
                }
            }
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (!config.isLogQuits()) return;
        Player player = event.getPlayer();
        auditLogger.log(AuditEvent.create(
                "QUIT",
                player.getUniqueId(),
                player.getUsername(),
                null,
                config.getGuildId(),
                null,
                "DISCONNECTED",
                "Player disconnected from proxy"
        ));
    }

    private String getKickMessage(String messageKey) {
        if (messageKey == null) return "<red>Access denied.</red>";
        return switch (messageKey) {
            case "unlinked" -> config.getMsgUnlinked();
            case "missing-access-role" -> config.getMsgMissingAccessRole();
            case "blacklisted" -> config.getMsgBlacklisted();
            case "verification-unavailable" -> config.getMsgVerificationUnavailable();
            default -> "<red>Access denied.</red>";
        };
    }
}
