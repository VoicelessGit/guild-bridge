package com.guildbridge.core.sb;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.guildbridge.core.OutgoingSanitizer;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Profile viewer answers, checked against a real profile (Justin's, trimmed to the fields the
 * commands read, Nov 2026).
 */
class PvTest {
    private static final String UUID = "00000000000000000000000000000001";

    private static JsonObject res(String name) {
        return JsonParser.parseReader(new InputStreamReader(
                PvTest.class.getResourceAsStream("/sb/" + name), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static SbProfile pear() {
        return SbProfile.pick("PlayerOne", UUID, res("profiles-sample.json"), null);
    }

    @Test
    void picksSelectedOrNamedProfile() {
        assertEquals("Pear", pear().cuteName());
        assertEquals("Peach", SbProfile.pick("PlayerOne", UUID, res("profiles-sample.json"), "peach").cuteName());
        var e = assertThrows(SbProfile.NotFound.class,
                () -> SbProfile.pick("PlayerOne", UUID, res("profiles-sample.json"), "banana"));
        assertEquals("PlayerOne has no profile named Banana.", e.getMessage());
        JsonObject none = JsonParser.parseString("{\"success\":true,\"profiles\":null}").getAsJsonObject();
        assertThrows(SbProfile.NotFound.class, () -> SbProfile.pick("Steve", "x", none, null));
    }

    @Test
    void overviewAndSkills() {
        SbProfile p = pear();
        assertEquals("PlayerOne (Pear): SB 369 | Skill avg 45.6 | Cata 45 | Slayers 9/9/9/9/4/5", PvFormat.sb(p));
        assertEquals("PlayerOne: SkyBlock level 369 (59/100 xp)", PvFormat.level(p));
        assertEquals("PlayerOne: avg 45.6 | Farming 53 (61%) | Mining 60 | Combat 60 | Foraging 22 (14%) | "
                + "Fishing 32 (98%) | Enchanting 60 | Alchemy 50 | Taming 60 | Carpentry 39 (17%) | Hunting 20 (43%)", PvFormat.skills(p));
        assertEquals("PlayerOne: Farming 53 3.17M/5.2M (61% to 54)", PvFormat.skillOne(p, "farming"));
        assertEquals("PlayerOne: Mining 60 MAX +586k overflow", PvFormat.skillOne(p, "mining"));
    }

    @Test
    void skillCapsCountJacobPerksAndPetSacrifices() {
        SbProfile p = pear();
        assertEquals(59, PvFormat.skillCap(p, "farming")); // 50 + 9 from Jacob's perk
        assertEquals(60, PvFormat.skillCap(p, "taming"));  // 50 + 10 pets given to George
        assertEquals(60, PvFormat.skillCap(p, "mining"));
    }

    @Test
    void dungeonsAndSlayers() {
        SbProfile p = pear();
        assertEquals("PlayerOne: Cata 45 (45%) | Healer 39 Mage 38 Berserk 39 Archer 42 Tank 38 | 23.3k secrets",
                PvFormat.cata(p, null));
        assertEquals("PlayerOne: F7 532 runs | PB S 5:43 | S+ 5:08", PvFormat.cata(p, "f7"));
        assertEquals("PlayerOne: M5 577 runs | PB S 2:38 | S+ 1:58", PvFormat.cata(p, "m5"));
        assertEquals("PlayerOne: Entrance 9 runs | PB S none | S+ none", PvFormat.cata(p, "f0"));
        assertEquals("PlayerOne: Archer 42 10.3M/24M (43% to 43)", PvFormat.cata(p, "archer"));
        assertEquals("PlayerOne: Zombie 9 | Spider 9 | Wolf 9 | Enderman 9 | Blaze 4 (1%) | Vampire 5",
                PvFormat.slayer(p, null));
        assertEquals("PlayerOne: Blaze 4 68/3.5k (1% to 5) | 17 boss kills", PvFormat.slayer(p, "blaze"));
    }

    @Test
    void coinsProfilesHotmPetEssenceCrimsonMinionsFairy() {
        SbProfile p = pear();
        assertEquals("PlayerOne: Purse 20.3M | Bank 1B", PvFormat.bank(p));
        assertEquals("PlayerOne: Peach (coop x4), Pear (active)", PvFormat.profiles(p));
        assertEquals("PlayerOne: HOTM 10 MAX | Mithril 9.47M (4.75M unspent) | Gemstone 18.6M (9.45M unspent) "
                + "| Glacite 205k (205k unspent)", PvFormat.hotm(p));
        assertEquals("PlayerOne: [Lvl 100] Mythic Guardian MAX | All Skills Boost", PvFormat.pet(p));
        assertEquals("PlayerOne: Wither 2 | Dragon 207 | Undead 187 | Diamond 61 | Gold 9 | Ice 10 | Spider 40 | Crimson 0",
                PvFormat.essence(p));
        assertEquals("PlayerOne: Mage 1.81k rep (main) | Barbarian 12k rep | Kuudra Basic 110, Hot 5, Burning 11, "
                + "Fiery 1, Infernal 0", PvFormat.crimson(p));
        assertEquals("PlayerOne: 616 unique crafts | 30 slots (25 + 5 community) | 34 more for the next slot",
                PvFormat.minions(p));
        assertEquals("PlayerOne: 264 fairy souls | 4 unspent", PvFormat.fairy(p));
    }

    @Test
    void gardenAndFarming() {
        SbProfile p = pear();
        JsonObject g = res("garden-sample.json");
        assertEquals("PlayerOne: Garden 15 MAX +2.47k overflow | Plots 24/24 | Visitors 1,272 served (111 unique) "
                + "| Copper 200", PvFormat.garden(p, g, null));
        assertEquals("PlayerOne: Wheat milestone 21 354k/400k (88% to 22) | 1.47M collected | Upgrade 9/9",
                PvFormat.garden(p, g, "WHEAT"));
        assertEquals("PlayerOne: 149 contests | Perks: Double Drops 1/15, Farming Cap +9/10 | Medals Gold 0, "
                + "Silver 2, Bronze 0", PvFormat.jacob(p));
        String medals = PvFormat.medals(p);
        assertTrue(medals.startsWith("PlayerOne: Medals Gold 0, Silver 2, Bronze 0 | Best: Wheat G, Carrot G, Potato P"),
                medals);
        assertEquals("PlayerOne: 876 pests killed | Most: Mosquito 92, Locust 87, Mite 85", PvFormat.pests(p));
    }

    @Test
    void everyAnswerFitsInOneChatLineUntouched() {
        SbProfile p = pear();
        JsonObject g = res("garden-sample.json");
        List<String> lines = List.of(PvFormat.sb(p), PvFormat.skills(p), PvFormat.cata(p, null), PvFormat.hotm(p),
                PvFormat.medals(p), PvFormat.garden(p, g, null), PvFormat.crimson(p), PvFormat.essence(p),
                PvFormat.slayer(p, null), PvFormat.minions(p));
        for (String line : lines) {
            // The link filter and length limit must not change any answer.
            assertEquals("gc " + line, OutgoingSanitizer.toGuildCommand("", line, null, ""), line);
        }
    }

    @Test
    void parserReadsArgUserAndProfileInAnyOrder() {
        var r = PvCommands.parse("!skill mining Steve apple", q -> false);
        assertEquals("mining", r.arg());
        assertEquals("Steve", r.user());
        assertEquals("apple", r.profile());
        r = PvCommands.parse("!skill Steve farm", q -> false);
        assertEquals("farming", r.arg());
        assertEquals("Steve", r.user());
        r = PvCommands.parse("!sb Peach", q -> false);
        assertNull(r.user());
        assertEquals("peach", r.profile());
        r = PvCommands.parse("!kuudra", q -> false);
        assertEquals("crimson", r.spec().name());
        r = PvCommands.parse("!cata m7", q -> false);
        assertEquals("m7", r.arg());
        r = PvCommands.parse("!slayer sven Steve", q -> false);
        assertEquals("wolf", r.arg());
        r = PvCommands.parse("!garden wart", q -> false);
        assertEquals("NETHER_WART", r.arg());
    }

    @Test
    void parserGivesUsageHints() {
        assertTrue(PvCommands.parse("!skill", q -> false).error().startsWith("Usage: !skill <skill> [user] [profile] (skills: farming,"));
        assertNotNull(PvCommands.parse("!skill nope", q -> false).error());
        assertNotNull(PvCommands.parse("!sb a b c", q -> false).error());
        assertEquals("Usage: !bz <item>", PvCommands.parse("!bz", q -> false).error());
        assertNotNull(PvCommands.parse("!sb not-a-name!", q -> false).error());
    }

    @Test
    void itemCommandsSplitOffATrailingPlayerName() {
        var known = java.util.Set.of("diamond", "nether wart");
        var r = PvCommands.parse("!coll nether wart Steve", known::contains);
        assertEquals("nether wart", r.arg());
        assertEquals("Steve", r.user());
        r = PvCommands.parse("!coll nether wart", known::contains);
        assertEquals("nether wart", r.arg());
        assertNull(r.user());
        r = PvCommands.parse("!coll diamond Steve apple", known::contains);
        assertEquals("diamond", r.arg());
        assertEquals("Steve", r.user());
        assertEquals("apple", r.profile());
        r = PvCommands.parse("!bz enchanted diamond", q -> false);
        assertEquals("enchanted diamond", r.arg());
    }

    @Test
    void onlyOurCommandsCount() {
        assertTrue(PvCommands.isCommand("!sb"));
        assertEquals("version", PvCommands.find("versions").orElseThrow().name());
        assertTrue(PvCommands.isCommand("  !KUUDRA Steve"));
        assertFalse(PvCommands.isCommand("!g online")); // a Discord command
        assertFalse(PvCommands.isCommand("hey !sb"));
        assertFalse(PvCommands.isCommand("!"));
    }

    @Test
    void levelTables() {
        assertEquals(60, Levels.skill("mining", 111_672_425, 60).level());
        assertTrue(Levels.skill("mining", 111_672_425, 60).maxed());
        assertEquals(50, Levels.skill("farming", 55_172_425, 50).level());
        assertEquals(1, Levels.hotm(0).level());
        assertEquals(10, Levels.hotm(1_247_000).level());
        assertEquals(2, Levels.hotm(3_000).level());
        assertEquals(9, Levels.slayer("wolf", 1_000_000).level());
        assertEquals(5, Levels.slayer("vampire", 2_400).level());
        assertEquals(100, Levels.pet("GUARDIAN", "MYTHIC", 1e10).level());
        assertEquals(200, Levels.pet("GOLDEN_DRAGON", "LEGENDARY", 1e12).level());
        assertEquals(1, Levels.pet("BEE", "COMMON", 0).level());
        assertEquals(2, Levels.pet("BEE", "COMMON", 100).level());
    }

    @Test
    void numberFormats() {
        assertEquals("950", Fmt.num(950));
        assertEquals("12.4k", Fmt.num(12_400));
        assertEquals("1.1M", Fmt.num(1_100_000));
        assertEquals("1B", Fmt.num(1_000_000_000));
        assertEquals("812k", Fmt.num(812_345));
        assertEquals("5:08", Fmt.time(308_473));
        assertEquals("99%", Fmt.pct(0.999));
    }

    @Test
    void bazaarAndAuction() {
        JsonObject items = JsonParser.parseString("{\"items\":[{\"id\":\"ENCHANTED_DIAMOND\",\"name\":\"Enchanted Diamond\"},"
                + "{\"id\":\"DIAMOND\",\"name\":\"Diamond\"},{\"id\":\"HYPERION\",\"name\":\"Hyperion\"}]}").getAsJsonObject();
        var idx = new MarketFormat.ItemIndex(items);
        JsonObject products = JsonParser.parseString("{\"ENCHANTED_DIAMOND\":{\"sell_summary\":[{\"pricePerUnit\":1290.4}],"
                + "\"buy_summary\":[{\"pricePerUnit\":1310.9}],\"quick_status\":{\"buyMovingWeek\":2100000,\"sellMovingWeek\":1900000}},"
                + "\"DIAMOND\":{\"sell_summary\":[{\"pricePerUnit\":8.1}],\"buy_summary\":[{\"pricePerUnit\":8.9}],\"quick_status\":{}}}")
                .getAsJsonObject();
        assertEquals("Enchanted Diamond: Insta-buy 1,310.9 | Insta-sell 1,290.4 | Buy order 1,290.5 | Sell offer 1,310.8 "
                + "| Weekly 2.1M bought, 1.9M sold", MarketFormat.bazaar(products, idx, "enchanted diamond"));
        assertTrue(MarketFormat.bazaar(products, idx, "diamond").startsWith("Diamond: "), "shortest name wins");
        assertEquals("No bazaar item matches \"zzz\".", MarketFormat.bazaar(products, idx, "zzz"));

        Map<String, Double> prices = new HashMap<>();
        prices.put("HYPERION", 450_000_000.0);
        prices.put("ENCHANTMENT_SHARPNESS_7", 132_000_000.0);
        prices.put("LVL_1_LEGENDARY_GOLDEN_DRAGON", 617_000_000.0);
        prices.put("LVL_100_LEGENDARY_GOLDEN_DRAGON", 652_000_000.0);
        prices.put("LVL_100_EPIC_GOLDEN_DRAGON", 1.0);
        assertEquals("Hyperion: lowest BIN 450M", MarketFormat.auction(prices, idx, "hyperion"));
        assertEquals("Sharpness 7: lowest BIN 132M", MarketFormat.auction(prices, idx, "sharpness 7"));
        assertEquals("Legendary Golden Dragon: lowest BIN Lvl 1 617M | Lvl 100 652M", MarketFormat.auction(prices, idx, "golden dragon"));
    }

    @Test
    void mayorAndFiresale() {
        JsonObject e = JsonParser.parseString("{\"mayor\":{\"name\":\"Marina\",\"perks\":[{\"name\":\"Fishing Festival\"}],"
                + "\"minister\":{\"name\":\"Paul\",\"perk\":{\"name\":\"EZPZ\"}}}}").getAsJsonObject();
        assertEquals("Mayor: Marina (Fishing Festival) | Minister: Paul (EZPZ)", MarketFormat.mayor(e, 0));
        assertEquals("Fire sale: none scheduled", MarketFormat.firesales(new JsonObject(), id -> id, 0));
        // The election closes once per SkyBlock year (124 hours).
        long now = 1_795_000_000_000L;
        long close = MarketFormat.nextElectionClose(now);
        assertTrue(close > now && close - now <= MarketFormat.SB_YEAR);
    }
}
