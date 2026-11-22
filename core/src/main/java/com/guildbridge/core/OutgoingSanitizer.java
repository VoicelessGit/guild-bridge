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

    /** A Discord message split into game lines. {@code cut} means it was too long even for two. */
    public record Split(java.util.List<String> commands, boolean cut) {}

    /**
     * Like {@link #toGuildCommand(String, String, ProfanityFilter, String)}, but a message that is
     * too long for one chat line becomes two, split at a space, each ending with (1/2) or (2/2)
     * and each carrying the author so other chat in between can't confuse who said what. Longer
     * than two lines: the second ends with "..." and {@code cut} is true.
     */
    public static Split toGuildCommands(String discordAuthor, String text, ProfanityFilter filter, String prefix) {
        String single = toGuildCommand(discordAuthor, text, filter, prefix);
        if (single == null) return new Split(java.util.List.of(), false);
        if (filter != null) {
            discordAuthor = filter.censor(discordAuthor);
            text = filter.censor(text);
        }
        String author = clean(LinkFilter.block(discordAuthor));
        String body = clean(LinkFilter.block(text));
        String tag = clean(prefix);
        String who = author.isEmpty() ? "" : (tag.isEmpty() ? "" : tag + " ") + author + ": ";
        String head = COMMAND + who;
        int oneRoom = CHAT_LIMIT - 1 - head.length();
        if (body.length() <= oneRoom) return new Split(java.util.List.of(single), false);

        int room = oneRoom - " (1/2)".length();
        if (room < 20) return new Split(java.util.List.of(single), true);
        int cutAt = body.lastIndexOf(' ', room);
        if (cutAt < room / 2) cutAt = room; // no good space: cut mid-word
        String first = body.substring(0, cutAt).strip();
        String rest = body.substring(cutAt).strip();
        boolean cut = false;
        if (rest.length() > room) {
            rest = rest.substring(0, room - 3) + "...";
            cut = true;
        }
        return new Split(java.util.List.of(head + first + " (1/2)", head + rest + " (2/2)"), cut);
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
