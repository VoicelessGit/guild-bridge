package com.guildbridge.core;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Discord message's raw text into what people see, using display names for pings.
 *
 * <p>Discord's own rendering for bots falls back to the account username when the member list
 * is not cached (the bridge runs without it), so pings showed "@jdoe123" while the same person
 * typing showed their display name. Discord does send each mentioned member with the message,
 * so the supervisor passes their nicknames or display names in here.
 */
public final class MentionText {
    private static final Pattern TOKEN = Pattern.compile(
            "<@!?(?<user>\\d+)>|<@&(?<role>\\d+)>|<#(?<channel>\\d+)>|<a?:(?<emoji>\\w+):\\d+>");

    private MentionText() {}

    /**
     * @param users    user ID to the name to show (server nickname, else display name, else username)
     * @param roles    role ID to role name
     * @param channels channel ID to channel name
     */
    public static String render(String raw, Map<String, String> users, Map<String, String> roles,
            Map<String, String> channels) {
        if (raw == null || raw.isEmpty()) return raw;
        Matcher m = TOKEN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String out;
            if (m.group("user") != null) {
                out = "@" + users.getOrDefault(m.group("user"), "unknown-user");
            } else if (m.group("role") != null) {
                out = "@" + roles.getOrDefault(m.group("role"), "unknown-role");
            } else if (m.group("channel") != null) {
                out = "#" + channels.getOrDefault(m.group("channel"), "unknown-channel");
            } else {
                out = ":" + m.group("emoji") + ":";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
