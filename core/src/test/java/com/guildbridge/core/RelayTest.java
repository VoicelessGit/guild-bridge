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
}
