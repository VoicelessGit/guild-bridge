package com.guildbridge.mod;

import com.guildbridge.core.AdminList;
import com.guildbridge.core.BotCommand;
import com.guildbridge.core.DiscordEmbed;
import com.guildbridge.core.OutgoingSanitizer;
import com.guildbridge.core.ProfanityFilter;
import java.util.EnumSet;
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
 * answers bot commands, and uses its online status as a health light.
 *
 * <p>Status meanings: Online = in Hypixel limbo and relaying. Idle = on Hypixel but NOT in limbo.
 * Do Not Disturb = game running but not on Hypixel. Offline = game closed or crashed.
 *
 * <p>Command tiers (see core BotCommand): members can use !commands, !g online, !g top [1-10];
 * admins from guildbridge-admins.txt can also use !say, !mc, !status, !uptime, !restart.
 */
final class DiscordBot extends ListenerAdapter {
    /** What the bot needs from the rest of the mod. */
    interface Control {
        String status();
        String uptime();
        void restart();
    }

    /** Minimum gap between game commands from members (admins are exempt). */
    private static final long MEMBER_COOLDOWN_MILLIS = 10_000;

    private final JDA jda;
    private final long channelId;
    private final GameSender game;
    private final ProfanityFilter filter;
    private final AdminList admins;
    private final String prefix;
    private final CommandCapture capture;
    private final Control control;
    private volatile long lastMemberCommandAt;

    DiscordBot(String token, String channelId, GameSender game, ProfanityFilter filter,
               AdminList admins, String prefix, CommandCapture capture, Control control) {
        this.channelId = Long.parseLong(channelId.strip());
        this.game = game;
        this.filter = filter;
        this.admins = admins;
        this.prefix = prefix == null ? "" : prefix;
        this.capture = capture;
        this.control = control;

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
        if (BotCommand.looksLikeCommand(msg.getContentRaw())) {
            handleCommand(event, msg);
            return; // commands are never relayed to guild chat
        }

        String text = OutgoingSanitizer.discordText(
                msg.getContentDisplay(), msg.getAttachments().size(), msg.getStickers().size());
        String name = event.getMember() != null
                ? event.getMember().getEffectiveName()
                : event.getAuthor().getEffectiveName();

        String cmd = OutgoingSanitizer.toGuildCommand(name, text, filter, prefix);
        game.queue(cmd, () -> react(msg, "❌"));
    }

    private void handleCommand(MessageReceivedEvent event, Message msg) {
        var parsed = BotCommand.parse(msg.getContentRaw());
        if (parsed.isEmpty()) return;
        BotCommand.Parsed p = parsed.get();
        boolean admin = admins.isAdmin(event.getAuthor().getId());

        if (BotCommand.requiresAdmin(p) && !admin) {
            react(msg, "⛔");
            return;
        }
        if (admin && BotCommand.requiresAdmin(p)) {
            GuildBridgeClient.LOG.info("Admin {} used {}", event.getAuthor().getName(), msg.getContentRaw());
        }

        switch (p) {
            case BotCommand.Invalid i -> reply(msg, i.message());
            case BotCommand.Commands c -> msg.replyEmbeds(new EmbedBuilder()
                    .setTitle("Bridge commands")
                    .setDescription(BotCommand.helpText())
                    .setColor(0x00AA00)
                    .build()).queue(null, err -> {});
            case BotCommand.Status s -> reply(msg, control.status());
            case BotCommand.Uptime u -> reply(msg, control.uptime());
            case BotCommand.Restart r -> {
                reply(msg, "Restarting the game now. If it was started with the launch script, "
                        + "it comes back in about 30 seconds and rejoins Hypixel.");
                control.restart();
            }
            case BotCommand.Say s -> {
                // Admin text as the bridge itself: no name, no prefix, still link- and profanity-filtered.
                String cmd = OutgoingSanitizer.toGuildCommand("", s.text(), filter, "");
                if (cmd == null) {
                    reply(msg, "Nothing left to send after filtering.");
                    return;
                }
                game.queue(cmd, () -> react(msg, "❌"));
                react(msg, "✅");
            }
            case BotCommand.GuildOnline g -> runAndReply(msg, p, "Guild online", admin);
            case BotCommand.GuildTop t -> runAndReply(msg, p,
                    t.day() == null ? "Guild top (today)" : "Guild top (" + t.day() + " days ago)", admin);
            case BotCommand.Run r -> runAndReply(msg, p, "/" + r.command(), admin);
        }
    }

    /** Runs a game command, captures what the game says back, and replies with it. */
    private void runAndReply(Message msg, BotCommand.Parsed p, String title, boolean admin) {
        long now = System.currentTimeMillis();
        if (!admin && now - lastMemberCommandAt < MEMBER_COOLDOWN_MILLIS) {
            react(msg, "⏳"); // cooldown, try again in a few seconds
            return;
        }
        if (!capture.reserve()) {
            react(msg, "⏳"); // another command is still running
            return;
        }
        if (!admin) lastMemberCommandAt = now;

        react(msg, "👀");
        game.queueUtility(BotCommand.gameCommand(p),
                () -> capture.begin(text -> msg.replyEmbeds(
                                EmbedBuilder.fromData(DataObject.fromJson(DiscordEmbed.response(title, text, filter)))
                                        .build())
                        .queue(null, err -> {})),
                () -> {
                    capture.cancel();
                    react(msg, "❌");
                });
    }

    private static void reply(Message msg, String text) {
        msg.reply(text).queue(null, err -> {});
    }

    private static void react(Message msg, String emoji) {
        msg.addReaction(Emoji.fromUnicode(emoji)).queue(null, err -> {});
    }
}
