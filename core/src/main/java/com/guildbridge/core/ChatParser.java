package com.guildbridge.core;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a plain-text Hypixel chat line into a BridgeEvent. Non-guild lines return empty. */
public final class ChatParser {

    // Guild > [VIP+] ExamplePlayer [GM]: hi
    // Guild > BridgeBot: hey!
    // Officer > [MVP++] Someone [Officer]: text
    private static final Pattern CHAT = Pattern.compile(
            "^(?<channel>Guild|Officer) > (?:\\[(?<rank>[A-Z+]+)\\] )?(?<name>\\w{1,16})"
                    + "(?: \\[(?<grank>[^\\]]+)\\])?: (?<msg>.*)$");

    // Guild > Name joined.   /   Guild > Name left.
    private static final Pattern STATUS = Pattern.compile(
            "^Guild > (?<name>\\w{1,16}) (?<action>joined|left)\\.$");

    private ChatParser() {}

    /** "From [MVP+] Steve: hello" (a private message to us). "To ..." lines are ours and ignored. */
    private static final Pattern WHISPER = Pattern.compile(
            "^From (?:\\[[A-Z+]+\\] )?(?<name>\\w{1,16}): (?<msg>.+)$");

    /** A private message to the bridge account, if this line is one. Separate from {@link #parse}. */
    public static Optional<BridgeEvent.Whisper> whisper(String line) {
        if (line == null) return Optional.empty();
        Matcher m = WHISPER.matcher(TextUtil.stripFormatting(line).strip());
        return m.matches() ? Optional.of(new BridgeEvent.Whisper(m.group("name"), m.group("msg").strip())) : Optional.empty();
    }

    public static Optional<BridgeEvent> parse(String line) {
        if (line == null) return Optional.empty();
        String clean = TextUtil.stripFormatting(line).strip();

        Matcher m = CHAT.matcher(clean);
        if (m.matches()) {
            BridgeEvent.Channel channel = m.group("channel").equals("Officer")
                    ? BridgeEvent.Channel.OFFICER : BridgeEvent.Channel.GUILD;
            return Optional.of(new BridgeEvent.Chat(
                    channel, m.group("rank"), m.group("name"), m.group("grank"), m.group("msg")));
        }

        Matcher s = STATUS.matcher(clean);
        if (s.matches()) {
            return Optional.of(new BridgeEvent.Status(
                    s.group("name"), s.group("action").equals("joined")));
        }
        return Optional.empty();
    }
}
