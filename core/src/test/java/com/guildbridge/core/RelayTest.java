package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class RelayTest {

    @Test
    void loopGuardDropsOwnMessages() {
        var guard = new LoopGuard("BridgeBot");
        assertFalse(guard.shouldRelay(ChatParser.parse("Guild > BridgeBot: hey!").orElseThrow()));
        assertTrue(guard.shouldRelay(ChatParser.parse("Guild > [VIP+] ExamplePlayer [GM]: hi").orElseThrow()));
    }

    @Test
    void chatEmbedShowsPlayerAsAuthor() {
        var event = ChatParser.parse("Guild > BridgeBot: @everyone hi").orElseThrow();
        var json = JsonParser.parseString(DiscordEmbed.forEvent(event)).getAsJsonObject();
        var author = json.getAsJsonObject("author");
        assertEquals("BridgeBot", author.get("name").getAsString());
        assertTrue(author.get("icon_url").getAsString().endsWith("/BridgeBot"));
        // Mentions inside embeds never ping, and the mod also disables mentions on every message.
        assertEquals("@everyone hi", json.get("description").getAsString());
    }

    @Test
    void chatEmbedShowsGuildRankAndEscapesMarkdown() {
        var event = ChatParser.parse("Guild > [VIP+] ExamplePlayer [GM]: *bold* _it_").orElseThrow();
        var json = JsonParser.parseString(DiscordEmbed.forEvent(event)).getAsJsonObject();
        assertEquals("ExamplePlayer [GM]", json.getAsJsonObject("author").get("name").getAsString());
        assertEquals("\\*bold\\* \\_it\\_", FeaturesTest.description(json));
        assertEquals(DiscordEmbed.GUILD_GREEN, FeaturesTest.embed(json).get("color").getAsInt());
    }

    @Test
    void outgoingCommandFormat() {
        assertEquals("gc Justin: hello", OutgoingSanitizer.toGuildCommand("Justin", "hello"));
    }

    @Test
    void outgoingDiscordPrefix() {
        assertEquals("gc [Discord] Justin: hello",
                OutgoingSanitizer.toGuildCommand("Justin", "hello", null, "[Discord]"));
        assertEquals("gc [D] Justin: hello",
                OutgoingSanitizer.toGuildCommand("Justin", "hello", null, " [D] "));
        assertEquals("gc Justin: hello", OutgoingSanitizer.toGuildCommand("Justin", "hello", null, ""));
        // No author (welcome messages): no prefix.
        assertEquals("gc Welcome!", OutgoingSanitizer.toGuildCommand("", "Welcome!", null, "[Discord]"));
        String longCmd = OutgoingSanitizer.toGuildCommand("Justin", "x".repeat(1000), null, "[Discord]");
        assertTrue(longCmd.length() + 1 <= OutgoingSanitizer.CHAT_LIMIT);
        assertTrue(longCmd.startsWith("gc [Discord] Justin: "));
    }

    @Test
    void outgoingStripsNewlinesAndSectionSigns() {
        assertEquals("gc Justin: line one line two a",
                OutgoingSanitizer.toGuildCommand("Justin", "line one\nline two §a"));
    }

    @Test
    void outgoingRespectsChatLimit() {
        String cmd = OutgoingSanitizer.toGuildCommand("Justin", "x".repeat(1000));
        assertTrue(cmd.length() + 1 <= OutgoingSanitizer.CHAT_LIMIT, "length " + cmd.length());
        assertTrue(cmd.endsWith("..."));
    }

    @Test
    void discordAttachmentsAndStickersAreNotRelayed() {
        assertEquals("look [attachment]", OutgoingSanitizer.discordText("look", 2, 0));
        assertEquals("[attachment]", OutgoingSanitizer.discordText("", 1, 0));
        assertEquals("[sticker]", OutgoingSanitizer.discordText(null, 0, 1));
        assertEquals("hi", OutgoingSanitizer.discordText(" hi ", 0, 0));
    }

    @Test
    void outgoingEmptyReturnsNull() {
        assertNull(OutgoingSanitizer.toGuildCommand("Justin", "  \n "));
    }

    // Real login sequence from the server, Nov 21 2026: the Message Of The Day block used to leave
    // the router stuck inside a block, swallowing guild chat afterwards.
    @Test
    void motdBlockDoesNotSwallowLaterChat() {
        long[] now = {0};
        ChatRouter r = new ChatRouter(() -> now[0]);
        assertTrue(r.accept("                          ").isEmpty());
        assertTrue(r.accept("--------------  Guild: Message Of The Day  --------------").isEmpty());
        assertTrue(r.accept("| USE CODE: EXAMPLE IN THE HYPIXEL STORE!!!").isEmpty());
        assertTrue(r.accept("-----------------------------------------------------").isEmpty(), "MOTD is not relayed");
        now[0] += 150_000;
        assertTrue(r.accept("\u00a7b[MVP\u00a7c+\u00a7b] BigBoiToasty\u00a7f \u00a76joined the lobby!").isEmpty());
        assertEquals(1, r.accept("Guild > PlayerOne joined.").size());
        var chat = r.accept("\u00a72Guild > \u00a7b[MVP\u00a7f++\u00a7b] PlayerOne \u00a7e[CHAD]\u00a7f: thx for responding gng :sob:");
        assertEquals(1, chat.size());
        assertEquals("thx for responding gng :sob:", ((BridgeEvent.Chat) chat.get(0)).message());
    }

    @Test
    void guildChatInsideAnOpenBlockStillRelays() {
        ChatRouter r = new ChatRouter(() -> 0);
        r.accept("-----------------------------------------------------");
        assertEquals(1, r.accept("Guild > [MVP+] Someone [Member]: hi").size());
    }

    @Test
    void strayOpenSeparatorExpires() {
        long[] now = {0};
        ChatRouter r = new ChatRouter(() -> now[0]);
        r.accept("-----------------------------------------------------");
        now[0] += 10_000;
        // A later block must open fresh instead of closing the stale one.
        r.accept("-----------------------------------------------------");
        r.accept("Steve joined the guild!");
        var out = r.accept("-----------------------------------------------------");
        assertEquals(1, out.size());
        assertEquals("Steve", ((BridgeEvent.Announcement) out.get(0)).joinedMember());
    }
}
