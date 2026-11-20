package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class LinkFilterTest {

    private static String b(String s) {
        return LinkFilter.block(s);
    }

    @Test
    void blocksCommonLinkShapes() {
        assertEquals("join [blocked link] now", b("join https://discord.gg/abc123 now"));
        assertEquals("[blocked link]", b("http://example.com/path?x=1"));
        assertEquals("see [blocked link]", b("see www.youtube.com/watch?v=x"));
        assertEquals("[blocked link]", b("discord.gg/freenitro"));
        assertEquals("[blocked link]", b("BIT.LY/abc"));
        assertEquals("play on [blocked link]", b("play on mc.hypixel.net"));
        assertEquals("ip [blocked link]", b("ip 192.168.1.20:25565"));
    }

    @Test
    void leavesNormalChatAlone() {
        String[] safe = {
                "gg wp", "version 1.21.11 is out", "e.g. this", "lol.", "ok...", "log.in tomorrow",
                "3.5 stars", "my email is not a link", "it's 2.30 pm", "wait...what", "S.T.A.L.K.E.R"
        };
        for (String s : safe) assertEquals(s, b(s), "changed: " + s);
    }

    @Test
    void gameToDiscordBlocksLinks() {
        var e = ChatParser.parse("Guild > BridgeBot: free coins at scam.xyz/login").orElseThrow();
        var json = JsonParser.parseString(DiscordEmbed.forEvent(e)).getAsJsonObject();
        assertEquals("free coins at [blocked link]", FeaturesTest.description(json));
    }

    @Test
    void discordToGameBlocksLinks() {
        assertEquals("gc Justin: look [blocked link]",
                OutgoingSanitizer.toGuildCommand("Justin", "look https://imgur.com/a/xyz"));
    }

    @Test
    void maskedMarkdownLinkIsBlocked() {
        assertEquals("[click]([blocked link]", b("[click](https://evil.com)"));
    }
}
