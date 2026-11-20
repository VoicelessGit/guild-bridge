package com.guildbridge.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;

/**
 * Parses Hypixel's /locraw reply, a single chat line of JSON such as
 * {@code {"server":"limbo"}} or {@code {"server":"dynamiclobby12A","gametype":"MAIN","lobbyname":"mainlobby1"}}.
 *
 * <p>NOTE: the exact limbo reply is from community knowledge; confirm it in game and add the real
 * line to LocrawTest.
 */
public final class Locraw {
    public record Info(String server, String gametype, String lobbyname) {
        public boolean inLimbo() {
            return "limbo".equalsIgnoreCase(server);
        }

        public boolean inLobby() {
            return lobbyname != null;
        }
    }

    private Locraw() {}

    /** Returns the parsed reply, or empty if the line is not a /locraw reply. */
    public static Optional<Info> parse(String line) {
        if (line == null) return Optional.empty();
        String s = TextUtil.stripFormatting(line).strip();
        if (!s.startsWith("{") || !s.endsWith("}") || !s.contains("\"server\"")) return Optional.empty();
        try {
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            return Optional.of(new Info(str(o, "server"), str(o, "gametype"), str(o, "lobbyname")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
