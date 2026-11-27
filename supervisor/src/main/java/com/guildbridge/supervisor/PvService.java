package com.guildbridge.supervisor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.guildbridge.core.sb.Fmt;
import com.guildbridge.core.sb.J;
import com.guildbridge.core.sb.MarketFormat;
import com.guildbridge.core.sb.PvCommands;
import com.guildbridge.core.sb.PvFormat;
import com.guildbridge.core.sb.SbProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Answers in-game commands: parses, fetches what is needed, formats one line. */
final class PvService {
    private final PvData api;
    private final NodeHelper node;
    private final Supplier<String> versionLine;
    private final Supplier<String> commandsHint;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "pv-commands");
        t.setDaemon(true);
        return t;
    });
    private volatile JsonObject indexedItems;
    private volatile MarketFormat.ItemIndex index = new MarketFormat.ItemIndex(null);

    PvService(PvData api, NodeHelper node, Supplier<String> versionLine, Supplier<String> commandsHint) {
        this.api = api;
        this.node = node;
        this.versionLine = versionLine;
        this.commandsHint = commandsHint;
    }

    /** In game: no [user] means the player who asked. Never completes exceptionally. */
    CompletableFuture<String> answer(String text, String asker) {
        return answer(text, asker, null);
    }

    /**
     * @param selfName   name to use when no [user] is given (in game), or null
     * @param selfLinked linked account to use when no [user] is given (Discord), or null
     */
    CompletableFuture<String> answer(String text, String selfName, HypixelApi.Player selfLinked) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return compute(text, selfName, selfLinked);
            } catch (HypixelApi.ApiException | SbProfile.NotFound e) {
                return e.getMessage();
            } catch (RuntimeException e) {
                Main.LOG.warn("Command '{}' failed: {}", text, e.toString());
                return "Something went wrong with that command.";
            }
        }, pool);
    }

    String compute(String text, String asker, HypixelApi.Player linked) {
        PvCommands.Request r = PvCommands.parse(text, q -> {
            try {
                return PvFormat.findCollection(api.collections(), q) != null;
            } catch (RuntimeException e) {
                return false;
            }
        });
        if (r.error() != null) return r.error();
        long now = System.currentTimeMillis();
        switch (r.spec().need()) {
            case NONE:
                return commandsHint.get();
            case VERSION:
                return versionLine.get();
            case BAZAAR:
                return MarketFormat.bazaar(J.obj(api.bazaar(), "products"), items(), r.arg());
            case PRICES:
                return MarketFormat.auction(api.prices(), items(), r.arg());
            case ELECTION:
                return MarketFormat.mayor(api.election(), now);
            case CANDIDATES:
                return MarketFormat.election(api.election(), now);
            case FIRESALES:
                return MarketFormat.firesales(api.firesales(), items()::name, now);
            default:
                break;
        }
        if (!api.hasKey()) return "Profile commands aren't set up yet (no Hypixel API key).";
        HypixelApi.Player pl;
        if (r.user() != null) {
            pl = api.player(r.user());
        } else if (linked != null) {
            String current;
            try {
                current = api.nameOf(linked.uuid()); // names change; the link keeps the uuid
            } catch (RuntimeException e) {
                current = linked.name();
            }
            pl = new HypixelApi.Player(current, linked.uuid());
        } else if (asker != null) {
            pl = api.player(asker);
        } else {
            return "Add a Minecraft name: " + r.spec().usage().replace("[user]", "<user>")
                    + ". Link your account with /verify to skip it.";
        }
        SbProfile p = SbProfile.pick(pl.name(), pl.uuid(), api.profiles(pl.uuid()), r.profile());
        return switch (r.spec().name()) {
            case "sb" -> PvFormat.sb(p);
            case "level" -> PvFormat.level(p);
            case "skills" -> PvFormat.skills(p);
            case "skill" -> PvFormat.skillOne(p, r.arg());
            case "cata" -> PvFormat.cata(p, r.arg());
            case "slayer" -> PvFormat.slayer(p, r.arg());
            case "bank" -> PvFormat.bank(p);
            case "profiles" -> PvFormat.profiles(p);
            case "hotm" -> PvFormat.hotm(p);
            case "pet" -> PvFormat.pet(p, id -> items().nameOrNull(id));
            case "essence" -> PvFormat.essence(p);
            case "crimson" -> PvFormat.crimson(p);
            case "minions" -> PvFormat.minions(p);
            case "fairy" -> PvFormat.fairy(p);
            case "garden" -> PvFormat.garden(p, api.garden(p.profileId()), r.arg());
            case "jacob" -> PvFormat.jacob(p);
            case "medals" -> PvFormat.medals(p);
            case "pests" -> PvFormat.pests(p);
            case "collection" -> collection(p, r.arg());
            case "weight" -> weight(p);
            case "nw" -> networth(p);
            default -> "Unknown command.";
        };
    }

    private String collection(SbProfile p, String query) {
        JsonObject cols = api.collections();
        String id = PvFormat.findCollection(cols, query);
        if (id == null) return "No collection matches \"" + query + "\".";
        return PvFormat.collection(p, cols, id);
    }

    private String weight(SbProfile p) {
        JsonObject exp = p.obj("player_data", "experience");
        if (exp == null) return p.ign() + ": Weight needs the skills API on";
        JsonObject req = new JsonObject();
        req.addProperty("op", "weight");
        JsonArray lilySkills = new JsonArray();
        for (String s : PvFormat.LILY_SKILLS) lilySkills.add(J.num0(exp, 0, "SKILL_" + s.toUpperCase(java.util.Locale.ROOT)));
        req.add("lilySkillXp", lilySkills);
        JsonObject cata = p.obj("dungeons", "dungeon_types", "catacombs");
        JsonObject master = p.obj("dungeons", "dungeon_types", "master_catacombs");
        req.add("cataCompl", cata != null && cata.has("tier_completions") ? cata.get("tier_completions") : new JsonObject());
        req.add("mCataCompl", master != null && master.has("tier_completions") ? master.get("tier_completions") : new JsonObject());
        req.addProperty("cataXp", J.num0(cata, 0, "experience"));
        JsonArray lilySlayers = new JsonArray();
        for (String b : List.of("zombie", "spider", "wolf", "enderman", "blaze")) {
            lilySlayers.add(p.num0("slayer", "slayer_bosses", b, "xp"));
        }
        req.add("lilySlayerXp", lilySlayers);
        JsonObject senSkills = new JsonObject();
        for (String s : List.of("mining", "foraging", "enchanting", "farming", "combat", "fishing", "alchemy", "taming")) {
            senSkills.addProperty(s, J.num0(exp, 0, "SKILL_" + s.toUpperCase(java.util.Locale.ROOT)));
        }
        req.add("senSkills", senSkills);
        JsonObject senSlayers = new JsonObject();
        senSlayers.addProperty("revenant", p.num0("slayer", "slayer_bosses", "zombie", "xp"));
        senSlayers.addProperty("tarantula", p.num0("slayer", "slayer_bosses", "spider", "xp"));
        senSlayers.addProperty("sven", p.num0("slayer", "slayer_bosses", "wolf", "xp"));
        senSlayers.addProperty("enderman", p.num0("slayer", "slayer_bosses", "enderman", "xp"));
        req.add("senSlayers", senSlayers);
        JsonObject senDungeons = new JsonObject();
        senDungeons.addProperty("catacombs", J.num0(cata, 0, "experience"));
        for (String c : List.of("healer", "mage", "berserk", "archer", "tank")) {
            senDungeons.addProperty(c.equals("berserk") ? "berserker" : c, p.num0("dungeons", "player_classes", c, "experience"));
        }
        req.add("senDungeons", senDungeons);
        JsonObject res = node.call(req, 20);
        double sen = J.num0(res, 0, "senither");
        double senOver = J.num0(res, 0, "senitherOverflow");
        double lily = J.num0(res, 0, "lily");
        return p.ign() + ": Senither " + Fmt.comma(sen) + " (" + Fmt.comma(sen - senOver) + " + " + Fmt.comma(senOver)
                + " overflow) | Lily " + Fmt.comma(lily);
    }

    private String networth(SbProfile p) {
        JsonObject req = new JsonObject();
        req.addProperty("op", "networth");
        req.add("member", p.member());
        req.addProperty("bank", J.num0(p.profile(), 0, "banking", "balance"));
        try {
            JsonObject museum = J.obj(api.museum(p.profileId()), "members", p.uuid());
            if (museum != null) req.add("museum", museum);
        } catch (RuntimeException e) {
            // museum is optional
        }
        JsonObject res = node.call(req, 60);
        StringBuilder sb = new StringBuilder(p.ign()).append(": Networth ").append(Fmt.num(J.num0(res, 0, "networth")))
                .append(" | Unsoulbound ").append(Fmt.num(J.num0(res, 0, "unsoulbound")));
        JsonObject types = J.obj(res, "types");
        if (types != null) {
            List<Map.Entry<String, Double>> top = new ArrayList<>();
            for (var e : types.entrySet()) top.add(Map.entry(e.getKey(), e.getValue().getAsDouble()));
            top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            sb.append(" | Top:");
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                if (top.get(i).getValue() <= 0) break;
                sb.append(i == 0 ? " " : ", ").append(typeName(top.get(i).getKey())).append(' ').append(Fmt.num(top.get(i).getValue()));
            }
        }
        if (J.bool(res, "noInventory")) sb.append(" | Inventory API off, items not counted");
        return sb.toString();
    }

    private static String typeName(String key) {
        return switch (key) {
            case "enderchest" -> "Ender Chest";
            case "personal_vault" -> "Vault";
            case "sacks_bag", "sacks" -> "Sacks";
            default -> Fmt.title(key);
        };
    }

    /** The item name index, rebuilt when the items list refreshes. Works (with made-up names) if Hypixel is down. */
    private MarketFormat.ItemIndex items() {
        try {
            JsonObject itemsJson = api.items();
            if (itemsJson != indexedItems) {
                index = new MarketFormat.ItemIndex(itemsJson);
                indexedItems = itemsJson;
            }
        } catch (RuntimeException e) {
            // keep the old index
        }
        return index;
    }

    void shutdown() {
        pool.shutdownNow();
        node.stop();
    }
}
