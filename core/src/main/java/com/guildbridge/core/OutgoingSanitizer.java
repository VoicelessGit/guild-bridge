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
        return toGuildCommand(discordAuthor, text, null, "");
    }

    /**
     * @param prefix marks the line as coming from Discord, e.g. "[Discord]"; blank for none.
     *               Only added when there is an author (so welcome messages stay unprefixed).
     */
    public static String toGuildCommand(String discordAuthor, String text, ProfanityFilter filter, String prefix) {
        if (filter != null) {
            discordAuthor = filter.censor(discordAuthor);
            text = filter.censor(text);
        }
        String author = clean(LinkFilter.block(discordAuthor));
        String body = clean(LinkFilter.block(text));
        if (body.isEmpty()) return null;
        String tag = clean(prefix);

        // The leading slash counts toward the limit when the client sends it.
        String who = author.isEmpty() ? "" : (tag.isEmpty() ? "" : tag + " ") + author + ": ";
        String head = COMMAND + who;
        int room = CHAT_LIMIT - 1 - head.length();
        if (room <= 0) return null;
        if (body.length() > room) body = body.substring(0, room - 3) + "...";
        return head + body;
    }

    /** Same as above, with Discord name and text run through the filter first. */
    public static String toGuildCommand(String discordAuthor, String text, ProfanityFilter filter) {
        return toGuildCommand(discordAuthor, text, filter, "");
    }

    /**
     * The text to relay for a Discord message. Attachments and stickers are never relayed
     * (they are links in disguise); they show up as "[attachment]" / "[sticker]" instead.
     */
    public static String discordText(String content, int attachments, int stickers) {
        StringBuilder sb = new StringBuilder(content == null ? "" : content.strip());
        if (attachments > 0) sb.append(sb.isEmpty() ? "" : " ").append("[attachment]");
        if (stickers > 0) sb.append(sb.isEmpty() ? "" : " ").append("[sticker]");
        return sb.toString();
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
