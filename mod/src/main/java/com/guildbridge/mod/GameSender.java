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

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(MAX_QUEUED);
    private final DuplicateGuard duplicates = new DuplicateGuard(3);
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "guildbridge-game");
        t.setDaemon(true);
        return t;
    });

    GameSender(long gapMillis) {
        ticker.scheduleWithFixedDelay(this::sendNext, gapMillis, gapMillis, TimeUnit.MILLISECONDS);
    }

    /** Queues a command (no leading slash). Drops it if the queue is full. */
    void queue(String command) {
        if (command == null) return;
        if (!queue.offer(command)) {
            GuildBridgeClient.LOG.warn("Game send queue full, dropped: {}", command);
        }
    }

    private void sendNext() {
        String cmd = queue.poll();
        if (cmd == null) return;
        if (!duplicates.tryRecord(cmd)) {
            // TODO phase 2: tell the Discord author (for example react with an X).
            GuildBridgeClient.LOG.info("Skipped repeat of a recent message: {}", cmd);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.connection.sendCommand(cmd);
        });
    }
}
