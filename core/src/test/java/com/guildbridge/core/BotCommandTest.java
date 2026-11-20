package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class BotCommandTest {

    private static BotCommand.Parsed p(String s) {
        return BotCommand.parse(s).orElseThrow();
    }

    @Test
    void memberCommands() {
        assertInstanceOf(BotCommand.Commands.class, p("!commands"));
        assertEquals("g online", BotCommand.gameCommand(p("!g online")));
        assertEquals("g top", BotCommand.gameCommand(p("!g top")));
        assertEquals("g top 1", BotCommand.gameCommand(p("!g top 1")));
        assertEquals("g top 10", BotCommand.gameCommand(p("  !G TOP 10 ")));
        assertFalse(BotCommand.requiresAdmin(p("!g online")));
        assertFalse(BotCommand.requiresAdmin(p("!g top 3")));
        assertFalse(BotCommand.requiresAdmin(p("!commands")));
    }

    @Test
    void topArgumentMustBe1To10() {
        assertInstanceOf(BotCommand.Invalid.class, p("!g top 0"));
        assertInstanceOf(BotCommand.Invalid.class, p("!g top 11"));
        assertInstanceOf(BotCommand.Invalid.class, p("!g top -1"));
        assertInstanceOf(BotCommand.Invalid.class, p("!g top abc"));
        assertInstanceOf(BotCommand.Invalid.class, p("!g top 3; g leave"));
        assertInstanceOf(BotCommand.Invalid.class, p("!g leave"));
        assertInstanceOf(BotCommand.Invalid.class, p("!g online extra"));
    }

    @Test
    void adminCommands() {
        assertEquals("hello guild", ((BotCommand.Say) p("!say hello guild")).text());
        assertEquals("lobby", BotCommand.gameCommand(p("!mc /lobby")));
        assertEquals("g online", BotCommand.gameCommand(p("!MC  //g online ")));
        assertEquals("pl", BotCommand.gameCommand(p("!mc §apl")));
        assertEquals("a b", BotCommand.gameCommand(p("!mc a\nb")));
        for (String s : List.of("!say hi", "!mc lobby", "!status", "!uptime", "!restart")) {
            assertTrue(BotCommand.requiresAdmin(p(s)), s);
        }
    }

    @Test
    void malformedAdminCommandsGiveUsage() {
        assertInstanceOf(BotCommand.Invalid.class, p("!say"));
        assertInstanceOf(BotCommand.Invalid.class, p("!mc"));
        assertInstanceOf(BotCommand.Invalid.class, p("!mc /"));
        assertInstanceOf(BotCommand.Invalid.class, p("!mc " + "x".repeat(300)));
    }

    @Test
    void onlyKnownWordsCountAsCommands() {
        assertTrue(BotCommand.looksLikeCommand("!g"));
        assertTrue(BotCommand.looksLikeCommand("!restart"));
        assertFalse(BotCommand.looksLikeCommand("!mcdonalds anyone"));
        assertFalse(BotCommand.looksLikeCommand("!!!"));
        assertFalse(BotCommand.looksLikeCommand("gg"));
        assertTrue(BotCommand.parse("hello").isEmpty());
    }

    @Test
    void helpListsBothTiersSeparately() {
        String help = BotCommand.helpText();
        int member = help.indexOf("**Member commands**");
        int admin = help.indexOf("**Admin commands**");
        assertTrue(member >= 0 && admin > member);
        assertTrue(help.indexOf("!g top") < admin);
        assertTrue(help.indexOf("!restart") > admin);
        assertFalse(help.contains("—"));
    }

    @Test
    void responseCleanupDropsSeparatorsAndBlanks() {
        String sep = "-----------------------------------------------------";
        String cleaned = CommandResponse.clean(List.of(
                sep + "\n                Guild Name: testing\n\n-- Guild Master --\n[VIP+] ExamplePlayer ●\n" + sep,
                "§aOnline Members: 1"));
        assertEquals("Guild Name: testing\n-- Guild Master --\n[VIP+] ExamplePlayer ●\nOnline Members: 1", cleaned);
    }

    @Test
    void responseCleanupIsCapped() {
        String many = "line\n".repeat(200);
        String cleaned = CommandResponse.clean(List.of(many));
        assertEquals(CommandResponse.MAX_LINES + 1, cleaned.split("\n").length);
    }

    @Test
    void responseEmbedUsesCodeBlockAndFilters() {
        var json = JsonParser.parseString(
                DiscordEmbed.response("Guild online", "join scam.xyz/x ```", null)).getAsJsonObject();
        assertEquals("Guild online", json.get("title").getAsString());
        assertEquals("```\njoin [blocked link] '''\n```", json.get("description").getAsString());
        var empty = JsonParser.parseString(DiscordEmbed.response("t", "  ", null)).getAsJsonObject();
        assertTrue(empty.get("description").getAsString().contains("no response"));
    }

    @Test
    void durations() {
        assertEquals("45s", CommandResponse.formatDuration(45_000));
        assertEquals("2m 5s", CommandResponse.formatDuration(125_000));
        assertEquals("3h 4m", CommandResponse.formatDuration((3 * 60 + 4) * 60_000L));
        assertEquals("2d 1h 0m", CommandResponse.formatDuration(49 * 3_600_000L));
        assertEquals("0s", CommandResponse.formatDuration(-5));
    }

    @Test
    void adminFileStillWorks() throws Exception {
        var file = java.nio.file.Files.createTempDirectory("gb").resolve("admins.txt");
        var admins = new AdminList(file);
        admins.createIfMissing();
        assertFalse(admins.isAdmin("123456789012345678"));
        java.nio.file.Files.writeString(file, AdminList.TEMPLATE + "123456789012345678 # justin\n");
        java.nio.file.Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertTrue(admins.isAdmin("123456789012345678"));
    }
}
