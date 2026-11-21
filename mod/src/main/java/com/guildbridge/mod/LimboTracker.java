package com.guildbridge.mod;

import com.guildbridge.core.Locraw;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Knows whether the client is in Hypixel limbo by asking /locraw after every world change
 * and every few minutes as a safety net. The reply is hidden from chat.
 *
 * <p>If autoLimbo is on, whenever a /locraw reply says the client is on Hypixel but not in
 * limbo, it sends /limbo after a short delay (default 5 s). Attempts are at least 30 s apart,
 * so a failing /limbo can never turn into spam. Hypixel's AFK system is the fallback.
 */
final class LimboTracker {
    enum State { NOT_ON_HYPIXEL, ON_HYPIXEL_NOT_LIMBO, IN_LIMBO }

    private static final long CHECK_DELAY_SECONDS = 3;    // let the new world settle first
    private static final long PERIODIC_MINUTES = 10;
    private static final long REPLY_WINDOW_MILLIS = 15_000;
    private static final long LIMBO_MIN_GAP_MILLIS = 30_000;

    private final GameSender game;
    private final Consumer<State> onChange;
    private volatile boolean autoLimbo = true;
    private volatile long autoLimboDelayMillis = 5_000;
    private volatile long lastLimboAttempt;
    /** Only one pending /limbo attempt at a time, however many locraw replies arrive. */
    private final java.util.concurrent.atomic.AtomicBoolean limboPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "guildbridge-limbo");
        t.setDaemon(true);
        return t;
    });

    private volatile State state = State.NOT_ON_HYPIXEL;
    private volatile long awaitingReplyUntil;
    private volatile String lastServer;
    private volatile long stateSince = System.currentTimeMillis();

    LimboTracker(GameSender game, Consumer<State> onChange) {
        this.game = game;
        this.onChange = onChange;
        // An exception in a scheduled task would silently cancel it forever, so every task is wrapped.
        timer.scheduleWithFixedDelay(() -> safely(this::check), PERIODIC_MINUTES, PERIODIC_MINUTES, TimeUnit.MINUTES);
    }

    State state() {
        return state;
    }

    String lastServer() {
        return lastServer;
    }

    /** When the current state started, for !uptime. */
    long stateSince() {
        return stateSince;
    }

    /** Call on server join and on every world change. */
    void worldChanged() {
        timer.schedule(() -> safely(this::check), CHECK_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            GuildBridgeClient.LOG.warn("Limbo check error: {}", t.toString());
        }
    }

    void disconnected() {
        set(State.NOT_ON_HYPIXEL);
    }

    /**
     * Call from ClientReceiveMessageEvents.ALLOW_GAME.
     * Returns false to hide the line from chat (it was our /locraw reply).
     */
    boolean allowChatLine(String line) {
        if (System.currentTimeMillis() > awaitingReplyUntil) return true;
        var info = Locraw.parse(line);
        if (info.isEmpty()) {
            if (line.contains("{") && line.contains("server")) {
                GuildBridgeClient.LOG.warn("Could not read this /locraw reply: {}", line);
            }
            return true;
        }
        awaitingReplyUntil = 0;
        GuildBridgeClient.LOG.info("Location: {}", line.strip());
        lastServer = info.get().server();
        boolean inLimbo = info.get().inLimbo();
        set(inLimbo ? State.IN_LIMBO : State.ON_HYPIXEL_NOT_LIMBO);
        if (!inLimbo) scheduleLimbo();
        return false;
    }

    /** Settings from the supervisor. */
    void configure(boolean autoLimbo, long delayMillis) {
        this.autoLimbo = autoLimbo;
        this.autoLimboDelayMillis = Math.max(1_000, delayMillis);
    }

    boolean autoLimbo() {
        return autoLimbo;
    }

    long autoLimboDelayMillis() {
        return autoLimboDelayMillis;
    }

    /** Sends /limbo after the delay, if still not in limbo. Attempts stay at least 30 s apart. */
    private void scheduleLimbo() {
        if (!autoLimbo || !limboPending.compareAndSet(false, true)) return;
        timer.schedule(() -> safely(this::attemptLimbo), autoLimboDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void attemptLimbo() {
        if (state != State.ON_HYPIXEL_NOT_LIMBO) {
            limboPending.set(false);
            return;
        }
        long wait = LIMBO_MIN_GAP_MILLIS - (System.currentTimeMillis() - lastLimboAttempt);
        if (wait > 0) {
            // Too soon after the last try: try again once the gap has passed, not 10 minutes later.
            timer.schedule(() -> safely(this::attemptLimbo), wait, TimeUnit.MILLISECONDS);
            return;
        }
        limboPending.set(false);
        lastLimboAttempt = System.currentTimeMillis();
        GuildBridgeClient.LOG.info("Not in limbo (server: {}), sending /limbo", lastServer);
        game.queueUtility("limbo");
        // Moving to limbo is a world change, which triggers a fresh /locraw by itself.
        // This extra check catches the case where /limbo did nothing.
        timer.schedule(() -> safely(this::check), 10, TimeUnit.SECONDS);
    }

    private void check() {
        if (!onHypixel()) {
            GuildBridgeClient.LOG.info("Location check: not connected to Hypixel");
            set(State.NOT_ON_HYPIXEL);
            return;
        }
        long until = System.currentTimeMillis() + REPLY_WINDOW_MILLIS;
        awaitingReplyUntil = until;
        GuildBridgeClient.LOG.info("Location check: sending /locraw");
        game.queueUtility("locraw", null,
                () -> GuildBridgeClient.LOG.warn("Location check: /locraw could not be sent"));
        timer.schedule(() -> {
            if (awaitingReplyUntil == until) {
                GuildBridgeClient.LOG.warn("Location check: no /locraw reply within {} s", REPLY_WINDOW_MILLIS / 1000);
            }
        }, REPLY_WINDOW_MILLIS + 500, TimeUnit.MILLISECONDS);
    }

    private void set(State next) {
        if (next == state) return;
        state = next;
        stateSince = System.currentTimeMillis();
        onChange.accept(next);
    }

    static boolean onHypixel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return false;
        ServerData server = mc.getCurrentServer();
        // No server data while connected still means a multiplayer server; auto-join only goes to Hypixel.
        if (server == null || server.ip == null) return true;
        String ip = server.ip.toLowerCase(Locale.ROOT);
        return ip.contains("hypixel.net") || ip.contains("hypixel.io");
    }
}
