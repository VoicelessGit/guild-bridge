package com.guildbridge.mod;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Joins the configured server once the game has started, and rejoins after a disconnect.
 *
 * <p>It does not depend on which screen is showing (headless clients may never show a normal
 * title screen). It only checks "not in a world and no connection", and skips while the connect
 * screen is open (an attempt is still in progress). Retry delay doubles from autoJoinRetrySeconds
 * up to autoJoinMaxRetrySeconds and resets after a successful join. Off when autoJoinServer is blank.
 *
 * <p>Settings arrive from the supervisor after it connects. The supervisor can also pause auto-join,
 * for example after a ban (until an admin runs !rejoin) or a duplicate login (10 minutes).
 */
final class AutoJoin {
    private static final long FIRST_DELAY_MILLIS = 5_000;
    /** Never start a new attempt sooner than this after the last one, whatever the config says. */
    private static final long FLOOR_MILLIS = 5_000;

    private volatile String address = "";
    private volatile long baseDelayMillis = 10_000;
    private volatile long maxDelayMillis = 300_000;
    /** Epoch millis until which auto-join is paused; Long.MAX_VALUE = until resumed. */
    private volatile long pausedUntil;
    /** Best-effort: the open screen, used only as the "back" screen for the connect screen. */
    private volatile Screen currentScreen;
    private volatile boolean started;
    private volatile long startedAt;
    private volatile boolean sawScreen;
    private boolean loggedWaiting;
    /** Give up waiting for the loading screen after this long and try anyway. */
    private static final long MAX_LOAD_WAIT_MILLIS = 180_000;
    private long nextAttemptAt;
    private int failures;

    /** Settings from the supervisor. */
    void configure(String address, int retrySeconds, int maxRetrySeconds) {
        this.address = address == null ? "" : address.strip();
        this.baseDelayMillis = Math.max(FLOOR_MILLIS, retrySeconds * 1000L);
        this.maxDelayMillis = Math.max(baseDelayMillis, maxRetrySeconds * 1000L);
        GuildBridgeClient.LOG.info(enabled()
                ? "Auto-join is ON for " + this.address
                : "Auto-join is OFF (autoJoinServer is blank)");
    }

    /** 0 = resume now, negative = pause until resumed, positive = pause that many minutes. */
    void pause(long minutes) {
        if (minutes == 0) {
            pausedUntil = 0;
            failures = 0;
            nextAttemptAt = System.currentTimeMillis(); // try right away
            GuildBridgeClient.LOG.info("Auto-join resumed");
        } else {
            pausedUntil = minutes < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + minutes * 60_000L;
            GuildBridgeClient.LOG.info(minutes < 0 ? "Auto-join paused until resumed" : "Auto-join paused for {} minutes", minutes);
        }
    }

    boolean enabled() {
        return !address.isEmpty();
    }

    /** Call from ClientLifecycleEvents.CLIENT_STARTED. */
    void clientStarted() {
        started = true;
        startedAt = System.currentTimeMillis();
        nextAttemptAt = startedAt + FIRST_DELAY_MILLIS;
        if (enabled()) GuildBridgeClient.LOG.info("Game started, joining once it finishes loading");
    }

    /** Call when a join succeeds, so the next disconnect starts with a short wait again. */
    void joined() {
        failures = 0;
    }

    /** Call from ScreenEvents.AFTER_INIT. */
    void screenOpened(Screen screen) {
        currentScreen = screen;
        if (!(screen instanceof ConnectScreen)) sawScreen = true;
        ScreenEvents.remove(screen).register(closed -> {
            if (currentScreen == closed) currentScreen = null;
        });
    }

    /**
     * True once the startup loading overlay is gone. Read by reflection (the field is private);
     * if that fails, the first real screen or a long timeout counts as loaded.
     */
    private boolean finishedLoading(Minecraft mc, long now) {
        if (now - startedAt > MAX_LOAD_WAIT_MILLIS) return true;
        Boolean overlayGone = overlayGone(mc);
        if (overlayGone != null) return overlayGone;
        return sawScreen;
    }

    private static java.lang.reflect.Field overlayField;
    private static boolean overlayLookupFailed;

    private static Boolean overlayGone(Minecraft mc) {
        try {
            if (overlayField == null && !overlayLookupFailed) {
                for (var f : Minecraft.class.getDeclaredFields()) {
                    if (f.getType().getSimpleName().equals("Overlay")) {
                        f.setAccessible(true);
                        overlayField = f;
                        break;
                    }
                }
                if (overlayField == null) overlayLookupFailed = true;
            }
            if (overlayField == null) return null;
            return overlayField.get(mc) == null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            overlayLookupFailed = true;
            return null;
        }
    }

    /** Call from ClientTickEvents.END_CLIENT_TICK. */
    void tick(Minecraft mc) {
        if (!enabled() || !started) return;
        if (System.currentTimeMillis() < pausedUntil) return;
        if (mc.level != null || mc.getConnection() != null) return; // connected or in a world
        if (currentScreen instanceof ConnectScreen) return;           // an attempt is still connecting

        long now = System.currentTimeMillis();
        // Joining while the game is still loading its resources left the connection half-broken
        // on the server (Nov 2026): Hypixel's messages arrived but nothing we sent went anywhere.
        if (!finishedLoading(mc, now)) {
            if (!loggedWaiting) {
                GuildBridgeClient.LOG.info("Waiting for the game to finish loading before joining");
                loggedWaiting = true;
            }
            nextAttemptAt = Math.max(nextAttemptAt, now + FIRST_DELAY_MILLIS);
            return;
        }
        if (now < nextAttemptAt) return;

        long delay = Math.min(maxDelayMillis, baseDelayMillis << Math.min(failures, 10));
        failures++;
        nextAttemptAt = now + delay;

        GuildBridgeClient.LOG.info("Auto-joining {} (attempt {}, next retry in {} s if this fails)",
                address, failures, delay / 1000);
        Screen parent = currentScreen != null && !(currentScreen instanceof ConnectScreen) ? currentScreen : new TitleScreen();
        ServerData data = new ServerData("Hypixel", address, ServerData.Type.OTHER);
        try {
            ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address), data, false, null);
        } catch (RuntimeException e) {
            GuildBridgeClient.LOG.warn("Auto-join attempt failed to start: {}", e.toString());
        }
    }
}
