package com.guildbridge.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Helpers for turning captured game chat into a Discord reply, and for uptime text. */
public final class CommandResponse {
    private static final Pattern SEPARATOR = Pattern.compile("^[-_▬]{10,}$");
    /** Keeps a capture from growing without limit if chat is very busy. */
    public static final int MAX_LINES = 60;

    private CommandResponse() {}

    /** Drops separator bars and blank lines, strips color codes, caps the line count. */
    public static String clean(List<String> rawMessages) {
        List<String> lines = new ArrayList<>();
        for (String msg : rawMessages) {
            for (String raw : TextUtil.stripFormatting(msg).split("\\R")) {
                String line = raw.strip();
                if (line.isEmpty() || SEPARATOR.matcher(line).matches()) continue;
                if (lines.size() >= MAX_LINES) {
                    lines.add("…");
                    return String.join("\n", lines);
                }
                lines.add(line);
            }
        }
        return String.join("\n", lines);
    }

    /** "3d 4h 12m", "2h 5m", "45s". */
    public static String formatDuration(long millis) {
        Duration d = Duration.ofMillis(Math.max(0, millis));
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + d.toSecondsPart() + "s";
        return d.toSecondsPart() + "s";
    }
}
