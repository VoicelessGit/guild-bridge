package com.guildbridge.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Builds Discord webhook JSON for a BridgeEvent. */
public final class WebhookPayload {
    private static final int DISCORD_CONTENT_LIMIT = 2000;

    private WebhookPayload() {}

    public static String forEvent(BridgeEvent event) {
        return forEvent(event, null);
    }

    /** @param filter censors player-written text before it reaches Discord; null means no filter */
    public static String forEvent(BridgeEvent event, ProfanityFilter filter) {
        JsonObject json = new JsonObject();
        switch (event) {
            case BridgeEvent.Chat c -> {
                String prefix = c.channel() == BridgeEvent.Channel.OFFICER ? "[Officer] " : "";
                String suffix = c.guildRank() != null ? " [" + c.guildRank() + "]" : "";
                json.addProperty("username", prefix + c.name() + suffix);
                json.addProperty("avatar_url", "https://mc-heads.net/avatar/" + c.name());
                json.addProperty("content", truncate(escapeMarkdown(censor(c.message(), filter))));
            }
            case BridgeEvent.Status s -> {
                json.addProperty("username", "Guild");
                json.addProperty("content",
                        escapeMarkdown(s.name()) + (s.joined() ? " joined." : " left."));
            }
            case BridgeEvent.Announcement a -> {
                json.addProperty("username", "Guild Announcement");
                // Announcements can quote player text (MOTD, guild names), so filter them too.
                String body = escapeMarkdown(censor(a.text(), filter));
                json.addProperty("content", truncate("**" + body.replace("\n", "**\n**") + "**"));
            }
        }
        // Never let game chat ping @everyone, @here, roles, or users.
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        json.add("allowed_mentions", allowed);
        return json.toString();
    }

    private static String censor(String s, ProfanityFilter filter) {
        return filter == null ? s : filter.censor(s);
    }

    /** Escapes Discord markdown so names like _Hero_ do not turn into italics. */
    static String escapeMarkdown(String s) {
        return s.replaceAll("([\\\\*_~`|>])", "\\\\$1");
    }

    private static String truncate(String s) {
        return s.length() <= DISCORD_CONTENT_LIMIT ? s : s.substring(0, DISCORD_CONTENT_LIMIT - 1) + "…";
    }
}
