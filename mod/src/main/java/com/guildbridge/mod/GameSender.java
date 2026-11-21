package com.guildbridge.mod;

import com.guildbridge.core.DuplicateGuard;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/**
 * Sends commands like "gc hello" in game, one at a time with a gap between them (Hypixel's
 * chat cooldown). Chat can be checked against the last 3 sends (Hypixel rejects repeats).
 */
final class GameSender {
    private static final int MAX_QUEUED = 20;

    private record Pending(String command, boolean checkRepeats, Runnable onSent, Runnable onRejected) {}

    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(MAX_QUEUED);
    private final DuplicateGuard duplicates = new DuplicateGuard(3);
    private volatile long gapMillis;

    GameSender(long gapMillis) {
        this.gapMillis = gapMillis;
        Thread t = new Thread(this::loop, "guildbridge-game");
        t.setDaemon(true);
        t.start();
    }

    void setGap(long millis) {
        gapMillis = Math.max(500, millis);
    }

    /** Chat that must not repeat one of the last 3 sends. */
    void queueChat(String command, Runnable onSent, Runnable onRejected) {
        offer(new Pending(command, true, onSent, onRejected));
    }

    /** Commands that may repeat (locraw, limbo, g online...). */
    void queueUtility(String command) {
        offer(new Pending(command, false, null, null));
    }

    void queueUtility(String command, Runnable onSent, Runnable onRejected) {
        offer(new Pending(command, false, onSent, onRejected));
    }

    private void offer(Pending p) {
        if (p.command() == null || p.command().isBlank()) {
            run(p.onRejected());
            return;
        }
        if (!queue.offer(p)) {
            GuildBridgeClient.LOG.warn("Game send queue full, dropped: {}", p.command());
            run(p.onRejected());
        }
    }

    private void loop() {
        while (true) {
            try {
                Pending p = queue.poll(1, TimeUnit.SECONDS);
                if (p == null) continue;
                send(p);
                Thread.sleep(gapMillis);
            } catch (InterruptedException ignored) {
            } catch (RuntimeException e) {
                GuildBridgeClient.LOG.warn("Game send failed: {}", e.toString());
            }
        }
    }

    private void send(Pending p) {
        if (p.checkRepeats() && !duplicates.tryRecord(p.command())) {
            GuildBridgeClient.LOG.info("Skipped repeat of a recent message: {}", p.command());
            run(p.onRejected());
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                if (mc.player == null) {
                    run(p.onRejected()); // not connected, nothing was sent
                    return;
                }
                mc.player.connection.sendCommand(p.command());
                run(p.onSent());
            } catch (RuntimeException e) {
                GuildBridgeClient.LOG.warn("Could not send command: {}", e.toString());
                run(p.onRejected());
            }
        });
    }

    private static void run(Runnable r) {
        if (r == null) return;
        try {
            r.run();
        } catch (RuntimeException e) {
            GuildBridgeClient.LOG.warn("Callback failed: {}", e.toString());
        }
    }
}
