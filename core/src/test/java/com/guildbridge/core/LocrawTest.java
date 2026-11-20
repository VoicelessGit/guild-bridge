package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocrawTest {

    @Test
    void limboReply() {
        var info = Locraw.parse("{\"server\":\"limbo\"}").orElseThrow();
        assertTrue(info.inLimbo());
        assertFalse(info.inLobby());
    }

    @Test
    void lobbyReply() {
        var info = Locraw.parse(
                "{\"server\":\"dynamiclobby12A\",\"gametype\":\"MAIN\",\"lobbyname\":\"mainlobby1\"}").orElseThrow();
        assertFalse(info.inLimbo());
        assertTrue(info.inLobby());
        assertEquals("MAIN", info.gametype());
    }

    @Test
    void gameReply() {
        var info = Locraw.parse(
                "{\"server\":\"mini121\",\"gametype\":\"BEDWARS\",\"mode\":\"EIGHT_TWO\",\"map\":\"Aquarium\"}").orElseThrow();
        assertFalse(info.inLimbo());
        assertFalse(info.inLobby());
    }

    @Test
    void normalChatIsNotLocraw() {
        assertTrue(Locraw.parse("Guild > BridgeBot: {\"server\":\"limbo\"}").isEmpty());
        assertTrue(Locraw.parse("{not json \"server\"}").isEmpty());
        assertTrue(Locraw.parse("hello").isEmpty());
        assertTrue(Locraw.parse(null).isEmpty());
    }

    @Test
    void noticeEmbedColors() {
        var good = com.google.gson.JsonParser.parseString(DiscordEmbed.notice("Bridge is in limbo.", true)).getAsJsonObject();
        var bad = com.google.gson.JsonParser.parseString(DiscordEmbed.notice("not_in limbo", false)).getAsJsonObject();
        assertEquals(DiscordEmbed.NOTICE_GOOD, good.get("color").getAsInt());
        assertEquals(DiscordEmbed.NOTICE_BAD, bad.get("color").getAsInt());
        assertEquals("not\\_in limbo", bad.get("description").getAsString());
    }
}
