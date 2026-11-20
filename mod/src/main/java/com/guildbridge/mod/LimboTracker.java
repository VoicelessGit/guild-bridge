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
    private final boolean autoLimbo;
    private final long autoLimboDelayMillis;
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

    LimboTracker(GameSender game, Consumer<State> onChange, boolean autoLimbo, long autoLimboDelayMillis) {
        this.game = game;
        this.onChange = onChange;
        this.autoLimbo = autoLimbo;
        this.autoLimboDelayMillis = autoLimboDelayMillis;
        timer.scheduleWithFixedDelay(this::check, PERIODIC_MINUTES, PERIODIC_MINUTES, TimeUnit.MINUTES);
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
        timer.schedule(this::check, CHECK_DELAY_SECONDS, TimeUnit.SECONDS);
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
        if (info.isEmpty()) return true;
        awaitingReplyUntil = 0;
        lastServer = info.get().server();
        boolean inLimbo = info.get().inLimbo();
        set(inLimbo ? State.IN_LIMBO : State.ON_HYPIXEL_NOT_LIMBO);
        if (!inLimbo) scheduleLimbo();
        return false;
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
        timer.schedule(this::attemptLimbo, autoLimboDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void attemptLimbo() {
        if (state != State.ON_HYPIXEL_NOT_LIMBO) {
            limboPending.set(false);
            return;
        }
        long wait = LIMBO_MIN_GAP_MILLIS - (System.currentTimeMillis() - lastLimboAttempt);
        if (wait > 0) {
            // Too soon after the last try: try again once the gap has passed, not 10 minutes later.
            timer.schedule(this::attemptLimbo, wait, TimeUnit.MILLISECONDS);
            return;
        }
        limboPending.set(false);
        lastLimboAttempt = System.currentTimeMillis();
        GuildBridgeClient.LOG.info("Not in limbo (server: {}), sending /limbo", lastServer);
        game.queueUtility("limbo");
        // Moving to limbo is a world change, which triggers a fresh /locraw by itself.
        // This extra check catches the case where /limbo did nothing.
        timer.schedule(this::check, 10, TimeUnit.SECONDS);
    }

    private void check() {
        if (!onHypixel()) {
            set(State.NOT_ON_HYPIXEL);
            return;
        }
        awaitingReplyUntil = System.currentTimeMillis() + REPLY_WINDOW_MILLIS;
        game.queueUtility("locraw");
    }

    private void set(State next) {
        if (next == state) return;
        state = next;
        stateSince = System.currentTimeMillis();
        onChange.accept(next);
    }

    static boolean onHypixel() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
    }
}
