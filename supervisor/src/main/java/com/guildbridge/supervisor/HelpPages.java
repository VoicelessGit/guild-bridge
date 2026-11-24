package com.guildbridge.supervisor;

import com.guildbridge.core.BotCommand;
import com.guildbridge.core.sb.PvCommands;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

/** The two pages of !commands in Discord. */
final class HelpPages {
    private HelpPages() {}

    static List<String> titles() {
        return List.of("Bot commands", "PV Commands");
    }

    static List<MessageEmbed> pages() {
        StringBuilder bot = new StringBuilder(BotCommand.helpText()).append("\n\n**In game or Discord**\n");
        StringBuilder pv = new StringBuilder("Use these in guild chat in game or here in Discord.\n"
                + "In game, leave out `[user]` to look yourself up. In Discord, add a name unless you've linked your "
                + "account with `/verify`. `[profile]` is a profile's fruit name; without it the active profile is used.\n\n");
        for (PvCommands.Spec s : PvCommands.ALL) {
            String aliases = s.aliases().isEmpty() ? "" : " (also " + String.join(", ", s.aliases().stream().map(a -> "!" + a).toList()) + ")";
            String line = "`" + s.usage() + "` " + s.help() + aliases + "\n";
            if (s.group() == PvCommands.Group.BOT) bot.append(line);
            else pv.append(line);
        }
        return List.of(
                new EmbedBuilder().setTitle("Bridge commands: bot").setDescription(trim(bot)).setColor(0x00AA00)
                        .setFooter("Page 1 of 2").build(),
                new EmbedBuilder().setTitle("Bridge commands: profile viewer").setDescription(trim(pv)).setColor(0x00AAAA)
                        .setFooter("Page 2 of 2 | Networth by SkyHelper").build());
    }

    static List<String> versionTitles() {
        return List.of("Current version", "Past versions");
    }

    /** !version in Discord: page 1 the running versions, page 2 every version with its first note. */
    static List<MessageEmbed> versionPages(String versionLine) {
        var all = com.guildbridge.core.Changelog.all();
        StringBuilder current = new StringBuilder(versionLine.replace(" | ", "\n"));
        if (!all.isEmpty()) {
            current.append("\n\n**What's new in ").append(all.get(0).version()).append("**\n").append(all.get(0).notes());
        }
        StringBuilder past = new StringBuilder("Use `!patch <version>` for a version's full notes.\n");
        for (var e : all) {
            String first = e.notes().lines().findFirst().orElse("").replaceFirst("^- ", "");
            past.append("\n**").append(e.version()).append("**  ").append(first);
        }
        return List.of(
                new EmbedBuilder().setTitle("Guild Bridge version").setDescription(trim(current)).setColor(0x00AA00)
                        .setFooter("Page 1 of 2").build(),
                new EmbedBuilder().setTitle("Past versions").setDescription(trim(past)).setColor(0x00AAAA)
                        .setFooter("Page 2 of 2").build());
    }

    private static String trim(StringBuilder sb) {
        return sb.length() > 4000 ? sb.substring(0, 4000) + "…" : sb.toString();
    }
}
