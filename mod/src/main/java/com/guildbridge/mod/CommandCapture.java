package com.guildbridge.mod;

import com.guildbridge.core.CommandResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Captures the game's reply to a command (for example /g online) so it can be posted back
 * to Discord. One capture at a time: reserve() when the command is queued, begin() when it is
 * actually sent, and everything non-guild-chat that arrives in the next few seconds is collected.
 */
final class CommandCapture {
    private static final long WINDOW_MILLIS = 2_500;
    private static final long RESERVATION_TIMEOUT_MILLIS = 20_000;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "guildbridge-capture");
        t.setDaemon(true);
        return t;
    });

    private long reservedAt;          // 0 when free
    private List<String> lines;       // non-null while capturing
    private Consumer<String> onDone;

    /** Claims the capture slot. False if another command is still in progress. */
    synchronized boolean reserve() {
        long now = System.currentTimeMillis();
        boolean stale = reservedAt != 0 && lines == null && now - reservedAt > RESERVATION_TIMEOUT_MILLIS;
        if (reservedAt != 0 && !stale) return false;
        reservedAt = now;
        return true;
    }

    /** Releases a reservation whose command was never sent. */
    synchronized void cancel() {
        if (lines == null) reservedAt = 0;
    }

    /** Starts collecting. Call right after the command is sent in game. */
    synchronized void begin(Consumer<String> onDone) {
        this.lines = new ArrayList<>();
        this.onDone = onDone;
        timer.schedule(this::finish, WINDOW_MILLIS, TimeUnit.MILLISECONDS);
    }

    synchronized boolean active() {
        return lines != null;
    }

    synchronized void add(String message) {
        if (lines != null && lines.size() < 200) lines.add(message);
    }

    private void finish() {
        List<String> collected;
        Consumer<String> done;
        synchronized (this) {
            collected = lines;
            done = onDone;
            lines = null;
            onDone = null;
            reservedAt = 0;
        }
        if (done != null) done.accept(CommandResponse.clean(collected));
    }
}
