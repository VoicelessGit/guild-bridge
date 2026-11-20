package com.guildbridge.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Remembers the last few messages the bot sent in game and rejects repeats.
 * Hypixel refuses identical chat sent close together, so a repeat would fail anyway.
 */
public final class DuplicateGuard {
    private final int window;
    private final Deque<String> recent = new ArrayDeque<>();

    public DuplicateGuard(int window) {
        this.window = window;
    }

    /** Returns true and records the message if it is new; false if it matches one of the last N. */
    public synchronized boolean tryRecord(String message) {
        String key = normalize(message);
        if (recent.contains(key)) return false;
        recent.addLast(key);
        while (recent.size() > window) recent.removeFirst();
        return true;
    }

    private static String normalize(String s) {
        return s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
