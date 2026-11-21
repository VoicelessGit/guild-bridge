package com.guildbridge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feeds raw chat messages in, gets BridgeEvents out.
 *
 * <p>Hypixel wraps announcements between separator lines (a long run of dashes, drawn with
 * strikethrough so it looks like a solid bar). A block can arrive as one message with newlines
 * or as separate messages, so both are handled. Guild chat lines go to ChatParser.
 *
 * <p>FORMATS BELOW ARE UNVERIFIED. Replace them with real samples from Justin before trusting them.
 */
public final class ChatRouter {
    private static final Pattern SEPARATOR = Pattern.compile("^[-_▬]{10,}$");
    /** A separator with a title in the middle, e.g. "------  Guild: Message Of The Day  ------". */
    private static final Pattern TITLED_SEPARATOR = Pattern.compile("^[-_▬]{5,}\\s*(?<title>.*?\\S)\\s*[-_▬]{5,}$");
    private static final Pattern JOINED_GUILD = Pattern.compile(
            "^(?:\\[[A-Z+]+\\] )?(?<name>\\w{1,16}) joined the guild!$");
    /** Blocks that mention guilds but are not announcements for everyone. */
    private static final Pattern NOT_ANNOUNCEMENT = Pattern.compile(
            "(?i)invited you to join|click here|/g accept|message of the day");
    /** Keeps a stuck block from growing forever if a closing separator never arrives. */
    private static final int MAX_BLOCK_LINES = 12;
    /** A real block arrives within one moment; one older than this was a stray separator. */
    private static final long MAX_BLOCK_MILLIS = 3_000;

    private final java.util.function.LongSupplier clock;
    private List<String> block; // null when not inside a separator block
    private long blockStartedAt;

    public ChatRouter() {
        this(System::currentTimeMillis);
    }

    /** For tests: a fake clock in milliseconds. */
    public ChatRouter(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized List<BridgeEvent> accept(String message) {
        List<BridgeEvent> out = new ArrayList<>();
        if (message == null) return out;
        long now = clock.getAsLong();
        if (block != null && now - blockStartedAt > MAX_BLOCK_MILLIS) block = null;
        for (String raw : TextUtil.stripFormatting(message).split("\\R")) {
            String line = raw.strip();
            // Guild/officer chat and join/leave lines are never part of a block, so a stray separator can't swallow it.
            var chat = ChatParser.parse(line);
            if (chat.isPresent()) {
                out.add(chat.get());
                continue;
            }
            Matcher titled = TITLED_SEPARATOR.matcher(line);
            if (SEPARATOR.matcher(line).matches()) {
                if (block == null) {
                    block = new ArrayList<>();
                    blockStartedAt = now;
                } else {
                    finishBlock(out);
                }
            } else if (titled.matches()) {
                // A titled separator opens a block whose first line is the title.
                if (block != null) finishBlock(out);
                block = new ArrayList<>();
                block.add(titled.group("title"));
                blockStartedAt = now;
            } else if (block != null) {
                if (!line.isEmpty()) block.add(line);
                if (block.size() > MAX_BLOCK_LINES) block = null;
            } else {
                chat.ifPresent(out::add);
            }
        }
        return out;
    }

    private void finishBlock(List<BridgeEvent> out) {
        List<String> lines = block;
        block = null;
        if (lines.isEmpty()) return;
        String text = String.join("\n", lines);
        // Blocks written in second person are about the bridge account itself (verified Nov 2026).
        if (text.startsWith("You were kicked from the guild")) {
            out.add(new BridgeEvent.Announcement("The bridge account was kicked from the guild.", null));
            return;
        }
        if (text.startsWith("You ")) return;
        if (!text.toLowerCase(Locale.ROOT).contains("guild")) return;
        if (NOT_ANNOUNCEMENT.matcher(text).find()) return;

        String joined = null;
        for (String l : lines) {
            Matcher m = JOINED_GUILD.matcher(l);
            if (m.matches()) joined = m.group("name");
        }
        out.add(new BridgeEvent.Announcement(text, joined));
    }
}
