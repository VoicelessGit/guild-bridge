package com.guildbridge.supervisor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.messages.MessageRequest;

/**
 * The only Discord bot. Thin wrapper around JDA: posting, alerts with pings, presence, and
 * handing channel messages to the supervisor. Every call catches its own errors, so a missing
 * permission is logged (and alerted by DM) instead of breaking anything.
 */
final class DiscordBot extends ListenerAdapter {
    private final JDA jda;
    private final long channelId;
    private final Consumer<MessageReceivedEvent> onMessage;
    private volatile Runnable onPermissionProblem = () -> {};
    /** Open paged messages (message id to its pages), forgotten after HELP_TIMEOUT_SECONDS. */
    private final Map<Long, List<MessageEmbed>> openHelp = new ConcurrentHashMap<>();
    private final Map<Long, List<String>> openHelpTitles = new ConcurrentHashMap<>();
    private final ScheduledExecutorService helpTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "help-timeout");
        t.setDaemon(true);
        return t;
    });
    private static final int HELP_TIMEOUT_SECONDS = 30;

    DiscordBot(String token, String channelId, Consumer<MessageReceivedEvent> onMessage) throws InterruptedException {
        this.channelId = Long.parseLong(channelId.strip());
        this.onMessage = onMessage;
        // Nothing may ping anyone, except alerts, which whitelist specific users and roles.
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
        this.jda = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .setStatus(OnlineStatus.IDLE)
                .setActivity(Activity.customStatus("Starting up"))
                .addEventListeners(this)
                .build();
        jda.awaitReady();
    }

    void setOnPermissionProblem(Runnable r) {
        onPermissionProblem = r;
    }

    void shutdown() {
        helpTimer.shutdownNow();
        jda.shutdown();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        try {
            if (event.getChannel().getIdLong() != channelId) return;
            if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
            onMessage.accept(event);
        } catch (RuntimeException e) {
            Main.LOG.warn("Error handling Discord message: {}", e.toString());
        }
    }

    /**
     * Replies with page 1 and a button per page. After HELP_TIMEOUT_SECONDS the buttons are removed
     * and the pages forgotten, so nothing piles up in memory.
     */
    void replyHelp(Message msg, List<String> titles, List<MessageEmbed> pages) {
        try {
            msg.replyEmbeds(pages.get(0)).setComponents(helpRow(titles, 0)).queue(sent -> {
                openHelp.put(sent.getIdLong(), pages);
                openHelpTitles.put(sent.getIdLong(), titles);
                helpTimer.schedule(() -> {
                    openHelp.remove(sent.getIdLong());
                    openHelpTitles.remove(sent.getIdLong());
                    try {
                        sent.editMessageComponents(new ArrayList<MessageTopLevelComponent>()).queue(null, err -> {});
                    } catch (RuntimeException ignored) {
                    }
                }, HELP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }, err -> Main.LOG.warn("Could not send the command list: {}", err.toString()));
        } catch (RuntimeException e) {
            Main.LOG.warn("Could not send the command list (missing Embed Links?): {}", e.toString());
        }
    }

    private static ActionRow helpRow(List<String> titles, int current) {
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            Button b = i == current ? Button.primary("gbhelp:" + i, titles.get(i)) : Button.secondary("gbhelp:" + i, titles.get(i));
            buttons.add(b);
        }
        return ActionRow.of(buttons);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        try {
            String id = event.getComponentId();
            if (!id.startsWith("gbhelp:")) return;
            List<MessageEmbed> pages = openHelp.get(event.getMessageIdLong());
            List<String> titles = openHelpTitles.get(event.getMessageIdLong());
            if (pages == null || titles == null) {
                event.reply("This list timed out. Send `!commands` again.").setEphemeral(true).queue(null, err -> {});
                return;
            }
            int page = Integer.parseInt(id.substring("gbhelp:".length()));
            if (page < 0 || page >= pages.size()) return;
            event.editMessageEmbeds(pages.get(page)).setComponents(helpRow(titles, page)).queue(null, err -> {});
        } catch (RuntimeException e) {
            Main.LOG.warn("Error handling a button: {}", e.toString());
        }
    }

    void setPresence(OnlineStatus status, String text) {
        try {
            jda.getPresence().setPresence(status, Activity.customStatus(text));
        } catch (RuntimeException e) {
            Main.LOG.warn("Could not set bot status: {}", e.toString());
        }
    }

    /** Posts an embed built from core JSON (DiscordEmbed). */
    void post(String embedJson) {
        send(EmbedBuilder.fromData(DataObject.fromJson(embedJson)).build());
    }

    void send(MessageEmbed embed) {
        try {
            TextChannel ch = channel();
            if (ch == null) return;
            ch.sendMessageEmbeds(embed).queue(null, err -> {
                Main.LOG.warn("Discord send failed: {}", err.toString());
                onPermissionProblem.run();
            });
        } catch (RuntimeException e) {
            Main.LOG.warn("Discord send failed: {}", e.toString());
            onPermissionProblem.run();
        }
    }

    /**
     * Posts an alert that pings the given people. Entries are user IDs or "role:<id>".
     * If the channel cannot be used (permissions), each listed user gets a DM instead.
     */
    void alert(String text, List<String> ping) {
        List<String> users = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        for (String p : ping) {
            String s = p.strip();
            if (s.startsWith("role:") && s.substring(5).matches("\\d{15,25}")) roles.add(s.substring(5));
            else if (s.matches("\\d{15,25}")) users.add(s);
        }
        StringBuilder mentions = new StringBuilder();
        users.forEach(u -> mentions.append("<@").append(u).append("> "));
        roles.forEach(r -> mentions.append("<@&").append(r).append("> "));
        MessageEmbed embed = new EmbedBuilder()
                .setTitle("Bridge alert")
                .setDescription(text.length() > 4000 ? text.substring(0, 4000) + "…" : text)
                .setColor(0xFF5555)
                .build();
        try {
            TextChannel ch = channel();
            if (ch == null) throw new IllegalStateException("bridge channel not found");
            var action = ch.sendMessageEmbeds(embed);
            if (!mentions.isEmpty()) action = action.setContent(mentions.toString().strip());
            action.mentionUsers(users.toArray(String[]::new))
                    .mentionRoles(roles.toArray(String[]::new))
                    .queue(null, err -> dmAll(users, text));
        } catch (RuntimeException e) {
            Main.LOG.warn("Could not post alert in channel ({}), sending DMs", e.toString());
            dmAll(users, text);
        }
    }

    private void dmAll(List<String> users, String text) {
        for (String id : users) {
            try {
                jda.retrieveUserById(id)
                        .flatMap(User::openPrivateChannel)
                        .flatMap(dm -> dm.sendMessage("**Guild Bridge alert**\n" + text))
                        .queue(null, err -> Main.LOG.warn("Could not DM {}: {}", id, err.toString()));
            } catch (RuntimeException e) {
                Main.LOG.warn("Could not DM {}: {}", id, e.toString());
            }
        }
    }

    private TextChannel channel() {
        TextChannel ch = jda.getTextChannelById(channelId);
        if (ch == null) Main.LOG.warn("Bridge channel {} not found (wrong channelId, or the bot cannot see it)", channelId);
        return ch;
    }

    // --- reply helpers, all safe to call from anywhere ---

    static void reply(Message msg, String text) {
        try {
            msg.reply(text.length() > 1900 ? text.substring(0, 1900) + "…" : text).queue(null, err -> {});
        } catch (RuntimeException e) {
            Main.LOG.warn("Could not reply in Discord: {}", e.toString());
        }
    }

    static void replyEmbed(Message msg, MessageEmbed embed) {
        try {
            msg.replyEmbeds(embed).queue(null, err -> {});
        } catch (RuntimeException e) {
            Main.LOG.warn("Could not reply in Discord (missing Embed Links?): {}", e.toString());
        }
    }

    static void replyEmbedJson(Message msg, String embedJson) {
        replyEmbed(msg, EmbedBuilder.fromData(DataObject.fromJson(embedJson)).build());
    }

    static void react(Message msg, String emoji) {
        try {
            msg.addReaction(Emoji.fromUnicode(emoji)).queue(null, err -> {});
        } catch (RuntimeException e) {
            Main.LOG.warn("Could not react (missing Add Reactions?): {}", e.toString());
        }
    }
}
