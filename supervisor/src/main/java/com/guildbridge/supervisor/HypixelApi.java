package com.guildbridge.supervisor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Hypixel and Mojang lookups with caching. Every method blocks (call it off the Discord and IPC
 * threads) and throws {@link ApiException} with a message fit to show players.
 */
final class HypixelApi implements PvData {
    /** A failure with a short, player-facing message. */
    static class ApiException extends RuntimeException {
        ApiException(String message) {
            super(message);
        }
    }

    record Player(String name, String uuid) {}

    private static final String BASE = "https://api.hypixel.net/v2";
    private static final String PRICES_URL = "https://raw.githubusercontent.com/SkyHelperBot/Prices/main/pricesV2.json";
    private static final long MINUTE = 60_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Supplier<String> key;

    private final Cache<Player> players = new Cache<>(500);
    private final Cache<JsonObject> keyed = new Cache<>(200);
    private final Cache<Object> shared = new Cache<>(20);
    /** Requests left in Hypixel's current rate-limit window, from the last response. */
    private volatile int remaining = 120;
    private volatile long resetAt;

    HypixelApi(Supplier<String> key) {
        this.key = key;
    }

    @Override
    public boolean hasKey() {
        String k = key.get();
        return k != null && !k.isBlank();
    }

    // ----- players -----

    @Override
    public Player player(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return players.get("p:" + lower, 24 * 60 * MINUTE, () -> {
            JsonObject o;
            try {
                o = getJson("https://api.mojang.com/users/profiles/minecraft/"
                        + URLEncoder.encode(name, StandardCharsets.UTF_8), null, false);
            } catch (NotFoundException e) {
                o = null;
            }
            if (o == null || !o.has("id")) throw new ApiException("No Minecraft player named " + name + ".");
            return new Player(o.get("name").getAsString(), o.get("id").getAsString());
        });
    }

    @Override
    public String nameOf(String uuid) {
        return players.get("u:" + uuid, 24 * 60 * MINUTE, () -> {
            JsonObject o = getJson("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid, null, false);
            if (o == null || !o.has("name")) throw new ApiException("Unknown player.");
            return new Player(o.get("name").getAsString(), uuid);
        }).name();
    }

    // ----- keyed SkyBlock data -----

    @Override
    public JsonObject profiles(String uuid) {
        return keyed.get("profiles:" + uuid, 3 * MINUTE, () -> getJson(BASE + "/skyblock/profiles?uuid=" + uuid, key.get(), true));
    }

    @Override
    public JsonObject garden(String profileId) {
        return keyed.get("garden:" + profileId, 3 * MINUTE, () -> {
            try {
                return getJson(BASE + "/skyblock/garden?profile=" + profileId, key.get(), true);
            } catch (NotFoundException e) {
                return new JsonObject(); // no garden on this profile
            }
        });
    }

    @Override
    public JsonObject museum(String profileId) {
        return keyed.get("museum:" + profileId, 10 * MINUTE, () -> {
            try {
                return getJson(BASE + "/skyblock/museum?profile=" + profileId, key.get(), true);
            } catch (NotFoundException e) {
                return new JsonObject();
            }
        });
    }

    // ----- public resources (no key) -----

    @Override
    public JsonObject bazaar() {
        return (JsonObject) shared.get("bazaar", MINUTE, () -> getJson(BASE + "/skyblock/bazaar", null, true));
    }

    @Override
    public JsonObject election() {
        return (JsonObject) shared.get("election", 5 * MINUTE, () -> getJson(BASE + "/resources/skyblock/election", null, true));
    }

    @Override
    public JsonObject firesales() {
        return (JsonObject) shared.get("firesales", 5 * MINUTE, () -> getJson(BASE + "/skyblock/firesales", null, true));
    }

    @Override
    public JsonObject collections() {
        return (JsonObject) shared.get("collections", 12 * 60 * MINUTE,
                () -> getJson(BASE + "/resources/skyblock/collections", null, true));
    }

    @Override
    public JsonObject items() {
        return (JsonObject) shared.get("items", 12 * 60 * MINUTE, () -> getJson(BASE + "/resources/skyblock/items", null, true));
    }

    /** SkyHelper's price list (item id to lowest BIN), refreshed every 5 minutes. */
    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Double> prices() {
        return (Map<String, Double>) shared.get("prices", 5 * MINUTE, () -> {
            JsonObject o = getJson(PRICES_URL, null, false);
            Map<String, Double> m = new HashMap<>();
            for (var e : o.entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
                    m.put(e.getKey(), e.getValue().getAsDouble());
                }
            }
            return m;
        });
    }

    // ----- plumbing -----

    private static final class NotFoundException extends ApiException {
        NotFoundException() {
            super("Not found.");
        }
    }

    private JsonObject getJson(String url, String apiKey, boolean hypixel) {
        if (hypixel && apiKey != null && remaining <= 2 && System.currentTimeMillis() < resetAt) {
            throw new ApiException("Hypixel API limit reached, try again in a minute.");
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "GuildBridge");
        if (apiKey != null) {
            if (apiKey.isBlank()) throw new ApiException("The bot has no Hypixel API key set.");
            rb.header("API-Key", apiKey);
        }
        HttpResponse<String> res;
        try {
            res = http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException(hypixel ? "Hypixel API didn't respond, try again." : "Lookup failed, try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Lookup interrupted.");
        }
        if (hypixel) trackLimit(res);
        int code = res.statusCode();
        if (code == 204 || code == 404) throw new NotFoundException();
        if (code == 403 && hypixel) throw new ApiException("The bot's Hypixel API key is invalid.");
        if (code == 429) throw new ApiException(hypixel ? "Hypixel API limit reached, try again in a minute."
                : "Too many lookups, try again in a minute.");
        if (code >= 500) throw new ApiException(hypixel ? "Hypixel API is having trouble, try again later." : "Lookup failed, try again later.");
        if (code != 200) throw new ApiException("Lookup failed (HTTP " + code + ").");
        JsonElement e;
        try {
            e = JsonParser.parseString(res.body());
        } catch (RuntimeException ex) {
            throw new ApiException("Got a bad response, try again.");
        }
        if (!e.isJsonObject()) throw new ApiException("Got a bad response, try again.");
        JsonObject o = e.getAsJsonObject();
        if (hypixel && o.has("success") && !o.get("success").getAsBoolean()) {
            String cause = o.has("cause") ? o.get("cause").getAsString() : "unknown error";
            throw new ApiException("Hypixel API: " + cause);
        }
        return o;
    }

    private void trackLimit(HttpResponse<?> res) {
        res.headers().firstValue("RateLimit-Remaining").ifPresent(v -> {
            try {
                remaining = Integer.parseInt(v.strip());
            } catch (NumberFormatException ignored) {
            }
        });
        res.headers().firstValue("RateLimit-Reset").ifPresent(v -> {
            try {
                resetAt = System.currentTimeMillis() + Long.parseLong(v.strip()) * 1000;
            } catch (NumberFormatException ignored) {
            }
        });
    }

    /** Tiny time-based cache. Failures are not cached. */
    private static final class Cache<V> {
        private record Slot<V>(V value, long expires) {}

        private final int max;
        private final Map<String, Slot<V>> map;

        Cache(int max) {
            this.max = max;
            this.map = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Slot<V>> eldest) {
                    return size() > Cache.this.max;
                }
            };
        }

        V get(String k, long ttl, Supplier<? extends V> load) {
            synchronized (this) {
                Slot<V> e = map.get(k);
                if (e != null && e.expires() > System.currentTimeMillis()) return e.value();
            }
            V v = load.get();
            synchronized (this) {
                map.put(k, new Slot<>(v, System.currentTimeMillis() + ttl));
            }
            return v;
        }
    }
}
