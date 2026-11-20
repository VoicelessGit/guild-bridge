package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdminCommandTest {

    private static String run(String content) {
        var p = AdminCommand.parse(content).orElseThrow();
        return assertInstanceOf(AdminCommand.Run.class, p).command();
    }

    @Test
    void runsCommandsWithOrWithoutSlash() {
        assertEquals("lobby", run("!mc /lobby"));
        assertEquals("locraw", run("!mc locraw"));
        assertEquals("g online", run("  !MC   //g online  "));
    }

    @Test
    void stripsNewlinesAndColorCodes() {
        assertEquals("g online", run("!mc g\nonline"));
        assertEquals("pl", run("!mc §apl"));
    }

    @Test
    void statusCommand() {
        assertInstanceOf(AdminCommand.Status.class, AdminCommand.parse("!status").orElseThrow());
    }

    @Test
    void rejectsEmptyOrTooLong() {
        assertTrue(AdminCommand.parse("!mc ").isEmpty());
        assertTrue(AdminCommand.parse("!mc /").isEmpty());
        assertTrue(AdminCommand.parse("!mc " + "x".repeat(300)).isEmpty());
        assertTrue(AdminCommand.parse("hello").isEmpty());
    }

    @Test
    void adminFileIsReadWithCommentsAndReloadsOnChange() throws Exception {
        var file = java.nio.file.Files.createTempDirectory("gb").resolve("admins.txt");
        var admins = new AdminList(file);
        assertFalse(admins.isAdmin("123456789012345678")); // no file yet
        admins.createIfMissing();
        assertFalse(admins.isAdmin("123456789012345678")); // template only
        java.nio.file.Files.writeString(file, AdminList.TEMPLATE
                + "123456789012345678  # justin\n   \nnot-an-id\n");
        java.nio.file.Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertTrue(admins.isAdmin("123456789012345678"));
        assertFalse(admins.isAdmin("999999999999999999"));
        assertFalse(admins.isAdmin(null));
    }

    @Test
    void commandLookingMessagesAreNeverRelayed() {
        assertTrue(AdminCommand.looksLikeCommand("!mc"));
        assertTrue(AdminCommand.looksLikeCommand("!mc /lobby"));
        assertTrue(AdminCommand.looksLikeCommand("!STATUS"));
        assertFalse(AdminCommand.looksLikeCommand("!mcdonalds anyone"));
        assertFalse(AdminCommand.looksLikeCommand("hi"));
    }
}
