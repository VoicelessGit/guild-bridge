package com.guildbridge.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sorts a Minecraft disconnect reason into a kind, so the supervisor can react sensibly:
 * rejoin, wait, stop rejoining, or ping someone.
 *
 * <p>Checked in order: banned, duplicate login, login failed, network, kicked, else unknown.
 * Patterns are deliberately broad; unknown reasons are always reported word for word so new
 * ones can be added here with a test.
 */
public enum DisconnectKind {
    BANNED("Banned"),
    DUPLICATE_LOGIN("Logged in elsewhere"),
    LOGIN_FAILED("Login failed"),
    NETWORK("Network problem"),
    KICKED("Kicked"),
    UNKNOWN("Disconnected");

    public final String label;

    DisconnectKind(String label) {
        this.label = label;
    }

    private static final Pattern BANNED_P = Pattern.compile(
            "banned|blacklisted|ban id|appeal at|you are suspended|account (?:has been )?suspended");
    private static final Pattern DUPLICATE_P = Pattern.compile(
            "logged in from another location|you logged in from another|duplicate login|already (?:logged|connected)");
    private static final Pattern LOGIN_P = Pattern.compile(
            "invalid session|authentication servers|failed to verify username|failed to log in|not authenticated"
                    + "|multiplayer is disabled|session (?:has )?expired|invalid access token|chat profile|profile key");
    private static final Pattern NETWORK_P = Pattern.compile(
            "timed out|timeout|connection reset|connection refused|network protocol error|end of stream"
                    + "|connection lost|lost connection|connection closed|unknown host|no further information"
                    + "|internal exception|broken pipe|unreachable|could not connect|couldn't connect|failed to connect"
                    + "|io exception|ioexception|socket");
    private static final Pattern KICKED_P = Pattern.compile(
            "kicked|server (?:is )?restarting|restart|proxy|server closed|please reconnect|out of date"
                    + "|outdated|limbo|afk|you have been sent|transfer|whitelist|full");

    public static DisconnectKind classify(String reason) {
        if (reason == null || reason.isBlank()) return UNKNOWN;
        String r = TextUtil.stripFormatting(reason).toLowerCase(Locale.ROOT);
        if (BANNED_P.matcher(r).find()) return BANNED;
        if (DUPLICATE_P.matcher(r).find()) return DUPLICATE_LOGIN;
        if (LOGIN_P.matcher(r).find()) return LOGIN_FAILED;
        if (NETWORK_P.matcher(r).find()) return NETWORK;
        if (KICKED_P.matcher(r).find()) return KICKED;
        return UNKNOWN;
    }
}
