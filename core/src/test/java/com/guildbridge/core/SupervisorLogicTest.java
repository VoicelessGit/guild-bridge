package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SupervisorLogicTest {

    // --- Disconnect classification ---

    private static DisconnectKind k(String reason) {
        return DisconnectKind.classify(reason);
    }

    @Test
    void realDisconnectFromThisProject() {
        // Seen on Justin's PC, Nov 2026.
        assertEquals(DisconnectKind.NETWORK, k("Network Protocol Error"));
    }

    @Test
    void bansWinOverEverything() {
        assertEquals(DisconnectKind.BANNED, k("You are temporarily banned for 29d 23h 59m from this server!"));
        assertEquals(DisconnectKind.BANNED, k("You are permanently banned from this server!\nReason: Cheating"));
        assertEquals(DisconnectKind.BANNED, k("Failed to connect to the server | You are temporarily banned | Ban ID: #ABC123"));
        assertEquals(DisconnectKind.BANNED, k("§cYou are currently blacklisted from this server"));
    }

    @Test
    void duplicateLogin() {
        assertEquals(DisconnectKind.DUPLICATE_LOGIN, k("You logged in from another location"));
    }

    @Test
    void loginFailures() {
        assertEquals(DisconnectKind.LOGIN_FAILED, k("Failed to log in: Invalid session (Try restarting your game)"));
        assertEquals(DisconnectKind.LOGIN_FAILED, k("Authentication servers are down. Please try again later"));
        assertEquals(DisconnectKind.LOGIN_FAILED, k("Multiplayer is disabled"));
    }

    @Test
    void networkProblems() {
        assertEquals(DisconnectKind.NETWORK, k("Connection Lost | Timed out"));
        assertEquals(DisconnectKind.NETWORK, k("Internal Exception: java.net.SocketException: Connection reset"));
        assertEquals(DisconnectKind.NETWORK, k("Failed to connect to the server | Connection refused: no further information"));
        assertEquals(DisconnectKind.NETWORK, k("Disconnected | End of stream"));
    }

    @Test
    void kicks() {
        assertEquals(DisconnectKind.KICKED, k("You have been kicked!"));
        assertEquals(DisconnectKind.KICKED, k("This server is restarting. Please reconnect."));
        assertEquals(DisconnectKind.KICKED, k("Proxy restarting"));
    }

    @Test
    void unknownStaysUnknown() {
        assertEquals(DisconnectKind.UNKNOWN, k("Something new and strange happened"));
        assertEquals(DisconnectKind.UNKNOWN, k(""));
        assertEquals(DisconnectKind.UNKNOWN, k(null));
    }

    // --- Alert limiting ---

    @Test
    void cooldownPerKey() {
        var a = new AlertLimiter(30 * 60_000L);
        assertTrue(a.allow("ban", 0));
        assertFalse(a.allow("ban", 10 * 60_000L));
        assertTrue(a.allow("crash", 10 * 60_000L));      // different key
        assertTrue(a.allow("ban", 31 * 60_000L));        // cooldown passed
        a.reset("crash");
        assertTrue(a.allow("crash", 11 * 60_000L));      // reset clears it
    }

    @Test
    void countsInsideWindowOnly() {
        var a = new AlertLimiter(0);
        long w = 30 * 60_000L;
        assertEquals(1, a.count("net", 0, w));
        assertEquals(2, a.count("net", 60_000, w));
        assertEquals(3, a.count("net", 120_000, w));
        assertEquals(2, a.count("net", w + 90_000, w));  // first two fell out of the window
    }

    // --- IPC protocol ---

    @Test
    void eventsRoundTrip() {
        BridgeEvent[] events = {
                new BridgeEvent.Chat(BridgeEvent.Channel.GUILD, "VIP+", "ExamplePlayer", "GM", "hi"),
                new BridgeEvent.Chat(BridgeEvent.Channel.OFFICER, null, "BridgeBot", null, "a \"quoted\" line"),
                new BridgeEvent.Status("BridgeBot", false),
                new BridgeEvent.Announcement("BridgeBot joined the guild!", "BridgeBot"),
                new BridgeEvent.Announcement("The Guild has reached Level 5!", null),
        };
        for (BridgeEvent e : events) {
            String line = Ipc.event(e).toString();
            var parsed = Ipc.parse(line);
            assertEquals("event", Ipc.type(parsed));
            assertEquals(e, Ipc.toEvent(parsed.getAsJsonObject("event")), line);
        }
    }

    @Test
    void parseRejectsGarbage() {
        assertNull(Ipc.parse(""));
        assertNull(Ipc.parse("not json"));
        assertNull(Ipc.parse("[1,2]"));
        assertNull(Ipc.parse("{\"no\":\"type\"}"));
        assertNull(Ipc.toEvent(Ipc.parse("{\"type\":\"event\",\"kind\":\"chat\"}")));
        assertNull(Ipc.toEvent(Ipc.parse("{\"type\":\"x\",\"kind\":\"weird\"}")));
    }

    @Test
    void commandMessage() {
        var o = Ipc.parse(Ipc.command(7, "g online", false, true).toString());
        assertEquals("command", Ipc.type(o));
        assertEquals(7, Ipc.num(o, "id", -1));
        assertEquals("g online", Ipc.str(o, "command"));
        assertFalse(Ipc.bool(o, "checkRepeats", true));
        assertTrue(Ipc.bool(o, "capture", false));
        assertEquals(-1, Ipc.num(Ipc.parse(Ipc.autoJoin(-1).toString()), "pausedMinutes", 0));
    }

    // --- New admin commands ---

    @Test
    void processCommands() {
        assertInstanceOf(BotCommand.Start.class, BotCommand.parse("!start").orElseThrow());
        assertInstanceOf(BotCommand.Stop.class, BotCommand.parse("!stop").orElseThrow());
        assertInstanceOf(BotCommand.Rejoin.class, BotCommand.parse("!rejoin").orElseThrow());
        assertEquals(20, ((BotCommand.Logs) BotCommand.parse("!logs").orElseThrow()).lines());
        assertEquals(5, ((BotCommand.Logs) BotCommand.parse("!logs 5").orElseThrow()).lines());
        assertInstanceOf(BotCommand.Invalid.class, BotCommand.parse("!logs 99").orElseThrow());
        assertInstanceOf(BotCommand.Invalid.class, BotCommand.parse("!logs abc").orElseThrow());
        for (String s : new String[] {"!start", "!stop", "!logs", "!rejoin"}) {
            assertTrue(BotCommand.requiresAdmin(BotCommand.parse(s).orElseThrow()), s);
        }
        assertTrue(BotCommand.helpText().contains("!stop"));
        assertTrue(BotCommand.helpText().contains("!rejoin"));
    }
}
