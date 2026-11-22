package com.guildbridge.core.sb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** !bz, !ah, !mayor, !firesale. */
public final class MarketFormat {
    private MarketFormat() {}

    // ----- item name lookup -----

    /** Item names from the Hypixel items list, plus names made up for ids it lacks (books, pets). */
    public static final class ItemIndex {
        private final Map<String, String> idToName = new HashMap<>();

        public ItemIndex(JsonObject itemsResource) {
            JsonArray items = J.arr(itemsResource, "items");
            if (items != null) {
                for (JsonElement e : items) {
                    String id = J.str(e, "id");
                    String name = J.str(e, "name");
                    if (id != null && name != null) idToName.put(id, com.guildbridge.core.TextUtil.stripFormatting(name));
                }
            }
        }

        /** The Hypixel name, or null if the items list doesn't have this id. */
        public String nameOrNull(String id) {
            return idToName.get(id);
        }

        public String name(String id) {
            String n = idToName.get(id);
            if (n != null) return n;
            Matcher book = ENCHANT_BOOK.matcher(id);
            if (book.matches()) return Fmt.title(book.group(1)) + " " + book.group(2);
            Matcher pet = PET_KEY.matcher(id);
            if (pet.matches()) return "[Lvl " + pet.group(1) + "] " + Fmt.title(pet.group(2)) + " " + Fmt.title(pet.group(3));
            return Fmt.title(id);
        }

        /**
         * Best match for a typed name among {@code ids}: exact name, then exact id, then a name that
         * starts with it, then one that contains it. Shorter names win ties, so "diamond" finds
         * Diamond before Enchanted Diamond.
         */
        public String find(String query, Set<String> ids) {
            String q = PvFormat.norm(query);
            if (q.isEmpty()) return null;
            String best = null;
            int bestRank = 9, bestLen = Integer.MAX_VALUE;
            for (String id : ids) {
                String n = PvFormat.norm(name(id));
                int rank;
                if (n.equals(q)) rank = 0;
                else if (PvFormat.norm(id).equals(q)) rank = 1;
                else if (n.startsWith(q)) rank = 2;
                else if (n.contains(q)) rank = 3;
                else continue;
                if (rank < bestRank || (rank == bestRank && n.length() < bestLen)) {
                    best = id;
                    bestRank = rank;
                    bestLen = n.length();
                }
            }
            return best;
        }
    }

    static final Pattern ENCHANT_BOOK = Pattern.compile("^ENCHANTMENT_(?:ULTIMATE_)?(.+?)_(\\d+)$");
    /** SkyHelper price keys for pets: LVL_100_LEGENDARY_GOLDEN_DRAGON. */
    static final Pattern PET_KEY = Pattern.compile("^LVL_(\\d+)_(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC)_([A-Z0-9_]+?)$");

    // ----- bazaar -----

    /**
     * @param products the "products" object of /v2/skyblock/bazaar
     */
    public static String bazaar(JsonObject products, ItemIndex names, String query) {
        if (products == null) return "Bazaar data isn't available right now.";
        String id = names.find(query, products.keySet());
        if (id == null) return "No bazaar item matches \"" + query + "\".";
        JsonObject pr = J.obj(products, id);
        // sell_summary holds buy orders (what you insta-sell into, highest first);
        // buy_summary holds sell offers (what you insta-buy from, lowest first).
        double instaBuy = top(J.arr(pr, "buy_summary"), J.num(pr, "quick_status", "buyPrice"));
        double instaSell = top(J.arr(pr, "sell_summary"), J.num(pr, "quick_status", "sellPrice"));
        double boughtWeek = J.num0(pr, 0, "quick_status", "buyMovingWeek");
        double soldWeek = J.num0(pr, 0, "quick_status", "sellMovingWeek");
        String name = names.name(id);
        if (Double.isNaN(instaBuy) && Double.isNaN(instaSell)) return name + ": no orders right now";
        StringBuilder sb = new StringBuilder(name).append(": ");
        sb.append("Insta-buy ").append(price(instaBuy)).append(" | Insta-sell ").append(price(instaSell));
        if (!Double.isNaN(instaBuy) && !Double.isNaN(instaSell)) {
            sb.append(" | Buy order ").append(price(instaSell + 0.1)).append(" | Sell offer ").append(price(instaBuy - 0.1));
        }
        sb.append(" | Weekly ").append(Fmt.num(boughtWeek)).append(" bought, ").append(Fmt.num(soldWeek)).append(" sold");
        return sb.toString();
    }

    private static double top(JsonArray summary, double fallback) {
        if (summary != null && !summary.isEmpty()) return J.num0(summary.get(0), fallback, "pricePerUnit");
        return fallback;
    }

    /** Exact to 0.1 coin below 1M (flippers care about small margins), short form above. */
    static String price(double v) {
        if (Double.isNaN(v)) return "none";
        if (Math.abs(v) < 1_000_000) return String.format(Locale.ROOT, "%,.1f", v);
        return Fmt.num(v);
    }

    // ----- auction house (SkyHelper price list) -----

    /**
     * @param prices SkyHelper's price list: item id (or pet key) to its lowest BIN
     */
    public static String auction(Map<String, Double> prices, ItemIndex names, String query) {
        if (prices == null || prices.isEmpty()) return "Auction prices aren't available right now.";
        // Pets: "golden dragon" -> show level 1 and the max level at the best rarity listed.
        String petType = petQuery(prices, query);
        if (petType != null) return petPrices(prices, petType);
        Set<String> ids = new java.util.HashSet<>();
        for (String k : prices.keySet()) {
            if (!PET_KEY.matcher(k).matches() && !k.contains("_SKINNED_")) ids.add(k);
        }
        String id = names.find(query, ids);
        if (id == null) return "No auction item matches \"" + query + "\".";
        return names.name(id) + ": lowest BIN " + Fmt.num(prices.get(id));
    }

    private static String petQuery(Map<String, Double> prices, String query) {
        String q = PvFormat.norm(query);
        String found = null;
        for (String k : prices.keySet()) {
            Matcher m = PET_KEY.matcher(k);
            if (!m.matches()) continue;
            String type = m.group(3);
            if (PvFormat.norm(type).equals(q)) return type;
            if (found == null && PvFormat.norm(type).startsWith(q) && q.length() >= 4) found = type;
        }
        return found;
    }

    private static String petPrices(Map<String, Double> prices, String type) {
        List<String> order = List.of("MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON");
        for (String rarity : order) {
            List<String> parts = new ArrayList<>();
            for (int lvl : new int[] {1, 100, 200}) {
                Double v = prices.get("LVL_" + lvl + "_" + rarity + "_" + type);
                if (v != null) parts.add("Lvl " + lvl + " " + Fmt.num(v));
            }
            if (!parts.isEmpty()) {
                return Fmt.title(rarity) + " " + Fmt.title(type) + ": lowest BIN " + String.join(" | ", parts);
            }
        }
        return Fmt.title(type) + ": no auction price";
    }

    // ----- mayor -----

    /** SkyBlock calendar: year 1 began at this instant; a year is 124 hours, a day 20 minutes. */
    static final long SB_EPOCH = 1560275700000L;
    static final long SB_YEAR = 124L * 60 * 60 * 1000;
    static final long SB_DAY = 20L * 60 * 1000;
    /** Votes close on Late Spring 27 (third month, day 27). */
    static final long ELECTION_CLOSE_OFFSET = (2 * 31 + 26) * SB_DAY;

    public static String mayor(JsonObject election, long now) {
        JsonObject mayor = J.obj(election, "mayor");
        if (mayor == null) return "Mayor data isn't available right now.";
        StringBuilder sb = new StringBuilder("Mayor: ").append(J.str(mayor, "name"));
        List<String> perks = perkNames(J.arr(mayor, "perks"));
        if (!perks.isEmpty()) sb.append(" (").append(String.join(", ", perks)).append(')');
        JsonObject minister = J.obj(mayor, "minister");
        if (minister != null) {
            sb.append(" | Minister: ").append(J.str(minister, "name"));
            String perk = J.str(minister, "perk", "name");
            if (perk != null) sb.append(" (").append(perk).append(')');
        }
        JsonArray cands = J.arr(election, "current", "candidates");
        if (cands != null && !cands.isEmpty()) {
            double total = 0;
            for (JsonElement c : cands) total += J.num0(c, 0, "votes");
            List<JsonElement> sorted = new ArrayList<>();
            cands.forEach(sorted::add);
            sorted.sort((a, b) -> Double.compare(J.num0(b, 0, "votes"), J.num0(a, 0, "votes")));
            sb.append(" | Election:");
            for (int i = 0; i < Math.min(3, sorted.size()); i++) {
                double v = J.num0(sorted.get(i), 0, "votes");
                sb.append(i == 0 ? " " : ", ").append(J.str(sorted.get(i), "name")).append(' ')
                        .append(total > 0 ? Math.round(v * 100 / total) : 0).append('%');
            }
            sb.append(", ends in about ").append(Fmt.duration(nextElectionClose(now) - now));
        }
        return sb.toString();
    }

    static long nextElectionClose(long now) {
        long years = Math.floorDiv(now - SB_EPOCH, SB_YEAR);
        long close = SB_EPOCH + years * SB_YEAR + ELECTION_CLOSE_OFFSET;
        return close > now ? close : close + SB_YEAR;
    }

    private static List<String> perkNames(JsonArray perks) {
        List<String> out = new ArrayList<>();
        if (perks == null) return out;
        for (JsonElement p : perks) {
            String n = J.str(p, "name");
            if (n != null) out.add(n);
        }
        return out;
    }

    // ----- fire sales -----

    public static String firesales(JsonObject resp, Function<String, String> names, long now) {
        JsonArray sales = J.arr(resp, "sales");
        if (sales == null || sales.isEmpty()) return "Fire sale: none scheduled";
        List<String> parts = new ArrayList<>();
        for (JsonElement s : sales) {
            String id = J.str(s, "item_id");
            String name = id == null ? "?" : names.apply(id);
            double start = J.num0(s, 0, "start"), end = J.num0(s, 0, "end");
            String when = start > now ? "starts in " + Fmt.duration((long) start - now)
                    : "ends in " + Fmt.duration((long) end - now);
            parts.add(name + " x" + Fmt.comma(J.num0(s, 0, "amount")) + " for " + Fmt.comma(J.num0(s, 0, "price"))
                    + " gems, " + when);
        }
        return "Fire sale: " + String.join(" | ", parts);
    }
}
