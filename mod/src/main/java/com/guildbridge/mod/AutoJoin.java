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
 * title screen). It only checks "not in a world and no connection". After each attempt it waits
 * at least 60 s before trying again, which is longer than a connection takes, so it never starts
 * a second attempt while the first is still connecting. Backoff: 60 s, 2, 4, 8, then 10 min max.
 * Off when autoJoinServer is blank.
 */
final class AutoJoin {
    private static final long FIRST_DELAY_MILLIS = 10_000;
    private static final long BASE_DELAY_MILLIS = 60_000;
    private static final long MAX_DELAY_MILLIS = 600_000;

    private final String address;
    /** Best-effort: the open screen, used only as the "back" screen for the connect screen. */
    private volatile Screen currentScreen;
    private volatile boolean started;
    private long nextAttemptAt;
    private int failures;

    AutoJoin(String address) {
        this.address = address == null ? "" : address.strip();
        GuildBridgeClient.LOG.info(enabled()
                ? "Auto-join is ON for " + this.address
                : "Auto-join is OFF (autoJoinServer is blank)");
    }

    boolean enabled() {
        return !address.isEmpty();
    }

    /** Call from ClientLifecycleEvents.CLIENT_STARTED. */
    void clientStarted() {
        started = true;
        nextAttemptAt = System.currentTimeMillis() + FIRST_DELAY_MILLIS;
        if (enabled()) GuildBridgeClient.LOG.info("Game started, first auto-join attempt in 10 s");
    }

    /** Call when a join succeeds, so the next disconnect starts with a short wait again. */
    void joined() {
        failures = 0;
    }

    /** Call from ScreenEvents.AFTER_INIT. */
    void screenOpened(Screen screen) {
        currentScreen = screen;
        ScreenEvents.remove(screen).register(closed -> {
            if (currentScreen == closed) currentScreen = null;
        });
    }

    /** Call from ClientTickEvents.END_CLIENT_TICK. */
    void tick(Minecraft mc) {
        if (!enabled() || !started) return;
        if (mc.level != null || mc.getConnection() != null) return; // connected or in a world

        long now = System.currentTimeMillis();
        if (now < nextAttemptAt) return;

        long delay = Math.min(MAX_DELAY_MILLIS, BASE_DELAY_MILLIS << Math.min(failures, 4));
        failures++;
        nextAttemptAt = now + delay;

        GuildBridgeClient.LOG.info("Auto-joining {} (attempt {}, next retry in {} s if this fails)",
                address, failures, delay / 1000);
        Screen parent = currentScreen != null ? currentScreen : new TitleScreen();
        ServerData data = new ServerData("Hypixel", address, ServerData.Type.OTHER);
        try {
            ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address), data, false, null);
        } catch (RuntimeException e) {
            GuildBridgeClient.LOG.warn("Auto-join attempt failed to start: {}", e.toString());
        }
    }
}
