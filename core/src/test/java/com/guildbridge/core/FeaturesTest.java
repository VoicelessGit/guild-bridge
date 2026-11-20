package com.guildbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeaturesTest {

    private static ProfanityFilter filter(String list, int threshold) throws Exception {
        return new ProfanityFilter(new StringReader(list), threshold);
    }

    // --- Profanity filter ---

    @Test
    void censorsMidAndHighButNotMild() throws Exception {
        var f = filter("damn 1\nshit 2\nbadslur 3", 2);
        assertEquals("damn this ****", f.censor("damn this shit"));
        assertEquals("you *******", f.censor("you badslur"));
    }

    @Test
    void catchesCapsLeetStretchAndSuffixes() throws Exception {
        var f = filter("shit 2\nfuck 2", 2);
        assertEquals("****", f.censor("SHIT"));
        assertEquals("****", f.censor("$h1t"));
        assertEquals("*******", f.censor("shiiiit"));
        assertEquals("*******", f.censor("fucking"));
        assertEquals("*****", f.censor("shits"));
    }

    @Test
    void wholeWordsOnly() throws Exception {
        var f = filter("ass 2\nhell 2", 2);
        assertEquals("class assassin shellfish hello", f.censor("class assassin shellfish hello"));
        assertEquals("what the ****", f.censor("what the hell"));
    }

    @Test
    void bundledListLoadsAndSkipsMildWords() {
        var f = ProfanityFilter.bundled(2);
        assertFalse(f.isEmpty());
        assertEquals("damn **** happens", f.censor("damn shit happens"));
    }

    // --- Duplicate guard ---

    @Test
    void blocksRepeatWithinLastThree() {
        var g = new DuplicateGuard(3);
        assertTrue(g.tryRecord("gc hi"));
        assertTrue(g.tryRecord("gc a"));
        assertTrue(g.tryRecord("gc b"));
        assertFalse(g.tryRecord("gc hi"));     // still in the last 3
        assertFalse(g.tryRecord("GC  Hi "));   // spacing and caps do not get around it
        assertTrue(g.tryRecord("gc c"));       // pushes "gc hi" out
        assertTrue(g.tryRecord("gc hi"));      // allowed again
    }

    // --- Welcome ---

    @Test
    void welcomesOnlyOnFirstJoinEvenAfterRestart() throws Exception {
        Path file = Files.createTempDirectory("gb").resolve("welcomed.txt");
        var t = new WelcomeTracker(file);
        assertTrue(t.firstTime("NewGuy"));
        assertFalse(t.firstTime("newguy"));
        assertFalse(new WelcomeTracker(file).firstTime("NewGuy")); // survives a restart
    }

    @Test
    void welcomeCommandFillsName() {
        assertEquals("gc Welcome to the guild, NewGuy!",
                OutgoingSanitizer.welcomeCommand("Welcome to the guild, {name}!", "NewGuy"));
    }

    @Test
    void outgoingCensorsDiscordText() throws Exception {
        var f = filter("shit 2", 2);
        assertEquals("gc Justin: oh ****", OutgoingSanitizer.toGuildCommand("Justin", "oh shit", f));
    }

    // --- Announcements (formats unverified, see ChatRouter) ---

    private static final String SEP = "-----------------------------------------------------";

    @Test
    void joinAnnouncementAsOneMessage() {
        var events = new ChatRouter().accept(SEP + "\n[VIP] NewGuy joined the guild!\n" + SEP);
        var a = assertInstanceOf(BridgeEvent.Announcement.class, single(events));
        assertEquals("NewGuy", a.joinedMember());
        assertEquals("[VIP] NewGuy joined the guild!", a.text());
    }

    @Test
    void announcementSplitAcrossMessages() {
        var r = new ChatRouter();
        assertTrue(r.accept(SEP).isEmpty());
        assertTrue(r.accept("The Guild has reached Level 50!").isEmpty());
        var a = assertInstanceOf(BridgeEvent.Announcement.class, single(r.accept(SEP)));
        assertNull(a.joinedMember());
    }

    @Test
    void nonGuildBlocksAndInvitesAreIgnored() {
        var r = new ChatRouter();
        assertTrue(r.accept(SEP + "\n[MVP+] Bob has invited you to join their party!\n" + SEP).isEmpty());
        assertTrue(r.accept(SEP + "\nBob has invited you to join their guild, Cool Guild!\n" + SEP).isEmpty());
    }

    @Test
    void guildChatStillRoutesOutsideBlocks() {
        var events = new ChatRouter().accept("Guild > BridgeBot: hey!");
        assertInstanceOf(BridgeEvent.Chat.class, single(events));
    }

    @Test
    void announcementWebhookIsCensoredAndBold() throws Exception {
        var a = new BridgeEvent.Announcement("Guild MOTD: shit happens", null);
        var json = JsonParser.parseString(WebhookPayload.forEvent(a, filter("shit 2", 2))).getAsJsonObject();
        assertEquals("**Guild MOTD: \\*\\*\\*\\* happens**", json.get("content").getAsString());
        assertTrue(new LoopGuard("BridgeBot").shouldRelay(a));
    }

    @Test
    void chatWebhookIsCensored() throws Exception {
        var e = ChatParser.parse("Guild > BridgeBot: fuck yeah").orElseThrow();
        var json = JsonParser.parseString(WebhookPayload.forEvent(e, filter("fuck 2", 2))).getAsJsonObject();
        assertEquals("\\*\\*\\*\\* yeah", json.get("content").getAsString());
    }

    // --- Real samples from the joining account's side, screenshot Nov 2026 ---

    @Test
    void realInviteToBridgeAccountIsIgnored() {
        var r = new ChatRouter();
        assertTrue(r.accept(SEP + "\n[VIP+] ExamplePlayer has invited you to join their guild, testing testing 123!\n"
                + "Click here to accept or type /guild accept ExamplePlayer!\n" + SEP).isEmpty());
    }

    @Test
    void realBridgeJoinedGuildIsIgnored() {
        assertTrue(new ChatRouter().accept(SEP + "\nYou joined testing testing 123!\n" + SEP).isEmpty());
    }

    @Test
    void realBridgeKickedBecomesStatusNotice() {
        var events = new ChatRouter().accept(
                SEP + "\nYou were kicked from the guild by [VIP+] ExamplePlayer for reason 'test'.\n" + SEP);
        var a = assertInstanceOf(BridgeEvent.Announcement.class, single(events));
        assertEquals("The bridge account was kicked from the guild.", a.text());
        assertNull(a.joinedMember());
    }

    @Test
    void realLobbyJoinBetweenBlocksIsIgnored() {
        var r = new ChatRouter();
        assertTrue(r.accept(SEP + "\nYou joined testing testing 123!\n" + SEP).isEmpty());
        assertTrue(r.accept("[MVP+] Luckiy joined the lobby!").isEmpty());
        assertTrue(r.accept("[MVP+] wiwouu joined the lobby!").isEmpty());
    }

    // --- Real samples from a member who stays in the guild, screenshot Nov 2026 ---

    @Test
    void realMemberJoinedGuildTriggersWelcome() {
        var a = assertInstanceOf(BridgeEvent.Announcement.class,
                single(new ChatRouter().accept(SEP + "\nBridgeBot joined the guild!\n" + SEP)));
        assertEquals("BridgeBot", a.joinedMember());
        assertEquals("BridgeBot joined the guild!", a.text());
    }

    @Test
    void realMemberKickedIsRelayedWithoutWelcome() {
        var a = assertInstanceOf(BridgeEvent.Announcement.class, single(new ChatRouter().accept(
                SEP + "\nBridgeBot was kicked from the guild by [VIP+] ExamplePlayer!\n" + SEP)));
        assertEquals("BridgeBot was kicked from the guild by [VIP+] ExamplePlayer!", a.text());
        assertNull(a.joinedMember());
    }

    @Test
    void realInviteConfirmationIsIgnored() {
        assertTrue(new ChatRouter().accept(SEP
                + "\nYou invited BridgeBot to your guild. They have 5 minutes to accept.\n" + SEP).isEmpty());
    }

    private static BridgeEvent single(List<BridgeEvent> events) {
        assertEquals(1, events.size(), "events: " + events);
        return events.get(0);
    }
}
