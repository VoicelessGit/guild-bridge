package com.guildbridge.core.sb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * SkyBlock level math. Tables come from the NotEnoughUpdates repo (MIT, see THIRD_PARTY_LICENSES.txt),
 * bundled under guildbridge/sb/.
 */
public final class Levels {
    /** Where a player stands in something that levels up. */
    public record Level(int level, double into, double needed, boolean maxed, double overflow, double total) {
        /** Progress to the next level, 0 to 1. */
        public double progress() {
            return maxed || needed <= 0 ? 1 : Math.min(1, into / needed);
        }
    }

    private static final JsonObject LEVELING = load("leveling.json");
    private static final JsonObject GARDEN = load("garden.json");
    private static final JsonObject PETS = load("pets.json");

    private static final double[] SKILL = doubles(LEVELING.getAsJsonArray("leveling_xp"));
    private static final double[] RUNECRAFTING = doubles(LEVELING.getAsJsonArray("runecrafting_xp"));
    private static final double[] SOCIAL = doubles(LEVELING.getAsJsonArray("social"));
    private static final double[] CATACOMBS = doubles(LEVELING.getAsJsonArray("catacombs"));
    private static final double[] HOTM = doubles(LEVELING.getAsJsonArray("HOTM"));
    private static final double[] GARDEN_XP = doubles(GARDEN.getAsJsonArray("garden_exp"));
    private static final double[] PET_LEVELS = doubles(PETS.getAsJsonArray("pet_levels"));
    private static final Map<String, Integer> CAPS = new HashMap<>();

    static {
        for (var e : LEVELING.getAsJsonObject("leveling_caps").entrySet()) {
            CAPS.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsInt());
        }
    }

    private Levels() {}

    /** Normal skills. Pass the cap (it can be raised, e.g. farming by Jacob perks). */
    public static Level skill(String skill, double xp, int cap) {
        double[] table = switch (skill) {
            case "runecrafting" -> RUNECRAFTING;
            case "social" -> SOCIAL;
            default -> SKILL;
        };
        return fromIncrements(table, xp, Math.min(cap, table.length), 0);
    }

    /** Default level cap for a skill before any raises. */
    public static int defaultCap(String skill) {
        return CAPS.getOrDefault(skill, 50);
    }

    public static Level catacombs(double xp) {
        return fromIncrements(CATACOMBS, xp, 50, 0);
    }

    /** Slayer: the table is cumulative totals per level. */
    public static Level slayer(String boss, double xp) {
        JsonArray arr = LEVELING.getAsJsonObject("slayer_xp").getAsJsonArray(boss);
        if (arr == null) return new Level(0, xp, 0, false, 0, xp);
        double[] totals = doubles(arr);
        int level = 0;
        while (level < totals.length && xp >= totals[level]) level++;
        if (level >= totals.length) {
            return new Level(level, 0, 0, true, xp - totals[totals.length - 1], xp);
        }
        double base = level == 0 ? 0 : totals[level - 1];
        return new Level(level, xp - base, totals[level] - base, false, 0, xp);
    }

    public static int slayerMax(String boss) {
        JsonArray arr = LEVELING.getAsJsonObject("slayer_xp").getAsJsonArray(boss);
        return arr == null ? 0 : arr.size();
    }

    /** Heart of the Mountain: level 1 at 0 xp. */
    public static Level hotm(double xp) {
        return fromIncrements(HOTM, xp, HOTM.length, 1);
    }

    /** Garden level: level 1 at 0 xp. */
    public static Level garden(double xp) {
        return fromIncrements(GARDEN_XP, xp, GARDEN_XP.length, 1);
    }

    /** Crop milestone. {@code crop} uses the milestone names (WHEAT, NETHER_WART, ...). */
    public static Level cropMilestone(String crop, double collected) {
        JsonArray arr = GARDEN.getAsJsonObject("crop_milestones").getAsJsonArray(crop);
        if (arr == null) return new Level(0, collected, 0, false, 0, collected);
        double[] inc = doubles(arr);
        return fromIncrements(inc, collected, inc.length, 0);
    }

    /** SkyBlock level: 100 xp per level, no cap. */
    public static Level skyblockLevel(double xp) {
        int level = (int) Math.floor(xp / 100);
        return new Level(level, xp - level * 100.0, 100, false, 0, xp);
    }

    /** Pet level from its xp, rarity and type. */
    public static Level pet(String type, String rarity, double xp) {
        JsonObject custom = PETS.getAsJsonObject("custom_pet_leveling").getAsJsonObject(type);
        int offset = PETS.getAsJsonObject("pet_rarity_offset").has(rarity)
                ? PETS.getAsJsonObject("pet_rarity_offset").get(rarity).getAsInt() : 0;
        if (custom != null && custom.has("rarity_offset") && custom.getAsJsonObject("rarity_offset").has(rarity)) {
            offset = custom.getAsJsonObject("rarity_offset").get(rarity).getAsInt();
        }
        int maxLevel = custom != null && custom.has("max_level") ? custom.get("max_level").getAsInt() : 100;
        // Levels 1..100 use the shared table from the rarity offset; some pets continue with their own table.
        int base = Math.min(99, PET_LEVELS.length - offset);
        double[] inc = new double[maxLevel - 1];
        for (int i = 0; i < base; i++) inc[i] = PET_LEVELS[offset + i];
        if (custom != null && custom.has("pet_levels") && maxLevel > 100) {
            double[] extra = doubles(custom.getAsJsonArray("pet_levels"));
            for (int i = 0; i < maxLevel - 1 - base && i < extra.length; i++) inc[base + i] = extra[i];
        }
        return fromIncrements(inc, xp, inc.length + 1, 1);
    }

    public static int petMaxLevel(String type) {
        JsonObject custom = PETS.getAsJsonObject("custom_pet_leveling").getAsJsonObject(type);
        return custom != null && custom.has("max_level") ? custom.get("max_level").getAsInt() : 100;
    }

    /**
     * Walks a per-level xp table. {@code startLevel} is the level held at 0 xp (0 for skills, 1 for
     * HOTM, garden and pets). For start level 1, the table's first entry is the cost of level 1 (0)
     * for HOTM/garden; pets pass a table that starts at the cost of level 2.
     */
    static Level fromIncrements(double[] inc, double xp, int maxLevel, int startLevel) {
        int level = startLevel;
        double remaining = xp;
        int i = startLevel == 1 && inc.length > 0 && inc[0] == 0 ? 1 : 0; // HOTM/garden list level 1 as 0
        while (level < maxLevel && i < inc.length && remaining >= inc[i]) {
            remaining -= inc[i];
            level++;
            i++;
        }
        if (level >= maxLevel || i >= inc.length) {
            return new Level(level, 0, 0, true, remaining, xp);
        }
        return new Level(level, remaining, inc[i], false, 0, xp);
    }

    /** Total xp needed for a level on the normal skill table. */
    public static double skillXpForLevel(int level) {
        double total = 0;
        for (int i = 0; i < level && i < SKILL.length; i++) total += SKILL[i];
        return total;
    }

    private static double[] doubles(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    private static JsonObject load(String name) {
        try (InputStream in = Levels.class.getResourceAsStream("/guildbridge/sb/" + name)) {
            if (in == null) throw new IllegalStateException("Missing table " + name);
            JsonElement e = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return e.getAsJsonObject();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Could not read table " + name, ex);
        }
    }
}
