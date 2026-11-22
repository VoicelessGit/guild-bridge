package com.guildbridge.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The private protocol between the mod (inside Minecraft) and the supervisor (always running).
 * One JSON object per line over a TCP connection bound to 127.0.0.1. The mod connects and must
 * send a hello with the shared secret first; anything else is dropped.
 *
 * <p>Mod to supervisor: hello, heartbeat, event, state, joined, disconnect, capture, sent, rejected.
 * <br>Supervisor to mod: settings, command, autojoin, stop.
 */
public final class Ipc {
    private Ipc() {}

    // ---- message builders ----

    public static JsonObject msg(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    public static JsonObject hello(String secret, String account) {
        JsonObject o = msg("hello");
        o.addProperty("secret", secret);
        o.addProperty("account", account);
        return o;
    }

    /** Hello that also tells the supervisor the mod's version, for !version. */
    public static JsonObject hello(String secret, String account, BuildInfo mod) {
        JsonObject o = hello(secret, account);
        o.addProperty("version", mod.version());
        o.addProperty("built", mod.built());
        return o;
    }

    public static JsonObject event(BridgeEvent e) {
        JsonObject o = msg("event");
        o.add("event", toJson(e));
        return o;
    }

    public static JsonObject command(long id, String command, boolean checkRepeats, boolean capture) {
        JsonObject o = msg("command");
        o.addProperty("id", id);
        o.addProperty("command", command);
        o.addProperty("checkRepeats", checkRepeats);
        o.addProperty("capture", capture);
        return o;
    }

    /** pausedMinutes: 0 = resume, negative = paused until told otherwise, positive = paused that long. */
    public static JsonObject autoJoin(int pausedMinutes) {
        JsonObject o = msg("autojoin");
        o.addProperty("pausedMinutes", pausedMinutes);
        return o;
    }

    /** Parses one line. Returns null for anything that is not a JSON object with a type. */
    public static JsonObject parse(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(line);
            if (!el.isJsonObject()) return null;
            JsonObject o = el.getAsJsonObject();
            return o.has("type") && o.get("type").isJsonPrimitive() ? o : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String type(JsonObject o) {
        return o.get("type").getAsString();
    }

    public static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    public static long num(JsonObject o, String key, long fallback) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean bool(JsonObject o, String key, boolean fallback) {
        try {
            return o.has(key) ? o.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---- BridgeEvent <-> JSON ----

    public static JsonObject toJson(BridgeEvent e) {
        JsonObject o = new JsonObject();
        switch (e) {
            case BridgeEvent.Chat c -> {
                o.addProperty("kind", "chat");
                o.addProperty("channel", c.channel().name());
                o.addProperty("rank", c.rank());
                o.addProperty("name", c.name());
                o.addProperty("guildRank", c.guildRank());
                o.addProperty("message", c.message());
            }
            case BridgeEvent.Status s -> {
                o.addProperty("kind", "status");
                o.addProperty("name", s.name());
                o.addProperty("joined", s.joined());
            }
            case BridgeEvent.Announcement a -> {
                o.addProperty("kind", "announcement");
                o.addProperty("text", a.text());
                o.addProperty("joinedMember", a.joinedMember());
            }
        }
        return o;
    }

    /** Returns null if the object is not a valid event. */
    public static BridgeEvent toEvent(JsonObject o) {
        if (o == null) return null;
        String kind = str(o, "kind");
        if (kind == null) return null;
        try {
            return switch (kind) {
                case "chat" -> {
                    String name = str(o, "name");
                    String message = str(o, "message");
                    if (name == null || message == null) yield null;
                    BridgeEvent.Channel ch = "OFFICER".equals(str(o, "channel"))
                            ? BridgeEvent.Channel.OFFICER : BridgeEvent.Channel.GUILD;
                    yield new BridgeEvent.Chat(ch, str(o, "rank"), name, str(o, "guildRank"), message);
                }
                case "status" -> {
                    String name = str(o, "name");
                    yield name == null ? null : new BridgeEvent.Status(name, bool(o, "joined", true));
                }
                case "announcement" -> {
                    String text = str(o, "text");
                    yield text == null ? null : new BridgeEvent.Announcement(text, str(o, "joinedMember"));
                }
                default -> null;
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
