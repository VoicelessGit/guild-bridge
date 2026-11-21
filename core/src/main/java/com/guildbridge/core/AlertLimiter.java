package com.guildbridge.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps alert pings useful: the same problem pings at most once per cooldown, and repeated
 * small problems (reconnects, login hiccups) only ping once they pile up inside a time window.
 */
public final class AlertLimiter {
    private final long cooldownMillis;
    private final Map<String, Long> lastAlert = new HashMap<>();
    private final Map<String, Deque<Long>> windows = new HashMap<>();

    public AlertLimiter(long cooldownMillis) {
        this.cooldownMillis = cooldownMillis;
    }

    /** True if an alert with this key may ping now; records it if so. */
    public synchronized boolean allow(String key, long now) {
        Long last = lastAlert.get(key);
        if (last != null && now - last < cooldownMillis) return false;
        lastAlert.put(key, now);
        return true;
    }

    /** Forgets the cooldown for a key, e.g. after the problem was fixed. */
    public synchronized void reset(String key) {
        lastAlert.remove(key);
    }

    /**
     * Records one occurrence and returns how many happened inside the window, including this one.
     * Old entries are dropped, so memory stays bounded.
     */
    public synchronized int count(String key, long now, long windowMillis) {
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > windowMillis) q.removeFirst();
        while (q.size() > 100) q.removeFirst();
        return q.size();
    }
}
