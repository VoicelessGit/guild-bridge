package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinkingTest {
    @Test
    void whispersParseAndNeverCountAsGuildChat() {
        var w = ChatParser.whisper("§dFrom §b[MVP§c+§b] Steve§7: §74F7K2Q");
        assertTrue(w.isPresent());
        assertEquals("Steve", w.get().name());
        assertEquals("4F7K2Q", w.get().message());
        assertEquals("Alex", ChatParser.whisper("From Alex: hi").get().name());
        assertTrue(ChatParser.whisper("To Steve: hi").isEmpty());
        assertTrue(ChatParser.whisper("Guild > Steve: hi").isEmpty());
        assertTrue(ChatParser.parse("From Steve: hi").isEmpty());
    }

    @Test
    void routerPassesWhispersEvenInsideABlock() {
        ChatRouter r = new ChatRouter(() -> 0);
        r.accept("-----------------------------------------------------");
        List<BridgeEvent> out = r.accept("From [VIP] Steve: ABC234");
        assertEquals(List.of(new BridgeEvent.Whisper("Steve", "ABC234")), out);
    }

    @Test
    void whisperSurvivesTheIpcRoundTrip() {
        var w = new BridgeEvent.Whisper("Steve", "ABC234");
        assertEquals(w, Ipc.toEvent(Ipc.toJson(w)));
        assertNull(DiscordEmbed.forEvent(w), "whispers are never posted");
    }

    @Test
    void codesOnlyWorkForTheRightPlayerAndExpire() {
        Verifier<String> v = new Verifier<>();
        var p = v.issue("111", "Steve", "uuid1", "hook", 0);
        assertEquals(6, p.code().length());
        assertTrue(v.onWhisper("Alex", p.code(), 1000).isEmpty(), "someone else can't use Steve's code");
        assertTrue(v.onWhisper("Steve", "WRONG1", 1000).isEmpty());
        var done = v.onWhisper("steve", p.code().toLowerCase(), 1000);
        assertTrue(done.isPresent());
        assertEquals("111", done.get().discordId());
        assertTrue(v.onWhisper("Steve", p.code(), 2000).isEmpty(), "single use");

        var q = v.issue("222", "Alex", "uuid2", "hook", 0);
        assertTrue(v.onWhisper("Alex", q.code(), Verifier.CODE_MILLIS + 1).isEmpty(), "expired");

        var first = v.issue("333", "Bob", "uuid3", "hook", 0);
        var second = v.issue("333", "Bob", "uuid3", "hook", 10);
        assertTrue(v.onWhisper("Bob", first.code(), 20).isEmpty(), "a new /verify replaces the old code");
        assertTrue(v.onWhisper("Bob", second.code(), 20).isPresent());
    }

    @Test
    void linksMoveToWhoeverVerifiedLast() throws Exception {
        Path f = Files.createTempDirectory("links").resolve("guildbridge-links.json");
        LinkStore s = new LinkStore(f);
        assertTrue(s.link("111", "UUID1", "Steve", 1).isEmpty());
        assertEquals("Steve", s.get("111").get().name());
        assertEquals("111", s.link("222", "uuid1", "Steve", 2).orElseThrow(), "moved from 111");
        assertTrue(s.get("111").isEmpty());
        assertTrue(s.get("222").isPresent());
        s.link("222", "uuid9", "Other", 3); // re-verifying a different account replaces your own link
        assertEquals("Other", s.get("222").get().name());
        LinkStore reloaded = new LinkStore(f);
        assertEquals("uuid9", reloaded.get("222").get().uuid());
        assertTrue(reloaded.unlink("other").isPresent(), "unlink by Minecraft name");
        assertTrue(reloaded.get("222").isEmpty());
    }

    @Test
    void emojiBecomeNamesOrOldSymbols() {
        assertEquals("lol :skull:", EmojiText.toGame("lol 💀"));
        assertEquals(":joy::sob:", EmojiText.toGame("😂😭"));
        assertEquals("i ❤ you", EmojiText.toGame("i ❤️ you"));
        assertEquals("☀ ✔", EmojiText.toGame("☀️ ✔️"));
        assertEquals(":thumbsup:", EmojiText.toGame("👍🏽"), "skin tones drop to the base emoji");
        assertEquals(":flag_us:", EmojiText.toGame("🇺🇸"));
        assertEquals(":man_woman_girl:", EmojiText.toGame("👨‍👩‍👧"));
        assertEquals("plain text 123 #1 ©", EmojiText.toGame("plain text 123 #1 ©"));
    }

    @Test
    void patchNotesShowTheNewestVersion() {
        var e = Changelog.latest();
        assertNotNull(e);
        assertEquals("0.3.2", e.version());
        assertFalse(e.notes().contains("## "));
        var older = Changelog.find("0.3.1");
        assertNotNull(older);
        assertTrue(older.notes().contains("/verify"));
        assertNull(Changelog.find("9.9.9"));
        var all = Changelog.all();
        assertEquals("0.3.2", all.get(0).version());
        assertEquals("0.1.0", all.get(all.size() - 1).version());
    }

    @Test
    void newDiscordCommands() {
        assertEquals(new BotCommand.Patch(null), BotCommand.parse("!patch").orElseThrow());
        assertEquals(new BotCommand.Patch("0.3.1"), BotCommand.parse("!patch 0.3.1").orElseThrow());
        assertInstanceOf(BotCommand.Invalid.class, BotCommand.parse("!patch latest").orElseThrow());
        assertFalse(BotCommand.requiresAdmin(BotCommand.parse("!patch").orElseThrow()));
        assertTrue(BotCommand.requiresOwner(BotCommand.parse("!links").orElseThrow()));
        var u = BotCommand.parse("!unlink <@123456789012345678>").orElseThrow();
        assertEquals(new BotCommand.LinkRemove("123456789012345678"), u);
        assertTrue(BotCommand.requiresOwner(u));
    }
}
