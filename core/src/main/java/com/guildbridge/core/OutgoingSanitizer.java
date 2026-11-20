package com.guildbridge.core;

/** Turns a Discord message into a safe /gc command for Minecraft. */
public final class OutgoingSanitizer {
    /** Minecraft rejects chat and commands longer than 256 characters. */
    static final int CHAT_LIMIT = 256;
    private static final String COMMAND = "gc ";

    private OutgoingSanitizer() {}

    /**
     * Returns the command text without the leading slash, for example {@code gc Justin: hi}.
     * Returns null if nothing is left to send.
     */
    public static String toGuildCommand(String discordAuthor, String text) {
        String author = clean(LinkFilter.block(discordAuthor));
        String body = clean(LinkFilter.block(text));
        if (body.isEmpty()) return null;

        // The leading slash counts toward the limit when the client sends it.
        String head = COMMAND + (author.isEmpty() ? "" : author + ": ");
        int room = CHAT_LIMIT - 1 - head.length();
        if (room <= 0) return null;
        if (body.length() > room) body = body.substring(0, room - 3) + "...";
        return head + body;
    }

    /** Same as above, with Discord name and text run through the filter first. */
    public static String toGuildCommand(String discordAuthor, String text, ProfanityFilter filter) {
        if (filter == null) return toGuildCommand(discordAuthor, text);
        return toGuildCommand(filter.censor(discordAuthor), filter.censor(text));
    }

    /** Builds the welcome command from a template such as "Welcome to the guild, {name}!". */
    public static String welcomeCommand(String template, String name) {
        return toGuildCommand("", template.replace("{name}", name));
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('§', ' ')
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\p{Cntrl}", "")
                .replaceAll(" {2,}", " ")
                .strip();
    }
}
