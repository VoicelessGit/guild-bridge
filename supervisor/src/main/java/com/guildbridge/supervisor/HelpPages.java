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
        StringBuilder bot = new StringBuilder(BotCommand.helpText()).append("\n\n**In game (guild chat)**\n");
        StringBuilder pv = new StringBuilder("Use these in guild chat in game. Account linking isn't available yet, "
                + "so they don't work here in Discord.\n"
                + "Leave out `[user]` to look yourself up. `[profile]` is a profile's fruit name; "
                + "without it your active profile is used.\n\n");
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

    private static String trim(StringBuilder sb) {
        return sb.length() > 4000 ? sb.substring(0, 4000) + "…" : sb.toString();
    }
}
