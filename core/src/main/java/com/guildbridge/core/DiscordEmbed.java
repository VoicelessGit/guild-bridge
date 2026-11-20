package com.guildbridge.core;

import com.google.gson.JsonObject;

/**
 * Builds the Discord embed (as JSON) that the bot posts for a BridgeEvent.
 * The mod turns it into a JDA embed with {@code EmbedBuilder.fromData(DataObject.fromJson(json))}.
 *
 * <p>Every player-written string goes through LinkFilter, then the profanity filter, then
 * markdown escaping. Mentions are disabled on the JDA side, so nothing here can ping anyone.
 */
public final class DiscordEmbed {
    /** Discord's limit for an embed description. */
    private static final int DESCRIPTION_LIMIT = 4096;
    private static final String HEAD_URL = "https://mc-heads.net/avatar/";

    // Embed side-bar colors, roughly matching Hypixel's chat colors.
    static final int GUILD_GREEN = 0x00AA00;
    static final int OFFICER_AQUA = 0x00AAAA;
    static final int STATUS_GRAY = 0xAAAAAA;
    static final int ANNOUNCEMENT_GOLD = 0xFFAA00;

    private DiscordEmbed() {}

    public static String forEvent(BridgeEvent event) {
        return forEvent(event, null);
    }

    /** @param filter censors player text; null means no profanity filter (links are always blocked) */
    public static String forEvent(BridgeEvent event, ProfanityFilter filter) {
        JsonObject embed = new JsonObject();
        switch (event) {
            case BridgeEvent.Chat c -> {
                boolean officer = c.channel() == BridgeEvent.Channel.OFFICER;
                String suffix = c.guildRank() != null ? " [" + c.guildRank() + "]" : "";
                JsonObject author = new JsonObject();
                author.addProperty("name", (officer ? "[Officer] " : "") + c.name() + suffix);
                author.addProperty("icon_url", HEAD_URL + c.name());
                embed.add("author", author);
                embed.addProperty("description", describe(c.message(), filter));
                embed.addProperty("color", officer ? OFFICER_AQUA : GUILD_GREEN);
            }
            case BridgeEvent.Status s -> {
                embed.addProperty("description",
                        escapeMarkdown(s.name()) + (s.joined() ? " joined." : " left."));
                embed.addProperty("color", STATUS_GRAY);
            }
            case BridgeEvent.Announcement a -> {
                embed.addProperty("title", "Guild Announcement");
                // Announcements can quote player text (MOTD, guild names), so filter them too.
                embed.addProperty("description", describe(a.text(), filter));
                embed.addProperty("color", ANNOUNCEMENT_GOLD);
                if (a.joinedMember() != null) {
                    JsonObject thumb = new JsonObject();
                    thumb.addProperty("url", HEAD_URL + a.joinedMember());
                    embed.add("thumbnail", thumb);
                }
            }
        }
        return embed.toString();
    }

    private static String describe(String text, ProfanityFilter filter) {
        String linkFree = LinkFilter.block(text);
        String s = escapeMarkdown(filter == null ? linkFree : filter.censor(linkFree));
        return s.length() <= DESCRIPTION_LIMIT ? s : s.substring(0, DESCRIPTION_LIMIT - 1) + "…";
    }

    /** Escapes Discord markdown so names like _Hero_ do not turn into italics. */
    static String escapeMarkdown(String s) {
        return s.replaceAll("([\\\\*_~`|>])", "\\\\$1");
    }
}
