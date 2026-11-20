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
    void webhookBlocksAllMentions() {
        var event = ChatParser.parse("Guild > BridgeBot: @everyone hi").orElseThrow();
        var json = JsonParser.parseString(WebhookPayload.forEvent(event)).getAsJsonObject();
        assertEquals(0, json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertEquals("BridgeBot", json.get("username").getAsString());
        assertTrue(json.get("avatar_url").getAsString().endsWith("/BridgeBot"));
    }

    @Test
    void webhookShowsGuildRankAndEscapesMarkdown() {
        var event = ChatParser.parse("Guild > [VIP+] ExamplePlayer [GM]: *bold* _it_").orElseThrow();
        var json = JsonParser.parseString(WebhookPayload.forEvent(event)).getAsJsonObject();
        assertEquals("ExamplePlayer [GM]", json.get("username").getAsString());
        assertEquals("\\*bold\\* \\_it\\_", FeaturesTest.description(json));
        assertEquals(WebhookPayload.GUILD_GREEN, FeaturesTest.embed(json).get("color").getAsInt());
    }

    @Test
    void outgoingCommandFormat() {
        assertEquals("gc Justin: hello", OutgoingSanitizer.toGuildCommand("Justin", "hello"));
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
    void outgoingEmptyReturnsNull() {
        assertNull(OutgoingSanitizer.toGuildCommand("Justin", "  \n "));
    }
}
