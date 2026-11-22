package com.guildbridge.core.sb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One-line answers for the profile viewer commands. Everything here is pure: data in, text out. */
public final class PvFormat {
    /** Skills that count toward skill average, in display order. */
    public static final List<String> AVG_SKILLS = List.of("farming", "mining", "combat", "foraging", "fishing",
            "enchanting", "alchemy", "taming", "carpentry");
    static final String API_OFF = "API off";

    private PvFormat() {}

    // ----- skills -----

    /** Level cap for a skill on this profile (farming and taming can be raised). */
    public static int skillCap(SbProfile p, String skill) {
        int cap = Levels.defaultCap(skill);
        if (skill.equals("farming")) cap += (int) p.num0("jacobs_contest", "perks", "farming_level_cap");
        if (skill.equals("taming")) {
            JsonArray sac = p.member() == null ? null : J.arr(p.member(), "pets_data", "pet_care", "pet_types_sacrificed");
            cap += Math.min(10, sac == null ? 0 : sac.size());
        }
        return cap;
    }

    /** Null when the skills API is off for this player. */
    public static Levels.Level skill(SbProfile p, String skill) {
        JsonObject exp = p.obj("player_data", "experience");
        if (exp == null) return null;
        double xp = J.num0(exp, 0, "SKILL_" + skill.toUpperCase(Locale.ROOT));
        return Levels.skill(skill, xp, skillCap(p, skill));
    }

    /** Average of the 9 main skill levels, NaN with skills API off. */
    public static double skillAverage(SbProfile p) {
        if (p.obj("player_data", "experience") == null) return Double.NaN;
        double sum = 0;
        for (String s : AVG_SKILLS) sum += skill(p, s).level();
        return sum / AVG_SKILLS.size();
    }

    public static String skills(SbProfile p) {
        if (p.obj("player_data", "experience") == null) return p.ign() + ": Skills " + API_OFF;
        StringBuilder sb = new StringBuilder(p.ign()).append(": avg ").append(Fmt.dec(skillAverage(p)));
        for (String s : AVG_SKILLS) sb.append(" | ").append(Fmt.levelShort(Fmt.title(s), skill(p, s)));
        return sb.toString();
    }

    public static String skillOne(SbProfile p, String skill) {
        Levels.Level l = skill(p, skill);
        if (l == null) return p.ign() + ": Skills " + API_OFF;
        return p.ign() + ": " + Fmt.level(Fmt.title(skill), l);
    }

    // ----- overview -----

    public static String sb(SbProfile p) {
        List<String> parts = new ArrayList<>();
        String mode = SbProfile.modeName(p.profile());
        parts.add(p.ign() + " (" + p.cuteName() + (mode.isEmpty() ? "" : ", " + mode) + "): SB "
                + Levels.skyblockLevel(p.num0("leveling", "experience")).level());
        double avg = skillAverage(p);
        parts.add("Skill avg " + (Double.isNaN(avg) ? API_OFF : Fmt.dec(avg)));
        parts.add("Cata " + Levels.catacombs(p.num0("dungeons", "dungeon_types", "catacombs", "experience")).level());
        StringBuilder sl = new StringBuilder("Slayers ");
        boolean first = true;
        for (String boss : List.of("zombie", "spider", "wolf", "enderman", "blaze", "vampire")) {
            if (!first) sl.append('/');
            sl.append(Levels.slayer(boss, p.num0("slayer", "slayer_bosses", boss, "xp")).level());
            first = false;
        }
        parts.add(sl.toString());
        return String.join(" | ", parts);
    }

    public static String level(SbProfile p) {
        Levels.Level l = Levels.skyblockLevel(p.num0("leveling", "experience"));
        return p.ign() + ": SkyBlock level " + l.level() + " (" + (int) l.into() + "/100 xp)";
    }

    // ----- dungeons -----

    public static String cata(SbProfile p, String arg) {
        JsonObject d = p.obj("dungeons");
        if (d == null) return p.ign() + ": no dungeon runs yet";
        if (arg == null) {
            Levels.Level c = Levels.catacombs(J.num0(d, 0, "dungeon_types", "catacombs", "experience"));
            StringBuilder sb = new StringBuilder(p.ign()).append(": ").append(Fmt.levelShort("Cata", c)).append(" |");
            for (String cls : List.of("healer", "mage", "berserk", "archer", "tank")) {
                sb.append(' ').append(Fmt.title(cls)).append(' ')
                        .append(Levels.catacombs(J.num0(d, 0, "player_classes", cls, "experience")).level());
            }
            double secrets = J.num0(d, 0, "secrets");
            sb.append(" | ").append(Fmt.num(secrets)).append(" secrets");
            return sb.toString();
        }
        if (arg.startsWith("f") || arg.startsWith("m")) {
            boolean master = arg.startsWith("m");
            String tier = arg.substring(1);
            JsonObject t = J.obj(d, "dungeon_types", master ? "master_catacombs" : "catacombs");
            String label = (master ? "M" : "F") + tier;
            if (master || !tier.equals("0")) label = (master ? "M" : "F") + tier;
            else label = "Entrance";
            double runs = J.num0(t, 0, "tier_completions", tier);
            double s = J.num(t, "fastest_time_s", tier);
            double sp = J.num(t, "fastest_time_s_plus", tier);
            return p.ign() + ": " + label + " " + Fmt.comma(runs) + " runs | PB S " + (Double.isNaN(s) ? "none" : Fmt.time(s))
                    + " | S+ " + (Double.isNaN(sp) ? "none" : Fmt.time(sp));
        }
        Levels.Level l = Levels.catacombs(J.num0(d, 0, "player_classes", arg, "experience"));
        return p.ign() + ": " + Fmt.level(Fmt.title(arg), l);
    }

    // ----- slayer -----

    public static String slayer(SbProfile p, String arg) {
        if (arg == null) {
            StringBuilder sb = new StringBuilder(p.ign()).append(':');
            boolean first = true;
            for (String boss : List.of("zombie", "spider", "wolf", "enderman", "blaze", "vampire")) {
                sb.append(first ? " " : " | ")
                        .append(Fmt.levelShort(Fmt.title(boss), Levels.slayer(boss, p.num0("slayer", "slayer_bosses", boss, "xp"))));
                first = false;
            }
            return sb.toString();
        }
        JsonObject b = p.obj("slayer", "slayer_bosses", arg);
        double kills = 0;
        if (b != null) {
            for (var e : b.entrySet()) {
                if (e.getKey().startsWith("boss_kills_tier_")) kills += e.getValue().getAsDouble();
            }
        }
        Levels.Level l = Levels.slayer(arg, J.num0(b, 0, "xp"));
        return p.ign() + ": " + Fmt.level(Fmt.title(arg), l) + " | " + Fmt.comma(kills) + " boss kills";
    }

    // ----- coins, profiles, misc -----

    public static String bank(SbProfile p) {
        double purse = p.num0("currencies", "coin_purse");
        double bank = J.num(p.profile(), "banking", "balance");
        return p.ign() + ": Purse " + Fmt.num(purse) + " | Bank " + (Double.isNaN(bank) ? API_OFF : Fmt.num(bank));
    }

    public static String profiles(SbProfile p) {
        List<String> names = new ArrayList<>();
        for (JsonObject pr : p.all()) {
            String n = J.str(pr, "cute_name");
            List<String> tags = new ArrayList<>();
            if (J.bool(pr, "selected")) tags.add("active");
            String mode = SbProfile.modeName(pr);
            if (!mode.isEmpty()) tags.add(mode);
            JsonObject members = J.obj(pr, "members");
            if (members != null && members.size() > 1) tags.add("coop x" + members.size());
            names.add(n + (tags.isEmpty() ? "" : " (" + String.join(", ", tags) + ")"));
        }
        return p.ign() + ": " + String.join(", ", names);
    }

    public static String hotm(SbProfile p) {
        double xp = p.num0("skill_tree", "experience", "mining");
        Levels.Level l = Levels.hotm(xp);
        StringBuilder sb = new StringBuilder(p.ign()).append(": ").append(Fmt.level("HOTM", l));
        for (String powder : List.of("mithril", "gemstone", "glacite")) {
            double cur = p.num0("mining_core", "powder_" + powder);
            double spent = p.num0("mining_core", "powder_spent_" + powder);
            sb.append(" | ").append(Fmt.title(powder)).append(' ').append(Fmt.num(cur + spent))
                    .append(" (").append(Fmt.num(cur)).append(" unspent)");
        }
        return sb.toString();
    }

    public static String pet(SbProfile p) {
        return pet(p, id -> null);
    }

    /** @param itemNames item id to display name (Hypixel items list); null when unknown */
    public static String pet(SbProfile p, java.util.function.Function<String, String> itemNames) {
        JsonArray pets = J.arr(p.member(), "pets_data", "pets");
        if (pets == null) return p.ign() + ": no pets";
        for (JsonElement e : pets) {
            if (!J.bool(e, "active")) continue;
            String type = J.str(e, "type");
            String tier = J.str(e, "tier");
            // A Tier Boost raises the shown rarity by one.
            String held = J.str(e, "heldItem");
            if ("PET_ITEM_TIER_BOOST".equals(held)) tier = nextTier(tier);
            Levels.Level l = Levels.pet(type, tier, J.num0(e, 0, "exp"));
            String line = p.ign() + ": " + Fmt.pet(tier, petName(type), l);
            if (held != null) {
                String hn = itemNames.apply(held);
                line += " | " + (hn != null ? hn : petItemName(held));
            }
            return line;
        }
        return p.ign() + ": no active pet";
    }

    static String nextTier(String tier) {
        List<String> order = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
        int i = order.indexOf(tier);
        return i < 0 || i == order.size() - 1 ? tier : order.get(i + 1);
    }

    static String petName(String type) {
        if ("TYRANNOSAURUS".equals(type)) return "T-Rex";
        return Fmt.title(type);
    }

    /** PET_ITEM_ALL_SKILLS_BOOST_COMMON -> All Skills Boost */
    static String petItemName(String id) {
        String s = id.replaceFirst("^PET_ITEM_", "").replaceFirst("_(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC)$", "");
        return Fmt.title(s);
    }

    public static String essence(SbProfile p) {
        StringBuilder sb = new StringBuilder(p.ign()).append(':');
        boolean first = true;
        for (String t : List.of("WITHER", "DRAGON", "UNDEAD", "DIAMOND", "GOLD", "ICE", "SPIDER", "CRIMSON")) {
            sb.append(first ? " " : " | ").append(Fmt.title(t)).append(' ')
                    .append(Fmt.num(p.num0("currencies", "essence", t, "current")));
            first = false;
        }
        return sb.toString();
    }

    public static String crimson(SbProfile p) {
        JsonObject n = p.obj("nether_island_player_data");
        if (n == null) return p.ign() + ": hasn't visited the Crimson Isle";
        String faction = J.str(n, "selected_faction");
        StringBuilder sb = new StringBuilder(p.ign()).append(": ");
        sb.append("Mage ").append(Fmt.num(J.num0(n, 0, "mages_reputation"))).append(" rep");
        if ("mages".equals(faction)) sb.append(" (main)");
        sb.append(" | Barbarian ").append(Fmt.num(J.num0(n, 0, "barbarians_reputation"))).append(" rep");
        if ("barbarians".equals(faction)) sb.append(" (main)");
        sb.append(" | Kuudra");
        Map<String, String> tiers = new LinkedHashMap<>();
        tiers.put("none", "Basic");
        tiers.put("hot", "Hot");
        tiers.put("burning", "Burning");
        tiers.put("fiery", "Fiery");
        tiers.put("infernal", "Infernal");
        boolean first = true;
        for (var t : tiers.entrySet()) {
            sb.append(first ? " " : ", ").append(t.getValue()).append(' ')
                    .append(Fmt.comma(J.num0(n, 0, "kuudra_completed_tiers", t.getKey())));
            first = false;
        }
        return sb.toString();
    }

    /** Unique minion crafts needed for each slot count (SkyCrypt constants, MIT). */
    static final int[][] MINION_SLOTS = {{0, 5}, {5, 6}, {15, 7}, {30, 8}, {50, 9}, {75, 10}, {100, 11}, {125, 12},
        {150, 13}, {175, 14}, {200, 15}, {225, 16}, {250, 17}, {275, 18}, {300, 19}, {350, 20}, {400, 21},
        {450, 22}, {500, 23}, {550, 24}, {600, 25}, {650, 26}, {700, 27}};

    public static String minions(SbProfile p) {
        // Crafted minions are shared across a coop, so count every member's unique crafts.
        java.util.Set<String> crafted = new java.util.HashSet<>();
        JsonObject members = J.obj(p.profile(), "members");
        if (members != null) {
            for (var m : members.entrySet()) {
                JsonArray a = J.arr(m.getValue(), "player_data", "crafted_generators");
                if (a != null) a.forEach(x -> crafted.add(x.getAsString()));
            }
        }
        int unique = crafted.size();
        int slots = 5, next = -1;
        for (int[] row : MINION_SLOTS) {
            if (unique >= row[0]) slots = row[1];
            else if (next < 0) next = row[0];
        }
        int community = 0;
        JsonArray ups = J.arr(p.profile(), "community_upgrades", "upgrade_states");
        if (ups != null) {
            for (JsonElement u : ups) {
                if ("minion_slots".equals(J.str(u, "upgrade"))) community = Math.max(community, (int) J.num0(u, 0, "tier"));
            }
        }
        String line = p.ign() + ": " + Fmt.comma(unique) + " unique crafts | " + (slots + community) + " slots";
        if (community > 0) line += " (" + slots + " + " + community + " community)";
        if (next > 0) line += " | " + (next - unique) + " more for the next slot";
        return line;
    }

    public static String fairy(SbProfile p) {
        double total = p.num0("fairy_soul", "total_collected");
        double unspent = p.num0("fairy_soul", "unspent_souls");
        return p.ign() + ": " + Fmt.comma(total) + " fairy souls" + (unspent > 0 ? " | " + Fmt.comma(unspent) + " unspent" : "");
    }

    // ----- garden and farming -----

    /** Garden API names for each milestone crop. */
    public static final Map<String, String> CROP_API = new LinkedHashMap<>();
    static {
        CROP_API.put("WHEAT", "WHEAT");
        CROP_API.put("CARROT", "CARROT_ITEM");
        CROP_API.put("POTATO", "POTATO_ITEM");
        CROP_API.put("MELON", "MELON");
        CROP_API.put("PUMPKIN", "PUMPKIN");
        CROP_API.put("SUGAR_CANE", "SUGAR_CANE");
        CROP_API.put("COCOA_BEANS", "INK_SACK:3");
        CROP_API.put("CACTUS", "CACTUS");
        CROP_API.put("MUSHROOM", "MUSHROOM_COLLECTION");
        CROP_API.put("NETHER_WART", "NETHER_STALK");
        CROP_API.put("SUNFLOWER", "DOUBLE_PLANT");
        CROP_API.put("MOONFLOWER", "MOONFLOWER");
        CROP_API.put("WILD_ROSE", "WILD_ROSE");
    }

    static String cropName(String milestoneCrop) {
        return switch (milestoneCrop) {
            case "SUGAR_CANE" -> "Cane";
            case "COCOA_BEANS" -> "Cocoa";
            case "NETHER_WART" -> "Wart";
            case "WILD_ROSE" -> "Rose";
            default -> Fmt.title(milestoneCrop);
        };
    }

    static String cropNameFromApi(String apiId) {
        for (var e : CROP_API.entrySet()) {
            if (e.getValue().equals(apiId)) return cropName(e.getKey());
        }
        return Fmt.title(apiId);
    }

    public static String garden(SbProfile p, JsonObject garden, String crop) {
        JsonObject g = garden == null ? null : J.obj(garden, "garden");
        if (g == null) return p.ign() + ": no garden yet";
        if (crop != null) {
            double collected = J.num0(g, 0, "resources_collected", CROP_API.get(crop));
            Levels.Level l = Levels.cropMilestone(crop, collected);
            double upgrade = J.num0(g, 0, "crop_upgrade_levels", CROP_API.get(crop));
            return p.ign() + ": " + Fmt.level(cropName(crop) + " milestone", l) + " | " + Fmt.num(collected)
                    + " collected | Upgrade " + (int) upgrade + "/9";
        }
        Levels.Level l = Levels.garden(J.num0(g, 0, "garden_experience"));
        JsonArray plots = J.arr(g, "unlocked_plots_ids");
        double served = J.num0(g, 0, "commission_data", "total_completed");
        double unique = J.num0(g, 0, "commission_data", "unique_npcs_served");
        double copper = p.num0("garden_player_data", "copper");
        return p.ign() + ": " + Fmt.level("Garden", l) + " | Plots " + (plots == null ? 0 : plots.size()) + "/24 | Visitors "
                + Fmt.comma(served) + " served (" + Fmt.comma(unique) + " unique) | Copper " + Fmt.comma(copper);
    }

    public static String jacob(SbProfile p) {
        JsonObject jc = p.obj("jacobs_contest");
        if (jc == null) return p.ign() + ": hasn't done any Jacob's contests";
        JsonElement contests = J.at(jc, "contests");
        int count = contests == null ? 0 : contests.isJsonObject() ? contests.getAsJsonObject().size()
                : contests.isJsonPrimitive() ? contests.getAsInt() : 0;
        return p.ign() + ": " + Fmt.comma(count) + " contests | Perks: Double Drops "
                + (int) J.num0(jc, 0, "perks", "double_drops") + "/15, Farming Cap +"
                + (int) J.num0(jc, 0, "perks", "farming_level_cap") + "/10 | " + medalCounts(jc);
    }

    static String medalCounts(JsonObject jc) {
        return "Medals Gold " + (int) J.num0(jc, 0, "medals_inv", "gold") + ", Silver "
                + (int) J.num0(jc, 0, "medals_inv", "silver") + ", Bronze " + (int) J.num0(jc, 0, "medals_inv", "bronze");
    }

    static final List<String> BRACKETS = List.of("bronze", "silver", "gold", "platinum", "diamond");

    public static String medals(SbProfile p) {
        JsonObject jc = p.obj("jacobs_contest");
        if (jc == null) return p.ign() + ": hasn't done any Jacob's contests";
        Map<String, Integer> best = new LinkedHashMap<>();
        JsonObject ub = J.obj(jc, "unique_brackets");
        if (ub != null) {
            for (int i = 0; i < BRACKETS.size(); i++) {
                JsonArray crops = J.arr(ub, BRACKETS.get(i));
                if (crops == null) continue;
                for (JsonElement c : crops) best.merge(c.getAsString(), i, Math::max);
            }
        }
        StringBuilder sb = new StringBuilder(p.ign()).append(": ").append(medalCounts(jc));
        if (!best.isEmpty()) {
            sb.append(" | Best:");
            boolean first = true;
            for (String apiId : CROP_API.values()) {
                Integer b = best.get(apiId);
                if (b == null) continue;
                sb.append(first ? " " : ", ").append(cropNameFromApi(apiId)).append(' ')
                        .append(Character.toUpperCase(BRACKETS.get(b).charAt(0)));
                first = false;
            }
        }
        return sb.toString();
    }

    public static String pests(SbProfile p) {
        JsonObject kills = p.obj("bestiary", "kills");
        if (kills == null) return p.ign() + ": no pest kills";
        Map<String, Double> byPest = new LinkedHashMap<>();
        for (var e : kills.entrySet()) {
            if (!e.getKey().startsWith("pest_")) continue;
            String name = e.getKey().replaceFirst("^pest_", "").replaceFirst("_\\d+$", "");
            byPest.merge(name, e.getValue().getAsDouble(), Double::sum);
        }
        if (byPest.isEmpty()) return p.ign() + ": no pest kills";
        double total = byPest.values().stream().mapToDouble(Double::doubleValue).sum();
        List<Map.Entry<String, Double>> top = new ArrayList<>(byPest.entrySet());
        top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder(p.ign()).append(": ").append(Fmt.comma(total)).append(" pests killed | Most:");
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            sb.append(i == 0 ? " " : ", ").append(Fmt.title(top.get(i).getKey())).append(' ')
                    .append(Fmt.comma(top.get(i).getValue()));
        }
        return sb.toString();
    }

    // ----- collections -----

    /**
     * @param collections the /v2/resources/skyblock/collections response
     * @param itemId      collection item id (from {@link #findCollection})
     */
    public static String collection(SbProfile p, JsonObject collections, String itemId) {
        JsonObject item = collectionItem(collections, itemId);
        String name = item == null ? Fmt.title(itemId) : J.str(item, "name");
        if (p.obj("collection") == null) return p.ign() + ": Collections " + API_OFF;
        // Each coop member's collection only counts what they collected; the coop total is the sum.
        double amount = coopCollection(p, itemId);
        JsonArray tiers = item == null ? null : J.arr(item, "tiers");
        if (tiers == null || tiers.isEmpty()) return p.ign() + ": " + name + " collection " + Fmt.num(amount);
        int tier = 0;
        double nextAt = -1;
        for (JsonElement t : tiers) {
            double need = J.num0(t, 0, "amountRequired");
            if (amount >= need) tier = (int) J.num0(t, 0, "tier");
            else if (nextAt < 0) nextAt = need;
        }
        String line = p.ign() + ": " + name + " collection " + roman(tier) + " " + Fmt.num(amount);
        if (nextAt > 0) {
            line += " (" + Fmt.pct(amount / nextAt) + " to " + roman(tier + 1) + " at " + Fmt.num(nextAt) + ")";
        } else {
            line += " MAX";
        }
        return line;
    }

    static double coopCollection(SbProfile p, String itemId) {
        double total = 0;
        JsonObject members = J.obj(p.profile(), "members");
        if (members == null) return p.num0("collection", itemId);
        for (var m : members.entrySet()) total += J.num0(m.getValue(), 0, "collection", itemId);
        return total;
    }

    static JsonObject collectionItem(JsonObject collections, String itemId) {
        JsonObject cats = J.obj(collections, "collections");
        if (cats == null) return null;
        for (var c : cats.entrySet()) {
            JsonObject it = J.obj(c.getValue(), "items", itemId);
            if (it != null) return it;
        }
        return null;
    }

    /** Finds a collection by name ("enchanted diamond" no, "diamond" yes, "wart" -> Nether Wart). */
    public static String findCollection(JsonObject collections, String query) {
        JsonObject cats = J.obj(collections, "collections");
        if (cats == null || query == null) return null;
        String q = norm(query);
        String partial = null;
        for (var c : cats.entrySet()) {
            JsonObject items = J.obj(c.getValue(), "items");
            if (items == null) continue;
            for (var it : items.entrySet()) {
                String name = norm(J.str(it.getValue(), "name"));
                if (name.equals(q) || norm(it.getKey()).equals(q)) return it.getKey();
                if (partial == null && name.contains(q) && q.length() >= 3) partial = it.getKey();
            }
        }
        return partial;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String roman(int n) {
        if (n <= 0) return "0";
        String[] r = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                n -= v[i];
                sb.append(r[i]);
            }
        }
        return sb.toString();
    }

    // ----- inputs for weight and networth (calculated by the Node helper) -----

    /** Skill xp in the order Lily weight expects. */
    public static final List<String> LILY_SKILLS = List.of("enchanting", "taming", "alchemy", "mining", "farming",
            "foraging", "combat", "fishing");
}
