package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdminCommandTest {
    private static BotCommand.Parsed p(String s) {
        return BotCommand.parse(s).orElseThrow();
    }

    @Test
    void parsesAdminSubcommands() {
        assertEquals(new BotCommand.AdminAdd("123456789012345678"), p("!admin add 123456789012345678"));
        assertEquals(new BotCommand.AdminAdd("123456789012345678"), p("!admin add <@123456789012345678>"));
        assertEquals(new BotCommand.AdminRemove("123456789012345678"), p("!admin remove <@!123456789012345678>"));
        assertEquals(new BotCommand.AdminShow(), p("!admin list"));
        assertInstanceOf(BotCommand.Invalid.class, p("!admin"));
        assertInstanceOf(BotCommand.Invalid.class, p("!admin add bob"));
        assertInstanceOf(BotCommand.Invalid.class, p("!admin add 123"));
        assertInstanceOf(BotCommand.Invalid.class, p("!admin add 123456789012345678 extra"));
    }

    @Test
    void adminCommandsAreOwnerOnly() {
        assertTrue(BotCommand.requiresOwner(p("!admin list")));
        assertTrue(BotCommand.requiresOwner(p("!admin add 123456789012345678")));
        assertFalse(BotCommand.requiresAdmin(p("!admin list")));
        assertFalse(BotCommand.requiresOwner(p("!say hi")));
        assertTrue(BotCommand.looksLikeCommand("!admin list"));
    }

    @Test
    void addAndRemoveKeepHandWrittenLines() throws Exception {
        Path dir = Files.createTempDirectory("admins");
        Path f = dir.resolve("guildbridge-admins.txt");
        Files.writeString(f, "# my notes\n111111111111111111  # friend\n");
        AdminList a = new AdminList(f);
        assertTrue(a.isAdmin("111111111111111111"));

        assertTrue(a.add("222222222222222222", "bob, added from Discord"));
        assertFalse(a.add("222222222222222222", "again"));
        assertTrue(a.isAdmin("222222222222222222"));
        assertEquals(java.util.List.of("111111111111111111", "222222222222222222"), a.list());
        assertTrue(Files.readString(f).contains("# my notes"));

        assertTrue(a.remove("111111111111111111"));
        assertFalse(a.remove("111111111111111111"));
        assertFalse(a.isAdmin("111111111111111111"));
        assertTrue(a.isAdmin("222222222222222222"));
        String text = Files.readString(f);
        assertTrue(text.contains("# my notes"));
        assertFalse(text.contains("friend"));
    }

    @Test
    void addCreatesFileWhenMissing() throws Exception {
        Path f = Files.createTempDirectory("admins2").resolve("guildbridge-admins.txt");
        AdminList a = new AdminList(f);
        assertTrue(a.add("333333333333333333", ""));
        assertTrue(a.isAdmin("333333333333333333"));
        assertTrue(Files.readString(f).startsWith("# Guild Bridge admins"));
    }

    @Test
    void notesCannotInjectExtraIds() throws Exception {
        Path f = Files.createTempDirectory("admins3").resolve("guildbridge-admins.txt");
        AdminList a = new AdminList(f);
        a.add("444444444444444444", "evil\n555555555555555555");
        assertFalse(a.isAdmin("555555555555555555"));
    }
}
