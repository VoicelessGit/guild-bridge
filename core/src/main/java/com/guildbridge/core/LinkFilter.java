package com.guildbridge.core;

import java.util.regex.Pattern;

/**
 * Replaces links with "[blocked link]". Always on, in both directions.
 *
 * <p>Catches http(s):// and www. links, bare domains on common TLDs (discord.gg/abc, bit.ly/x,
 * hypixel.net), and IPv4 addresses with optional port. Some very short country TLDs that are
 * also common English words (in, it, no, at...) are left out to avoid false positives like
 * "log.in" in normal chat.
 */
public final class LinkFilter {
    public static final String REPLACEMENT = "[blocked link]";

    private static final String TLDS = String.join("|",
            "com", "net", "org", "gg", "io", "co", "xyz", "me", "tv", "ly", "be", "uk", "ru", "info",
            "link", "app", "dev", "site", "online", "store", "shop", "club", "fun", "top", "live",
            "pro", "biz", "cc", "to", "ws", "gl", "sh", "lol", "ai", "ca", "de", "fr", "nl", "eu",
            "au", "jp", "cn", "br", "kr", "tk", "ml", "ga", "cf", "gq", "pw", "su", "one", "win",
            "vip", "art", "blog", "page", "wiki", "space", "website", "tech", "today", "world",
            "zone", "gd", "ink", "rip", "wtf", "ovh", "fm", "gs", "id", "mx", "nz", "pl");

    private static final Pattern LINK = Pattern.compile(
            "(?:https?://|www\\.)\\S+"
                    + "|(?<![\\w.@-])(?:[a-z0-9-]+\\.)+(?:" + TLDS + ")(?![\\w-])(?:[:/?#]\\S*)?"
                    + "|(?<![\\w.])\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?![\\w.])",
            Pattern.CASE_INSENSITIVE);

    private LinkFilter() {}

    public static String block(String text) {
        if (text == null || text.isEmpty()) return text;
        return LINK.matcher(text).replaceAll(REPLACEMENT);
    }
}
