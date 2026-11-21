package com.guildbridge.core;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Commands typed in the bridge channel. They are never relayed to guild chat.
 *
 * <p>Members (anyone in the channel): !commands, !g online, !g top [1-10].
 * Admins (listed in guildbridge-admins.txt): everything above plus !say, !mc, !status, !uptime,
 * !start, !stop, !restart, !logs [lines], !rejoin.
 * Owner (set in guildbridge.json): everything above plus !admin add/remove/list.
 *
 * <p>Security: nothing here ever reaches a shell. Each command maps to fixed Minecraft chat
 * commands, or to admin-only text that goes through the same sanitizing as normal chat.
 */
public final class BotCommand {
    public sealed interface Parsed {}

    // Member commands
    public record Commands() implements Parsed {}
    public record GuildOnline() implements Parsed {}
    /** day is null for plain "!g top". */
    public record GuildTop(Integer day) implements Parsed {}

    // Admin commands
    public record Say(String text) implements Parsed {}
    public record Run(String command) implements Parsed {}
    public record Status() implements Parsed {}
    public record Uptime() implements Parsed {}
    public record Restart() implements Parsed {}
    public record Start() implements Parsed {}
    public record Stop() implements Parsed {}
    /** lines to show, 1 to 50. */
    public record Logs(int lines) implements Parsed {}
    /** Clears a ban or duplicate-login pause and lets auto-join try again. */
    public record Rejoin() implements Parsed {}

    // Owner commands
    public record AdminAdd(String userId) implements Parsed {}
    public record AdminRemove(String userId) implements Parsed {}
    public record AdminShow() implements Parsed {}

    /** A known command used wrongly; message is the usage hint to show. */
    public record Invalid(String message) implements Parsed {}

    private static final Set<String> KNOWN = Set.of(
            "commands", "g", "say", "mc", "status", "uptime", "restart", "start", "stop", "logs", "rejoin", "admin");

    private BotCommand() {}

    /** True for anything that starts like one of our commands, so it is never relayed. */
    public static boolean looksLikeCommand(String content) {
        if (content == null) return false;
        String s = content.strip();
        if (!s.startsWith("!")) return false;
        String word = s.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return KNOWN.contains(word);
    }

    public static boolean requiresAdmin(Parsed p) {
        return p instanceof Say || p instanceof Run || p instanceof Status || p instanceof Uptime
                || p instanceof Restart || p instanceof Start || p instanceof Stop
                || p instanceof Logs || p instanceof Rejoin;
    }

    /** Commands only the owner may use. */
    public static boolean requiresOwner(Parsed p) {
        return p instanceof AdminAdd || p instanceof AdminRemove || p instanceof AdminShow;
    }

    /** Parses a message. Empty means it is not a command at all. */
    public static Optional<Parsed> parse(String content) {
        if (!looksLikeCommand(content)) return Optional.empty();
        String[] parts = content.strip().substring(1).split("\\s+", 2);
        String word = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].strip() : "";

        return Optional.of(switch (word) {
            case "commands" -> new Commands();
            case "status" -> new Status();
            case "uptime" -> new Uptime();
            case "restart" -> new Restart();
            case "start" -> new Start();
            case "stop" -> new Stop();
            case "rejoin" -> new Rejoin();
            case "logs" -> parseLogs(rest);
            case "g" -> parseGuild(rest);
            case "say" -> rest.isEmpty() ? new Invalid("Usage: `!say <message>`") : new Say(rest);
            case "mc" -> parseRun(rest);
            case "admin" -> parseAdmin(rest);
            default -> new Invalid("Unknown command. Try `!commands`.");
        });
    }

    private static Parsed parseGuild(String rest) {
        String[] args = rest.toLowerCase(Locale.ROOT).split("\\s+");
        if (args[0].equals("online") && args.length == 1) return new GuildOnline();
        if (args[0].equals("top")) {
            if (args.length == 1) return new GuildTop(null);
            if (args.length == 2 && args[1].matches("\\d{1,2}")) {
                int day = Integer.parseInt(args[1]);
                if (day >= 1 && day <= 10) return new GuildTop(day);
            }
            return new Invalid("Usage: `!g top` or `!g top <1-10>`");
        }
        return new Invalid("Usage: `!g online`, `!g top` or `!g top <1-10>`");
    }

    private static final String ADMIN_USAGE = "Usage: `!admin add <user id or @mention>`, "
            + "`!admin remove <user id or @mention>` or `!admin list`";

    private static Parsed parseAdmin(String rest) {
        String[] args = rest.split("\\s+");
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list") && args.length == 1) return new AdminShow();
        if ((sub.equals("add") || sub.equals("remove")) && args.length == 2) {
            // Accept a raw ID or a mention like <@123> / <@!123>.
            String id = args[1].replaceAll("^<@!?(\\d+)>$", "$1");
            if (id.matches("\\d{15,25}")) return sub.equals("add") ? new AdminAdd(id) : new AdminRemove(id);
        }
        return new Invalid(ADMIN_USAGE);
    }

    private static Parsed parseLogs(String rest) {
        if (rest.isEmpty()) return new Logs(20);
        if (rest.matches("\\d{1,2}")) {
            int n = Integer.parseInt(rest);
            if (n >= 1 && n <= 50) return new Logs(n);
        }
        return new Invalid("Usage: `!logs` or `!logs <1-50>`");
    }

    private static Parsed parseRun(String rest) {
        String cmd = TextUtil.stripFormatting(rest.replaceAll("[\\r\\n\\t]+", " ")).strip();
        while (cmd.startsWith("/")) cmd = cmd.substring(1);
        cmd = cmd.strip();
        // 256 chat limit, minus the slash the client adds.
        if (cmd.isEmpty() || cmd.length() > 255) return new Invalid("Usage: `!mc <command>`, e.g. `!mc /lobby`");
        return new Run(cmd);
    }

    /** The Minecraft command (no slash) for a member guild command. */
    public static String gameCommand(Parsed p) {
        return switch (p) {
            case GuildOnline g -> "g online";
            case GuildTop t -> t.day() == null ? "g top" : "g top " + t.day();
            case Run r -> r.command();
            default -> throw new IllegalArgumentException("Not a game command: " + p);
        };
    }

    /** Text for !commands. Both tiers are listed, separated. */
    public static String helpText() {
        return """
                **Member commands**
                `!commands` show this list
                `!g online` who is online in the guild
                `!g top` today's top guild experience
                `!g top <1-10>` top guild experience from that many days ago

                **Admin commands**
                `!say <message>` send a message in guild chat as the bridge
                `!mc <command>` run any Minecraft command, e.g. `!mc /lobby`
                `!status` where the bridge is (limbo, lobby, offline, stopped)
                `!uptime` how long the bridge has been running and connected
                `!start` start the game
                `!stop` stop the game (the bot stays online)
                `!restart` restart the game
                `!logs` or `!logs <1-50>` last lines of the game log
                `!rejoin` let auto-join try again after a ban or login pause

                **Owner commands**
                `!admin add <id or @mention>` make someone an admin
                `!admin remove <id or @mention>` remove an admin
                `!admin list` show the admins""";
    }
}
