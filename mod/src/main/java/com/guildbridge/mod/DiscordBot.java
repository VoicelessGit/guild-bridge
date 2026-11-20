package com.guildbridge.mod;

import com.guildbridge.core.AdminCommand;
import com.guildbridge.core.AdminList;
import com.guildbridge.core.OutgoingSanitizer;
import com.guildbridge.core.ProfanityFilter;
import java.util.EnumSet;
import java.util.function.Supplier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.messages.MessageRequest;

/**
 * The Discord side: posts game events as embeds, relays channel messages into guild chat,
 * and uses its online status as a health light.
 *
 * <p>Status meanings: Online = in Hypixel limbo and relaying. Idle = on Hypixel but NOT in limbo.
 * Do Not Disturb = game running but not on Hypixel. Offline = game closed or crashed.
 */
final class DiscordBot extends ListenerAdapter {
    private final JDA jda;
    private final long channelId;
    private final GameSender game;
    private final ProfanityFilter filter;
    private final AdminList admins;
    private final Supplier<String> status;
    private final String prefix;

    DiscordBot(String token, String channelId, GameSender game, ProfanityFilter filter,
               AdminList admins, Supplier<String> status, String prefix) {
        this.channelId = Long.parseLong(channelId.strip());
        this.game = game;
        this.filter = filter;
        this.admins = admins;
        this.status = status;
        this.prefix = prefix == null ? "" : prefix;

        // Nothing the bot sends may ping @everyone, roles or users.
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));

        // build() connects in the background, so this never blocks the game.
        this.jda = JDABuilder.createLight(token,
                        GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .setStatus(OnlineStatus.DO_NOT_DISTURB)
                .setActivity(Activity.customStatus("Not on Hypixel"))
                .addEventListeners(this)
                .build();
    }

    /** Posts an embed built by core's DiscordEmbed. JDA handles Discord's rate limits. */
    void post(String embedJson) {
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            GuildBridgeClient.LOG.warn("Bridge channel not found yet (still connecting, or wrong channelId)");
            return;
        }
        channel.sendMessageEmbeds(EmbedBuilder.fromData(DataObject.fromJson(embedJson)).build())
                .queue(null, err -> GuildBridgeClient.LOG.warn("Discord send failed: {}", err.toString()));
    }

    void setState(LimboTracker.State state) {
        switch (state) {
            case IN_LIMBO -> jda.getPresence().setPresence(
                    OnlineStatus.ONLINE, Activity.customStatus("In limbo, bridging guild chat"));
            case ON_HYPIXEL_NOT_LIMBO -> jda.getPresence().setPresence(
                    OnlineStatus.IDLE, Activity.customStatus("On Hypixel, not in limbo"));
            case NOT_ON_HYPIXEL -> jda.getPresence().setPresence(
                    OnlineStatus.DO_NOT_DISTURB, Activity.customStatus("Not on Hypixel"));
        }
    }

    void shutdown() {
        jda.shutdown();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getChannel().getIdLong() != channelId) return;
        // Ignores itself (no echo loop), other bots, and webhooks.
        if (event.getAuthor().isBot() || event.isWebhookMessage()) return;

        Message msg = event.getMessage();
        if (AdminCommand.looksLikeCommand(msg.getContentRaw())) {
            handleAdmin(event, msg);
            return; // admin commands are never relayed to guild chat
        }
        String text = OutgoingSanitizer.discordText(
                msg.getContentDisplay(), msg.getAttachments().size(), msg.getStickers().size());
        String name = event.getMember() != null
                ? event.getMember().getEffectiveName()
                : event.getAuthor().getEffectiveName();

        String cmd = OutgoingSanitizer.toGuildCommand(name, text, filter, prefix);
        game.queue(cmd, () -> msg.addReaction(Emoji.fromUnicode("❌")).queue(null, err -> {}));
    }

    private void handleAdmin(MessageReceivedEvent event, Message msg) {
        if (!admins.isAdmin(event.getAuthor().getId())) {
            msg.addReaction(Emoji.fromUnicode("⛔")).queue(null, err -> {});
            return;
        }
        var parsed = AdminCommand.parse(msg.getContentRaw());
        if (parsed.isEmpty()) {
            msg.reply("Usage: `!mc <command>` (for example `!mc /lobby`) or `!status`").queue(null, err -> {});
            return;
        }
        switch (parsed.get()) {
            case AdminCommand.Status s -> msg.reply(status.get()).queue(null, err -> {});
            case AdminCommand.Run r -> {
                GuildBridgeClient.LOG.info("Admin {} ran /{}", event.getAuthor().getName(), r.command());
                game.queueUtility(r.command());
                msg.addReaction(Emoji.fromUnicode("✅")).queue(null, err -> {});
            }
        }
    }
}
