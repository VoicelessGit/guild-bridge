package com.guildbridge.core.sb;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One player's view of one SkyBlock profile, cut from the /v2/skyblock/profiles response.
 *
 * @param ign       name to show in replies
 * @param uuid      undashed Minecraft UUID
 * @param profile   the whole profile object (members, banking, cute_name...)
 * @param member    profile.members[uuid]
 * @param all       every profile the player has, for !profiles
 */
public record SbProfile(String ign, String uuid, JsonObject profile, JsonObject member, List<JsonObject> all) {

    /** Fruit names Hypixel gives profiles. A word in this list is always a profile, never a player. */
    public static final List<String> FRUITS = List.of("apple", "banana", "blueberry", "coconut", "cucumber",
            "grapes", "kiwi", "lemon", "lime", "mango", "orange", "papaya", "peach", "pear", "pineapple",
            "pomegranate", "raspberry", "strawberry", "tomato", "watermelon", "zucchini");

    /** Thrown when the player or the asked-for profile does not exist. The message is shown as is. */
    public static final class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /**
     * Picks a profile from the API response: the named one, else the one Hypixel marks selected,
     * else the most recently saved.
     */
    public static SbProfile pick(String ign, String uuid, JsonObject response, String wantedName) {
        var profiles = J.arr(response, "profiles");
        if (profiles == null || profiles.isEmpty()) {
            throw new NotFound(ign + " has no SkyBlock profiles.");
        }
        List<JsonObject> all = new ArrayList<>();
        for (JsonElement e : profiles) {
            if (e.isJsonObject() && J.obj(e, "members", uuid) != null) all.add(e.getAsJsonObject());
        }
        if (all.isEmpty()) throw new NotFound(ign + " has no SkyBlock profiles.");
        JsonObject chosen = null;
        if (wantedName != null) {
            for (JsonObject p : all) {
                if (wantedName.equalsIgnoreCase(J.str(p, "cute_name"))) chosen = p;
            }
            if (chosen == null) throw new NotFound(ign + " has no profile named " + Fmt.title(wantedName) + ".");
        } else {
            for (JsonObject p : all) {
                if (J.bool(p, "selected")) chosen = p;
            }
            if (chosen == null) chosen = all.get(0);
        }
        return new SbProfile(ign, uuid, chosen, J.obj(chosen, "members", uuid), all);
    }

    public String cuteName() {
        String n = J.str(profile, "cute_name");
        return n == null ? "?" : n;
    }

    public String profileId() {
        return J.str(profile, "profile_id");
    }

    /** "Ironman", "Stranded", "Bingo" or "" for a normal profile. */
    public static String modeName(JsonObject p) {
        String m = J.str(p, "game_mode");
        if (m == null) return "";
        return switch (m.toLowerCase(Locale.ROOT)) {
            case "ironman" -> "Ironman";
            case "island" -> "Stranded";
            case "bingo" -> "Bingo";
            default -> Fmt.title(m);
        };
    }

    public double num(String... path) {
        return J.num(member, path);
    }

    public double num0(String... path) {
        return J.num0(member, 0, path);
    }

    public JsonObject obj(String... path) {
        return J.obj(member, path);
    }
}
