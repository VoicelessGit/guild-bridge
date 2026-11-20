package com.guildbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Builds Discord webhook JSON for a BridgeEvent. Everything is sent as an embed.
 * Webhook embeds do not need the bot's Embed Links permission.
 */
public final class WebhookPayload {
    /** Discord's limit for an embed description. */
    private static final int DESCRIPTION_LIMIT = 4096;

    // Embed side-bar colors, roughly matching Hypixel's chat colors.
    static final int GUILD_GREEN = 0x00AA00;
    static final int OFFICER_AQUA = 0x00AAAA;
    static final int STATUS_GRAY = 0xAAAAAA;
    static final int ANNOUNCEMENT_GOLD = 0xFFAA00;

    private WebhookPayload() {}

    public static String forEvent(BridgeEvent event) {
        return forEvent(event, null);
    }

    /** @param filter censors player-written text before it reaches Discord; null means no filter */
    public static String forEvent(BridgeEvent event, ProfanityFilter filter) {
        JsonObject json = new JsonObject();
        JsonObject embed = new JsonObject();
        switch (event) {
            case BridgeEvent.Chat c -> {
                boolean officer = c.channel() == BridgeEvent.Channel.OFFICER;
                String suffix = c.guildRank() != null ? " [" + c.guildRank() + "]" : "";
                // Webhook name and avatar make each message look like it came from the player.
                json.addProperty("username", (officer ? "[Officer] " : "") + c.name() + suffix);
                json.addProperty("avatar_url", "https://mc-heads.net/avatar/" + c.name());
                embed.addProperty("description", describe(c.message(), filter));
                embed.addProperty("color", officer ? OFFICER_AQUA : GUILD_GREEN);
            }
            case BridgeEvent.Status s -> {
                json.addProperty("username", "Guild");
                embed.addProperty("description",
                        escapeMarkdown(s.name()) + (s.joined() ? " joined." : " left."));
                embed.addProperty("color", STATUS_GRAY);
            }
            case BridgeEvent.Announcement a -> {
                json.addProperty("username", "Guild");
                embed.addProperty("title", "Guild Announcement");
                // Announcements can quote player text (MOTD, guild names), so filter them too.
                embed.addProperty("description", describe(a.text(), filter));
                embed.addProperty("color", ANNOUNCEMENT_GOLD);
                if (a.joinedMember() != null) {
                    JsonObject thumb = new JsonObject();
                    thumb.addProperty("url", "https://mc-heads.net/avatar/" + a.joinedMember());
                    embed.add("thumbnail", thumb);
                }
            }
        }
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        json.add("embeds", embeds);

        // Never let game chat ping @everyone, @here, roles, or users.
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        json.add("allowed_mentions", allowed);
        return json.toString();
    }

    private static String describe(String text, ProfanityFilter filter) {
        String s = escapeMarkdown(filter == null ? text : filter.censor(text));
        return s.length() <= DESCRIPTION_LIMIT ? s : s.substring(0, DESCRIPTION_LIMIT - 1) + "…";
    }

    /** Escapes Discord markdown so names like _Hero_ do not turn into italics. */
    static String escapeMarkdown(String s) {
        return s.replaceAll("([\\\\*_~`|>])", "\\\\$1");
    }
}
