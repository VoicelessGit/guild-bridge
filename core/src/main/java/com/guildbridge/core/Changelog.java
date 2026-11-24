package com.guildbridge.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads guildbridge/changelog.md ("## 0.3.1" headings, newest first). */
public final class Changelog {
    public record Entry(String version, String notes) {}

    private Changelog() {}

    /** The newest entry, or null if the changelog is missing. */
    public static Entry latest() {
        var all = all();
        return all.isEmpty() ? null : all.get(0);
    }

    /** The entry for a version like "0.3.1", or null. */
    public static Entry find(String version) {
        for (Entry e : all()) {
            if (e.version().equals(version)) return e;
        }
        return null;
    }

    /** Every entry, newest first. */
    public static java.util.List<Entry> all() {
        String text = read();
        java.util.List<Entry> out = new java.util.ArrayList<>();
        if (text == null) return out;
        int start = text.indexOf("## ");
        while (start >= 0) {
            int lineEnd = text.indexOf('\n', start);
            if (lineEnd < 0) break;
            String version = text.substring(start + 3, lineEnd).strip();
            int next = text.indexOf("\n## ", lineEnd);
            String notes = (next < 0 ? text.substring(lineEnd) : text.substring(lineEnd, next)).strip();
            out.add(new Entry(version, notes));
            start = next < 0 ? -1 : next + 1;
        }
        return out;
    }

    private static String read() {
        try (InputStream in = Changelog.class.getResourceAsStream("/guildbridge/changelog.md")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
