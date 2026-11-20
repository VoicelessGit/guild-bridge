package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatParserTest {

    private static BridgeEvent.Chat chat(String line) {
        Optional<BridgeEvent> e = ChatParser.parse(line);
        assertTrue(e.isPresent(), "expected a match for: " + line);
        return assertInstanceOf(BridgeEvent.Chat.class, e.get());
    }

    // --- Real samples from Justin's screenshot, Nov 2026 ---

    @Test
    void rankedMemberWithGuildRank() {
        var c = chat("Guild > [VIP+] ExamplePlayer [GM]: hi");
        assertEquals(BridgeEvent.Channel.GUILD, c.channel());
        assertEquals("VIP+", c.rank());
        assertEquals("ExamplePlayer", c.name());
        assertEquals("GM", c.guildRank());
        assertEquals("hi", c.message());
    }

    @Test
    void nonWithNoGuildRank() {
        var c = chat("Guild > BridgeBot: hey!");
        assertNull(c.rank());
        assertEquals("BridgeBot", c.name());
        assertNull(c.guildRank());
        assertEquals("hey!", c.message());
    }

    @Test
    void messageWithQuestionMark() {
        assertEquals("how are you?", chat("Guild > [VIP+] ExamplePlayer [GM]: how are you?").message());
    }

    @Test
    void lobbyJoinIsIgnored() {
        assertTrue(ChatParser.parse("[MVP+] Mc_Gregory joined the lobby!").isEmpty());
        assertTrue(ChatParser.parse("[MVP+] P024 joined the lobby!").isEmpty());
    }

    // --- Expected formats, not yet confirmed in game ---

    @Test
    void mvpPlusPlusAndOfficerChat() {
        var c = chat("Officer > [MVP++] Someone_1 [Officer]: meeting at 8");
        assertEquals(BridgeEvent.Channel.OFFICER, c.channel());
        assertEquals("MVP++", c.rank());
        assertEquals("Officer", c.guildRank());
    }

    @Test
    void colonInsideMessageStaysInMessage() {
        assertEquals("time: 5pm", chat("Guild > BridgeBot: time: 5pm").message());
    }

    @Test
    void formattingCodesAreStripped() {
        var c = chat("§2Guild > §a[VIP§6+§a] ExamplePlayer §7[GM]§f: hi");
        assertEquals("ExamplePlayer", c.name());
        assertEquals("hi", c.message());
    }

    @Test
    void onlineStatus() {
        var e = ChatParser.parse("Guild > ExamplePlayer joined.").orElseThrow();
        var s = assertInstanceOf(BridgeEvent.Status.class, e);
        assertEquals("ExamplePlayer", s.name());
        assertTrue(s.joined());
        assertFalse(((BridgeEvent.Status) ChatParser.parse("Guild > ExamplePlayer left.").orElseThrow()).joined());
    }

    @Test
    void privateMessagesAndPartyChatAreIgnored() {
        assertTrue(ChatParser.parse("From [VIP+] ExamplePlayer: psst").isEmpty());
        assertTrue(ChatParser.parse("Party > [VIP+] ExamplePlayer: hi").isEmpty());
        assertTrue(ChatParser.parse("").isEmpty());
        assertTrue(ChatParser.parse(null).isEmpty());
    }
}
