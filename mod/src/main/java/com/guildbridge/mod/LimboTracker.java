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
 * <p>It does NOT move the player. Getting into limbo is left to Hypixel's own AFK system,
 * which moves idle players from lobbies to limbo. That keeps the client's behavior normal.
 */
final class LimboTracker {
    enum State { NOT_ON_HYPIXEL, ON_HYPIXEL_NOT_LIMBO, IN_LIMBO }

    private static final long CHECK_DELAY_SECONDS = 3;    // let the new world settle first
    private static final long PERIODIC_MINUTES = 10;
    private static final long REPLY_WINDOW_MILLIS = 15_000;

    private final GameSender game;
    private final Consumer<State> onChange;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "guildbridge-limbo");
        t.setDaemon(true);
        return t;
    });

    private volatile State state = State.NOT_ON_HYPIXEL;
    private volatile long awaitingReplyUntil;
    private volatile String lastServer;

    LimboTracker(GameSender game, Consumer<State> onChange) {
        this.game = game;
        this.onChange = onChange;
        timer.scheduleWithFixedDelay(this::check, PERIODIC_MINUTES, PERIODIC_MINUTES, TimeUnit.MINUTES);
    }

    State state() {
        return state;
    }

    String lastServer() {
        return lastServer;
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
        set(info.get().inLimbo() ? State.IN_LIMBO : State.ON_HYPIXEL_NOT_LIMBO);
        return false;
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
        onChange.accept(next);
    }

    static boolean onHypixel() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
    }
}
