package com.guildbridge.mod;

import com.guildbridge.core.DuplicateGuard;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/**
 * Sends commands like "gc hello" in game, one at a time with a gap between them
 * (Hypixel's chat cooldown), skipping anything that matches one of the last 3 sends.
 */
final class GameSender {
    private static final int MAX_QUEUED = 20;

    private record Pending(String command, Runnable onRejected, boolean checkRepeats) {}

    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(MAX_QUEUED);
    private final DuplicateGuard duplicates = new DuplicateGuard(3);
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "guildbridge-game");
        t.setDaemon(true);
        return t;
    });

    GameSender(long gapMillis) {
        ticker.scheduleWithFixedDelay(this::sendNext, gapMillis, gapMillis, TimeUnit.MILLISECONDS);
    }

    void queue(String command) {
        queue(command, null);
    }

    /**
     * Queues a command (no leading slash). onRejected runs if it is dropped (queue full)
     * or skipped as a repeat, so the Discord side can react with an X.
     */
    void queue(String command, Runnable onRejected) {
        offer(new Pending(command, onRejected, true));
    }

    /** For utility commands like "locraw" that may repeat; skips the repeat check. */
    void queueUtility(String command) {
        offer(new Pending(command, null, false));
    }

    private void offer(Pending p) {
        String command = p.command();
        Runnable onRejected = p.onRejected();
        if (command == null) return;
        if (!queue.offer(p)) {
            GuildBridgeClient.LOG.warn("Game send queue full, dropped: {}", command);
            reject(onRejected);
        }
    }

    private void sendNext() {
        Pending p = queue.poll();
        if (p == null) return;
        if (p.checkRepeats() && !duplicates.tryRecord(p.command())) {
            GuildBridgeClient.LOG.info("Skipped repeat of a recent message: {}", p.command());
            reject(p.onRejected());
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.connection.sendCommand(p.command());
        });
    }

    private static void reject(Runnable onRejected) {
        if (onRejected != null) onRejected.run();
    }
}
