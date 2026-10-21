package online.rizzlers.proxylogger.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import online.rizzlers.proxylogger.config.PluginConfig;
import online.rizzlers.proxylogger.database.DatabaseManager;
import online.rizzlers.proxylogger.database.models.AuditEvent;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Discord bot integration using JDA for role retrieval, account linking slash commands,
 * and structured embed audit logging.
 */
public class DiscordService extends ListenerAdapter implements DiscordRoleProvider, AutoCloseable {
    private final PluginConfig config;
    private final DatabaseManager databaseManager;
    private final Logger logger;
    private JDA jda;
    private boolean ready = false;

    public DiscordService(PluginConfig config, DatabaseManager databaseManager, Logger logger) {
        this.config = config;
        this.databaseManager = databaseManager;
        this.logger = logger;
        initJda();
    }

    private void initJda() {
        String token = config.getBotToken();
        if (token == null || token.isBlank() || token.contains("REPLACE")) {
            if (logger != null) {
                logger.warning("Discord bot token is not configured (set RIZZLERS_DISCORD_TOKEN). Bot integration is inactive.");
            }
            return;
        }

        try {
            this.jda = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_MEMBERS))
                    .addEventListeners(this)
                    .build();

            // Register Discord slash commands
            this.jda.updateCommands().addCommands(
                    Commands.slash("link", "Link your Discord account to your Minecraft player on RIZZLERS")
                            .addOption(OptionType.STRING, "code", "The 6-character code generated in-game via /plog link", true),
                    Commands.slash("unlink", "Unlink your Discord account from the RIZZLERS network")
            ).queue(
                    success -> { if (logger != null) logger.info("Registered /link and /unlink Discord slash commands."); },
                    error -> { if (logger != null) logger.warning("Failed to register Discord commands: " + error.getMessage()); }
            );

            this.ready = true;
            if (logger != null) logger.info("Discord JDA bot initialized successfully.");
        } catch (Exception e) {
            if (logger != null) {
                logger.severe("Failed to initialize JDA Discord bot: " + e.getMessage());
            }
        }
    }

    @Override
    public Set<String> getMemberRoles(String guildId, String discordId) throws Exception {
        if (!isReady() || jda == null) {
            throw new IllegalStateException("Discord service is not connected.");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalStateException("Configured Discord guild (" + guildId + ") not found or bot lacks access.");
        }

        Member member;
        try {
            member = guild.retrieveMemberById(discordId).complete();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to retrieve member (" + discordId + ") in guild (" + guildId + ").", e);
        }

        if (member == null) {
            return Collections.emptySet();
        }

        return member.getRoles().stream()
                .map(ISnowflake::getId)
                .collect(Collectors.toSet());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if ("link".equalsIgnoreCase(event.getName())) {
            OptionMapping codeOpt = event.getOption("code");
            if (codeOpt == null || codeOpt.getAsString().isBlank()) {
                event.reply("Please provide the linking code generated via `/plog link` in-game.")
                        .setEphemeral(true).queue();
                return;
            }

            String rawCode = codeOpt.getAsString().trim().toUpperCase();
            String codeHash = sha256(rawCode);
            String discordId = event.getUser().getId();

            try {
                DatabaseManager.LinkResult result = databaseManager.consumeLinkCode(codeHash, discordId);
                if (result.success()) {
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTitle("✅ RIZZLERS Account Linked")
                            .setDescription("Your Discord account has been successfully linked to **"
                                    + result.linkedAccount().minecraftUsername() + "**!")
                            .addField("Account Type", result.linkedAccount().accountType().toUpperCase(), true)
                            .addField("UUID", result.linkedAccount().minecraftUuid().toString(), false)
                            .setColor(new Color(0x00FF88))
                            .setTimestamp(Instant.now());

                    event.replyEmbeds(eb.build()).setEphemeral(true).queue();

                    // Audit and log
                    AuditEvent audit = AuditEvent.create(
                            "LINK_SUCCESS",
                            result.linkedAccount().minecraftUuid(),
                            result.linkedAccount().minecraftUsername(),
                            discordId,
                            config.getGuildId(),
                            null,
                            "SUCCESS",
                            "Linked via Discord /link command"
                    );
                    databaseManager.recordAudit(audit);
                    sendLogEmbed(audit);
                } else {
                    event.reply("❌ " + result.message()).setEphemeral(true).queue();
                }
            } catch (Exception e) {
                if (logger != null) logger.severe("Error processing /link slash command: " + e.getMessage());
                event.reply("❌ An internal database error occurred while linking. Please try again later.")
                        .setEphemeral(true).queue();
            }
        } else if ("unlink".equalsIgnoreCase(event.getName())) {
            String discordId = event.getUser().getId();
            try {
                boolean unlinked = databaseManager.unlinkAccountByDiscordId(discordId);
                if (unlinked) {
                    event.reply("✅ Your Discord account has been unlinked from RIZZLERS.")
                            .setEphemeral(true).queue();
                    AuditEvent audit = AuditEvent.create(
                            "UNLINK_SUCCESS",
                            null,
                            null,
                            discordId,
                            config.getGuildId(),
                            null,
                            "SUCCESS",
                            "Unlinked via Discord /unlink command"
                    );
                    databaseManager.recordAudit(audit);
                    sendLogEmbed(audit);
                } else {
                    event.reply("ℹ️ No active Minecraft link was found for your Discord account.")
                            .setEphemeral(true).queue();
                }
            } catch (Exception e) {
                event.reply("❌ An internal database error occurred while unlinking.").setEphemeral(true).queue();
            }
        }
    }

    public void sendLogEmbed(AuditEvent audit) {
        if (!isReady() || jda == null) return;
        String logChannelId = config.getLogChannelId();
        if (logChannelId == null || logChannelId.isBlank() || logChannelId.contains("REPLACE")) return;

        try {
            TextChannel channel = jda.getTextChannelById(logChannelId);
            if (channel == null) return;

            Color embedColor;
            String result = audit.result() != null ? audit.result().toUpperCase() : "INFO";
            if (result.contains("ALLOW") || result.contains("SUCCESS")) {
                embedColor = new Color(0x00FF88); // Green
            } else if (result.contains("BLACKLIST") || result.contains("DENY") || result.contains("MISSING")) {
                embedColor = new Color(0xFF3333); // Red
            } else if (result.contains("ADMIN") || result.contains("RELOAD")) {
                embedColor = new Color(0xFFAA00); // Gold
            } else {
                embedColor = new Color(0x3399FF); // Blue
            }

            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("🛡️ RIZZLERS Proxy Audit: " + audit.eventType())
                    .setColor(embedColor)
                    .setTimestamp(audit.createdAt() != null ? audit.createdAt() : Instant.now());

            if (audit.minecraftUsername() != null) {
                eb.addField("Player", audit.minecraftUsername() + (audit.minecraftUuid() != null ? " (" + audit.minecraftUuid() + ")" : ""), false);
            }
            if (audit.discordId() != null) {
                eb.addField("Discord", "<@" + audit.discordId() + "> (`" + audit.discordId() + "`)", false);
            }
            eb.addField("Result", audit.result() != null ? audit.result() : "UNKNOWN", true);
            if (audit.reason() != null) {
                eb.addField("Details", audit.reason(), false);
            }
            eb.setFooter("RIZZLERS ProxyLogger • Velocity 4.3.0");

            channel.sendMessageEmbeds(eb.build()).queue(
                    null,
                    err -> { if (logger != null) logger.warning("Could not dispatch Discord log embed: " + err.getMessage()); }
            );
        } catch (Exception e) {
            if (logger != null) logger.warning("Failed to send audit log embed: " + e.getMessage());
        }
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }

    @Override
    public boolean isReady() {
        return ready && jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    public JDA getJda() {
        return jda;
    }

    @Override
    public void close() {
        if (jda != null) {
            try {
                jda.shutdown();
                if (!jda.awaitShutdown(5, TimeUnit.SECONDS)) {
                    jda.shutdownNow();
                }
            } catch (InterruptedException e) {
                jda.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
