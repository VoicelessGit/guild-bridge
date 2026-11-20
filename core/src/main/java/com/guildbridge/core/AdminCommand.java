package com.guildbridge.core;

import java.util.Optional;

/**
 * Admin-only commands typed in the bridge channel. These are never relayed to guild chat.
 *
 * <ul>
 *   <li>{@code !mc <command>} runs a Minecraft command, for example {@code !mc /lobby} or {@code !mc locraw}</li>
 *   <li>{@code !status} asks the bot where it is</li>
 * </ul>
 */
public final class AdminCommand {
    public sealed interface Parsed {}
    public record Run(String command) implements Parsed {}
    public record Status() implements Parsed {}

    private AdminCommand() {}

    /** True for anything that looks like an admin command, so it is never relayed even if rejected. */
    public static boolean looksLikeCommand(String content) {
        if (content == null) return false;
        String s = content.strip().toLowerCase();
        return s.equals("!mc") || s.startsWith("!mc ") || s.equals("!status");
    }

    /** Returns the parsed command, or empty if it is not one or is malformed. */
    public static Optional<Parsed> parse(String content) {
        if (content == null) return Optional.empty();
        String s = content.strip();
        if (s.equalsIgnoreCase("!status")) return Optional.of(new Status());
        if (!s.toLowerCase().startsWith("!mc ")) return Optional.empty();

        String cmd = s.substring(4).replaceAll("[\\r\\n\\t]+", " ").strip();
        while (cmd.startsWith("/")) cmd = cmd.substring(1);
        cmd = TextUtil.stripFormatting(cmd).strip();
        // 256 chat limit, minus the slash the client adds.
        if (cmd.isEmpty() || cmd.length() > 255) return Optional.empty();
        return Optional.of(new Run(cmd));
    }
}
