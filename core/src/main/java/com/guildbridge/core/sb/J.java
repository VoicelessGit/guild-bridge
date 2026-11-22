package com.guildbridge.core.sb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Null-safe JSON path helpers. Missing values come back as null/NaN instead of throwing. */
public final class J {
    private J() {}

    public static JsonElement at(JsonElement root, String... path) {
        JsonElement cur = root;
        for (String p : path) {
            if (cur == null || !cur.isJsonObject()) return null;
            cur = cur.getAsJsonObject().get(p);
        }
        return cur == null || cur.isJsonNull() ? null : cur;
    }

    public static JsonObject obj(JsonElement root, String... path) {
        JsonElement e = at(root, path);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonElement root, String... path) {
        JsonElement e = at(root, path);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** The number at the path, or NaN when it is missing or not a number. */
    public static double num(JsonElement root, String... path) {
        JsonElement e = at(root, path);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return Double.NaN;
        return e.getAsDouble();
    }

    /** The number at the path, or {@code fallback}. */
    public static double num0(JsonElement root, double fallback, String... path) {
        double v = num(root, path);
        return Double.isNaN(v) ? fallback : v;
    }

    public static String str(JsonElement root, String... path) {
        JsonElement e = at(root, path);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    public static boolean bool(JsonElement root, String... path) {
        JsonElement e = at(root, path);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }
}
