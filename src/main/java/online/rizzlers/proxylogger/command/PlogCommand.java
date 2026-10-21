package online.rizzlers.proxylogger.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import online.rizzlers.proxylogger.access.AccessManager;
import online.rizzlers.proxylogger.audit.AuditLogger;
import online.rizzlers.proxylogger.config.PluginConfig;
import online.rizzlers.proxylogger.database.DatabaseManager;
import online.rizzlers.proxylogger.database.models.AccessRule;
import online.rizzlers.proxylogger.database.models.AuditEvent;
import online.rizzlers.proxylogger.discord.DiscordService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles Velocity /plog command execution and tab completion.
 */
public class PlogCommand implements SimpleCommand {
    public static final String PERM_ADMIN = "rizzlersproxylogger.admin";
    public static final String PERM_LINK = "rizzlersproxylogger.link";

    private final PluginConfig config;
    private final AccessManager accessManager;
    private final DatabaseManager databaseManager;
    private final DiscordService discordService;
    private final AuditLogger auditLogger;
    private final Runnable reloadAction;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PlogCommand(PluginConfig config,
                       AccessManager accessManager,
                       DatabaseManager databaseManager,
                       DiscordService discordService,
                       AuditLogger auditLogger,
                       Runnable reloadAction) {
        this.config = config;
        this.accessManager = accessManager;
        this.databaseManager = databaseManager;
        this.discordService = discordService;
        this.auditLogger = auditLogger;
        this.reloadAction = reloadAction;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // Player /plog link command
        if ("link".equals(sub)) {
            handleLink(source);
            return;
        }

        // Player /plog unlink command
        if ("unlink".equals(sub)) {
            handleUnlink(source, args);
            return;
        }

        // Remaining commands require admin permission
        if (!source.hasPermission(PERM_ADMIN)) {
            source.sendMessage(miniMessage.deserialize("<red>You do not have permission to use administrative ProxyLogger commands.</red>"));
            return;
        }

        switch (sub) {
            case "status" -> handleStatus(source);
            case "reload" -> handleReload(source);
            case "log" -> handleLog(source, args);
            case "whitelist" -> handleRuleCommand(source, args, "allow");
            case "blacklist" -> handleRuleCommand(source, args, "blacklist");
            default -> sendHelp(source);
        }
    }

    private void handleLink(CommandSource source) {
        if (!(source instanceof Player player)) {
            source.sendMessage(miniMessage.deserialize("<red>Only players can execute /plog link.</red>"));
            return;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        String accountType = player.isOnlineMode() ? "online" : "offline";

        try {
            String code = accessManager.generateLinkCode(uuid, username, accountType);
            Component msg = miniMessage.deserialize(String.format("""
                <gradient:#ff4fd8:#7c4dff><bold>RIZZLERS ACCOUNT LINKING</bold></gradient>
                <gray>Your temporary 1-time link code is: </gray><yellow><bold>%s</bold></yellow>
                <gray>Run </gray><click:suggest_command:'/link %s'><hover:show_text:'Click to copy'><white>/link %s</white></hover></click><gray> in the RIZZLERS Discord.</gray>
                <dark_gray>This code expires in 5 minutes. Account type: %s.</dark_gray>
                """, code, code, code, accountType.toUpperCase()));
            player.sendMessage(msg);
        } catch (IllegalStateException e) {
            player.sendMessage(miniMessage.deserialize("<red>" + e.getMessage() + "</red>"));
        } catch (Exception e) {
            player.sendMessage(miniMessage.deserialize("<red>Failed to generate link code. Please contact staff.</red>"));
        }
    }

    private void handleUnlink(CommandSource source, String[] args) {
        if (args.length > 1) {
            if (!source.hasPermission(PERM_ADMIN)) {
                source.sendMessage(miniMessage.deserialize("<red>You do not have permission to unlink other players. Only administrators can use /plog unlink <player>.</red>"));
                return;
            }
            // Admin unlinking target
            String target = args[1];
            try {
                boolean success;
                if (target.contains("-") && target.length() == 36) {
                    success = databaseManager.unlinkAccount(UUID.fromString(target));
                } else if (target.matches("\\d{17,20}")) {
                    success = databaseManager.unlinkAccountByDiscordId(target);
                } else {
                    success = databaseManager.unlinkAccountByUsername(target);
                }
                if (success) {
                    source.sendMessage(miniMessage.deserialize("<green>Successfully unlinked target: " + target + "</green>"));
                    auditLogger.log(AuditEvent.create("ADMIN_UNLINK", null, target, target, config.getGuildId(), null, "SUCCESS", "Admin unlinked: " + target));
                } else {
                    source.sendMessage(miniMessage.deserialize("<red>Target account was not found or already unlinked.</red>"));
                }
            } catch (Exception e) {
                source.sendMessage(miniMessage.deserialize("<red>Error unlinking target: " + e.getMessage() + "</red>"));
            }
            return;
        }

        if (!(source instanceof Player player)) {
            source.sendMessage(miniMessage.deserialize("<red>Console must specify target to unlink: /plog unlink <player></red>"));
            return;
        }

        if (!config.isAllowUnlink()) {
            player.sendMessage(miniMessage.deserialize("<red>Self-unlinking is disabled on the RIZZLERS network. Please contact staff to unlink your account.</red>"));
            return;
        }

        try {
            boolean success = databaseManager.unlinkAccount(player.getUniqueId());
            if (success) {
                player.sendMessage(miniMessage.deserialize("<green>Your Discord link has been disconnected.</green>"));
                auditLogger.log(AuditEvent.create("PLAYER_UNLINK", player.getUniqueId(), player.getUsername(), null, config.getGuildId(), null, "SUCCESS", "Player self-unlinked"));
            } else {
                player.sendMessage(miniMessage.deserialize("<yellow>You do not currently have an active link.</yellow>"));
            }
        } catch (Exception e) {
            player.sendMessage(miniMessage.deserialize("<red>Failed to unlink account: " + e.getMessage() + "</red>"));
        }
    }

    private void handleStatus(CommandSource source) {
        int linkedCount = databaseManager.countLinkedAccounts();
        boolean discordReady = discordService != null && discordService.isReady();

        Component status = miniMessage.deserialize(String.format("""
            <gradient:#ff4fd8:#7c4dff><bold>RIZZLERS ProxyLogger Status</bold></gradient>
            <gray>• Storage:</gray> <white>%s</white> <gray>(Linked accounts: </gray><green>%d</green><gray>)</gray>
            <gray>• Discord Bot:</gray> %s
            <gray>• Configured Guild:</gray> <white>%s</white>
            <gray>• Access Role:</gray> <white>%s</white>
            <gray>• Blacklist Role:</gray> <white>%s</white>
            <gray>• Log Channel:</gray> <white>%s</white>
            <gray>• Require Link:</gray> %s
            <gray>• Deny on Error:</gray> %s
            """,
                config.getStorageDriver().toUpperCase(),
                linkedCount,
                discordReady ? "<green>Connected</green>" : "<red>Disconnected / Degraded</red>",
                config.getGuildId().isBlank() ? "<dark_gray>None</dark_gray>" : config.getGuildId(),
                config.getAccessRoleId().isBlank() ? "<dark_gray>None</dark_gray>" : config.getAccessRoleId(),
                config.getBlacklistRoleId().isBlank() ? "<dark_gray>None</dark_gray>" : config.getBlacklistRoleId(),
                config.getLogChannelId().isBlank() ? "<dark_gray>None</dark_gray>" : config.getLogChannelId(),
                config.isRequireLink() ? "<green>true</green>" : "<red>false</red>",
                config.isDenyOnProviderError() ? "<green>true</green>" : "<yellow>false</yellow>"
        ));
        source.sendMessage(status);
    }

    private void handleReload(CommandSource source) {
        if (reloadAction != null) {
            reloadAction.run();
        }
        source.sendMessage(miniMessage.deserialize("<green>RIZZLERS ProxyLogger configuration reloaded successfully.</green>"));
        auditLogger.log(AuditEvent.create("ADMIN_RELOAD", null, null, null, config.getGuildId(), null, "SUCCESS", "Config reloaded by " + (source instanceof Player p ? p.getUsername() : "Console")));
    }

    private void handleLog(CommandSource source, String[] args) {
        if (args.length < 3) {
            source.sendMessage(miniMessage.deserialize("<yellow>Usage: /plog log <guild_id> <channel_id></yellow>"));
            return;
        }
        String guildId = args[1].trim();
        String channelId = args[2].trim();
        config.setLogGuildId(guildId);
        config.setLogChannelId(channelId);
        source.sendMessage(miniMessage.deserialize("<green>Discord log channel updated to Guild: " + guildId + ", Channel: " + channelId + "</green>"));
        auditLogger.log(AuditEvent.create("ADMIN_CONFIG", null, null, null, guildId, null, "SUCCESS", "Updated log destination to " + channelId));
    }

    private void handleRuleCommand(CommandSource source, String[] args, String ruleType) {
        // /plog whitelist <guild> <role>  OR  /plog whitelist add/remove/list
        String ruleName = ruleType.equalsIgnoreCase("allow") ? "whitelist" : "blacklist";

        if (args.length == 3 && !args[1].equalsIgnoreCase("add") && !args[1].equalsIgnoreCase("remove")) {
            // Shortcut: /plog whitelist <guild> <role>
            String guildId = args[1];
            String roleId = args[2];
            try {
                databaseManager.addAccessRule(ruleType, guildId, roleId);
                if (ruleType.equalsIgnoreCase("allow")) config.setAccessRoleId(roleId);
                else config.setBlacklistRoleId(roleId);
                source.sendMessage(miniMessage.deserialize("<green>Added " + ruleName + " rule for Guild " + guildId + ", Role " + roleId + "</green>"));
            } catch (SQLException e) {
                source.sendMessage(miniMessage.deserialize("<red>Failed to add rule: " + e.getMessage() + "</red>"));
            }
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            try {
                List<AccessRule> rules = databaseManager.getAccessRules(ruleType);
                if (rules.isEmpty()) {
                    source.sendMessage(miniMessage.deserialize("<yellow>No active " + ruleName + " rules found in database.</yellow>"));
                } else {
                    source.sendMessage(miniMessage.deserialize("<gradient:#ff4fd8:#7c4dff><bold>Active " + ruleName + " Rules:</bold></gradient>"));
                    for (AccessRule r : rules) {
                        source.sendMessage(miniMessage.deserialize("<gray>• Guild: </gray><white>" + r.guildId() + "</white> <gray>Role: </gray><yellow>" + r.roleId() + "</yellow>"));
                    }
                }
            } catch (SQLException e) {
                source.sendMessage(miniMessage.deserialize("<red>Database error: " + e.getMessage() + "</red>"));
            }
            return;
        }

        if (args.length >= 4) {
            String action = args[1].toLowerCase(Locale.ROOT);
            String guildId = args[2];
            String roleId = args[3];

            try {
                if ("add".equals(action)) {
                    databaseManager.addAccessRule(ruleType, guildId, roleId);
                    source.sendMessage(miniMessage.deserialize("<green>Added " + ruleName + " rule for Guild " + guildId + ", Role " + roleId + "</green>"));
                } else if ("remove".equals(action)) {
                    databaseManager.removeAccessRule(ruleType, guildId, roleId);
                    source.sendMessage(miniMessage.deserialize("<green>Removed " + ruleName + " rule for Guild " + guildId + ", Role " + roleId + "</green>"));
                } else {
                    source.sendMessage(miniMessage.deserialize("<yellow>Usage: /plog " + ruleName + " <add|remove|list> [guild] [role]</yellow>"));
                }
            } catch (SQLException e) {
                source.sendMessage(miniMessage.deserialize("<red>Database error: " + e.getMessage() + "</red>"));
            }
            return;
        }

        source.sendMessage(miniMessage.deserialize("<yellow>Usage: /plog " + ruleName + " <add|remove|list> [guild] [role]</yellow>"));
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(miniMessage.deserialize("""
            <gradient:#ff4fd8:#7c4dff><bold>RIZZLERS ProxyLogger Commands</bold></gradient>
            <gray>• </gray><white>/plog link</white> <gray>— Generate a one-time Discord link code</gray>
            <gray>• </gray><white>/plog unlink</white> <gray>— Disconnect your Discord account link</gray>
            <gray>• </gray><white>/plog status</white> <gray>— View database and Discord integration state</gray>
            <gray>• </gray><white>/plog reload</white> <gray>— Reload config.yml</gray>
            <gray>• </gray><white>/plog log <guild> <channel></white> <gray>— Configure Discord logging</gray>
            <gray>• </gray><white>/plog whitelist <guild> <role></white> <gray>— Configure access role</gray>
            <gray>• </gray><white>/plog blacklist <guild> <role></white> <gray>— Configure blacklist role</gray>
            """));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        CommandSource source = invocation.source();

        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            List<String> list = new ArrayList<>();
            if (source.hasPermission(PERM_ADMIN)) {
                list.addAll(List.of("status", "reload", "log", "whitelist", "blacklist", "link", "unlink"));
            } else {
                list.addAll(List.of("link", "unlink"));
            }
            return list.stream().filter(s -> s.startsWith(prefix)).toList();
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if ("whitelist".equals(sub) || "blacklist".equals(sub)) {
                return List.of("add", "remove", "list").stream().filter(s -> s.startsWith(prefix)).toList();
            }
        }

        return Collections.emptyList();
    }
}
