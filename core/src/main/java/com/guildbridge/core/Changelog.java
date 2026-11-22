package com.guildbridge.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads guildbridge/changelog.md ("## 0.3.1" headings, newest first). */
public final class Changelog {
    public record Entry(String version, String notes) {}

    private Changelog() {}

    /** The newest entry, or null if the changelog is missing. */
    public static Entry latest() {
        String text = read();
        if (text == null) return null;
        int start = text.indexOf("## ");
        if (start < 0) return null;
        int lineEnd = text.indexOf('\n', start);
        if (lineEnd < 0) return null;
        String version = text.substring(start + 3, lineEnd).strip();
        int next = text.indexOf("\n## ", lineEnd);
        String notes = (next < 0 ? text.substring(lineEnd) : text.substring(lineEnd, next)).strip();
        return new Entry(version, notes);
    }

    private static String read() {
        try (InputStream in = Changelog.class.getResourceAsStream("/guildbridge/changelog.md")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
