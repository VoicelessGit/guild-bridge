package com.guildbridge.core.sb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The in-game command list and the parser for {@code !command [arg] [user] [profile]}.
 *
 * <p>Every part after the command is optional. A word is the arg when it is in that command's arg
 * list, a profile when it is a fruit name, and a player name otherwise. Item commands (!bz, !ah,
 * !collection) take the rest of the line as an item name instead.
 */
public final class PvCommands {
    /** What a command needs fetched before it can answer. */
    public enum Need { NONE, PROFILE, GARDEN, NETWORTH, WEIGHT, BAZAAR, PRICES, ELECTION, CANDIDATES, FIRESALES, COLLECTIONS, VERSION }

    public enum Group { BOT, PV }

    /**
     * @param args     accepted arg words, alias to canonical name; empty when the command takes none
     * @param argRequired the command makes no sense without its arg
     * @param itemArg  the arg is a free-text item name (rest of the line)
     * @param player   the command is about a player (so it takes [user] [profile])
     */
    public record Spec(String name, List<String> aliases, Map<String, String> args, boolean argRequired,
            boolean itemArg, boolean player, Need need, Group group, String usage, String help) {
        public boolean matches(String word) {
            return name.equals(word) || aliases.contains(word);
        }
    }

    /** A parsed request. {@code error} is a one-line hint to send back instead of answering. */
    public record Request(Spec spec, String arg, String user, String profile, String error) {
        static Request error(Spec spec, String error) {
            return new Request(spec, null, null, null, error);
        }
    }

    private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    public static final Map<String, String> SKILLS = ordered(
            "farming", "farming", "farm", "farming",
            "mining", "mining", "mine", "mining",
            "combat", "combat",
            "foraging", "foraging", "forage", "foraging",
            "fishing", "fishing", "fish", "fishing",
            "enchanting", "enchanting", "ench", "enchanting",
            "alchemy", "alchemy", "alch", "alchemy",
            "taming", "taming", "tame", "taming",
            "carpentry", "carpentry", "carp", "carpentry",
            "runecrafting", "runecrafting", "rc", "runecrafting",
            "social", "social",
            "hunting", "hunting");

    public static final Map<String, String> SLAYERS = ordered(
            "zombie", "zombie", "rev", "zombie", "revenant", "zombie",
            "spider", "spider", "tara", "spider", "tarantula", "spider",
            "wolf", "wolf", "sven", "wolf",
            "enderman", "enderman", "eman", "enderman", "voidgloom", "enderman",
            "blaze", "blaze", "inferno", "blaze",
            "vampire", "vampire", "vamp", "vampire");

    /** Classes and floors for !cata. Floors are f0..f7 and m1..m7. */
    public static final Map<String, String> CATA_ARGS;

    public static final Map<String, String> CROPS = ordered(
            "wheat", "WHEAT",
            "carrot", "CARROT", "carrots", "CARROT",
            "potato", "POTATO", "potatoes", "POTATO",
            "melon", "MELON",
            "pumpkin", "PUMPKIN",
            "cane", "SUGAR_CANE", "sugarcane", "SUGAR_CANE",
            "cocoa", "COCOA_BEANS", "cocoabeans", "COCOA_BEANS",
            "cactus", "CACTUS",
            "mushroom", "MUSHROOM", "mushrooms", "MUSHROOM",
            "wart", "NETHER_WART", "netherwart", "NETHER_WART",
            "moonflower", "MOONFLOWER",
            "sunflower", "SUNFLOWER",
            "rose", "WILD_ROSE", "wildrose", "WILD_ROSE");

    static {
        Map<String, String> m = new LinkedHashMap<>();
        for (String c : List.of("healer", "mage", "berserk", "archer", "tank")) m.put(c, c);
        m.put("bers", "berserk");
        m.put("entrance", "f0");
        m.put("e", "f0");
        for (int i = 0; i <= 7; i++) m.put("f" + i, "f" + i);
        for (int i = 1; i <= 7; i++) m.put("m" + i, "m" + i);
        CATA_ARGS = m;
    }

    public static final List<Spec> ALL = List.of(
            // Bot commands
            spec("commands", List.of("help"), Map.of(), false, false, false, Need.NONE, Group.BOT,
                    "!commands", "where to find the full command list"),
            spec("version", List.of("versions"), Map.of(), false, false, false, Need.VERSION, Group.BOT,
                    "!version", "bot version and last update"),
            spec("bz", List.of("bazaar"), Map.of(), true, true, false, Need.BAZAAR, Group.BOT,
                    "!bz <item>", "bazaar prices"),
            spec("ah", List.of("lbin"), Map.of(), true, true, false, Need.PRICES, Group.BOT,
                    "!ah <item>", "auction house price"),
            spec("mayor", List.of(), Map.of(), false, false, false, Need.ELECTION, Group.BOT,
                    "!mayor", "current mayor and election"),
            spec("election", List.of(), Map.of(), false, false, false, Need.CANDIDATES, Group.BOT,
                    "!election", "candidates, their perks and votes (Discord only)"),
            spec("firesale", List.of("fs", "firesales"), Map.of(), false, false, false, Need.FIRESALES, Group.BOT,
                    "!firesale", "upcoming fire sales"),
            // Profile viewer commands
            spec("sb", List.of("pv"), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!sb [user] [profile]", "profile overview"),
            spec("level", List.of("lvl", "sblvl"), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!level [user] [profile]", "SkyBlock level"),
            spec("skills", List.of(), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!skills [user] [profile]", "all skills"),
            spec("skill", List.of(), SKILLS, true, false, true, Need.PROFILE, Group.PV,
                    "!skill <skill> [user] [profile]", "one skill with xp"),
            spec("cata", List.of("catacombs", "dungeons"), CATA_ARGS, false, false, true, Need.PROFILE, Group.PV,
                    "!cata [class or floor] [user] [profile]", "catacombs, classes, floor runs and PBs"),
            spec("slayer", List.of("slayers"), SLAYERS, false, false, true, Need.PROFILE, Group.PV,
                    "!slayer [type] [user] [profile]", "slayer levels"),
            spec("bank", List.of("purse", "coins"), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!bank [user] [profile]", "purse and bank"),
            spec("profiles", List.of(), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!profiles [user]", "all profiles"),
            spec("hotm", List.of(), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!hotm [user] [profile]", "Heart of the Mountain and powder"),
            spec("pet", List.of("pets"), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!pet [user] [profile]", "active pet"),
            spec("essence", List.of("ess"), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!essence [user] [profile]", "essence"),
            spec("crimson", List.of("kuudra"), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!crimson [user] [profile]", "faction rep and Kuudra runs"),
            spec("minions", List.of("minion"), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!minions [user] [profile]", "unique crafts and slots"),
            spec("fairy", List.of("souls", "fairysouls"), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!fairy [user] [profile]", "fairy souls"),
            spec("garden", List.of(), CROPS, false, false, true, Need.GARDEN, Group.PV,
                    "!garden [crop] [user] [profile]", "garden, or one crop milestone"),
            spec("jacob", List.of("contests"), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!jacob [user] [profile]", "contests and perks"),
            spec("medals", List.of(), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!medals [user] [profile]", "farming medals and best brackets"),
            spec("pests", List.of(), Map.of(), false, false, true, Need.PROFILE, Group.PV,
                    "!pests [user] [profile]", "pest kills"),
            spec("collection", List.of("coll"), Map.of(), true, true, true, Need.COLLECTIONS, Group.PV,
                    "!collection <item> [user] [profile]", "collection tier"),
            spec("weight", List.of("we"), Map.of(), false, false, true, Need.WEIGHT, Group.PV,
                    "!weight [user] [profile]", "Senither and Lily weight"),
            spec("nw", List.of("networth"), Map.of(), false, false, true, Need.NETWORTH, Group.PV,
                    "!nw [user] [profile]", "networth (SkyHelper)"));

    private PvCommands() {}

    /** The spec for a command word ("sb", "kuudra"...), if it is one of ours. */
    public static Optional<Spec> find(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return ALL.stream().filter(s -> s.matches(w)).findFirst();
    }

    /** Commands answered only in Discord (in game, players can just check it themselves). */
    public static boolean discordOnly(Spec s) {
        return s.need() == Need.CANDIDATES;
    }

    /** True for "!word ..." that the bridge answers in game; Discord-only commands stay plain chat. */
    public static boolean isGameCommand(String text) {
        if (!isCommand(text)) return false;
        return !discordOnly(find(text.strip().substring(1).split("\\s+")[0]).orElseThrow());
    }

    /** True for "!word ..." where word is one of ours. */
    public static boolean isCommand(String text) {
        if (text == null) return false;
        String t = text.strip();
        if (!t.startsWith("!") || t.length() < 2) return false;
        return find(t.substring(1).split("\\s+")[0]).isPresent();
    }

    /**
     * Parses "!skill mining Steve Apple". {@code itemExists} answers whether a free-text item name is
     * known; it decides whether the last word of an item command is an item word or a player name.
     */
    public static Request parse(String text, Predicate<String> itemExists) {
        String[] words = text.strip().substring(1).split("\\s+");
        Spec spec = find(words[0]).orElseThrow();
        List<String> rest = new ArrayList<>(Arrays.asList(words).subList(1, words.length));
        String usage = "Usage: " + spec.usage();

        if (spec.itemArg()) {
            String profile = null, user = null;
            if (spec.player() && !rest.isEmpty() && isFruit(rest.get(rest.size() - 1))) {
                profile = rest.remove(rest.size() - 1).toLowerCase(Locale.ROOT);
            }
            if (rest.isEmpty()) return Request.error(spec, usage);
            String all = String.join(" ", rest);
            if (spec.player() && rest.size() > 1 && !itemExists.test(all)
                    && IGN.matcher(rest.get(rest.size() - 1)).matches()) {
                String maybeItem = String.join(" ", rest.subList(0, rest.size() - 1));
                if (itemExists.test(maybeItem)) {
                    user = rest.get(rest.size() - 1);
                    all = maybeItem;
                }
            }
            return new Request(spec, all, user, profile, null);
        }

        String arg = null, user = null, profile = null;
        for (String w : rest) {
            String lw = w.toLowerCase(Locale.ROOT);
            if (arg == null && spec.args().containsKey(lw)) {
                arg = spec.args().get(lw);
            } else if (profile == null && spec.player() && isFruit(lw)) {
                profile = lw;
            } else if (user == null && spec.player() && IGN.matcher(w).matches()) {
                user = w;
            } else {
                return Request.error(spec, usage + hint(spec));
            }
        }
        if (spec.argRequired() && arg == null) return Request.error(spec, usage + hint(spec));
        return new Request(spec, arg, user, profile, null);
    }

    /** Lists the canonical arg words, e.g. " (skills: farming, mining, ...)". */
    private static String hint(Spec spec) {
        if (spec.args().isEmpty()) return "";
        List<String> canon = new ArrayList<>();
        for (String v : spec.args().values()) {
            String c = v.toLowerCase(Locale.ROOT);
            if (!canon.contains(c)) canon.add(c);
        }
        String label = switch (spec.name()) {
            case "skill" -> "skills";
            case "slayer" -> "types";
            case "cata" -> "classes or floors";
            case "garden" -> "crops";
            default -> "options";
        };
        if (spec.name().equals("cata")) return " (" + label + ": healer, mage, berserk, archer, tank, f0-f7, m1-m7)";
        String list = String.join(", ", canon).replace("_", " ");
        return " (" + label + ": " + list + ")";
    }

    public static boolean isFruit(String w) {
        return SbProfile.FRUITS.contains(w.toLowerCase(Locale.ROOT));
    }

    private static Spec spec(String name, List<String> aliases, Map<String, String> args, boolean argRequired,
            boolean itemArg, boolean player, Need need, Group group, String usage, String help) {
        return new Spec(name, aliases, args, argRequired, itemArg, player, need, group, usage, help);
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
