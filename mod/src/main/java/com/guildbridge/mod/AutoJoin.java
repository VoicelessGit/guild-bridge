package com.guildbridge.mod;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Joins the configured server from the title screen, and rejoins after a disconnect.
 * Waits between attempts and backs off (30 s, 1, 2, 4, 8, then 10 min max) so a kick or an
 * outage never turns into rapid reconnect spam. Off when autoJoinServer is blank.
 */
final class AutoJoin {
    private static final long FIRST_DELAY_MILLIS = 10_000;
    private static final long BASE_DELAY_MILLIS = 30_000;
    private static final long MAX_DELAY_MILLIS = 600_000;

    private final String address;
    /** The open screen, tracked through Fabric's ScreenEvents (Minecraft's own field is not public in 26.x). */
    private volatile Screen currentScreen;
    private long nextAttemptAt;
    private int failures;

    AutoJoin(String address) {
        this.address = address == null ? "" : address.strip();
        this.nextAttemptAt = System.currentTimeMillis() + FIRST_DELAY_MILLIS;
    }

    boolean enabled() {
        return !address.isEmpty();
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
        if (!enabled()) return;
        // The startup delay covers the loading overlay, so no overlay check is needed.
        if (mc.level != null || mc.getConnection() != null) return;
        Screen screen = currentScreen;
        boolean idle = screen instanceof TitleScreen
                || screen instanceof DisconnectedScreen
                || screen instanceof JoinMultiplayerScreen;
        if (!idle) return; // already connecting, or a screen we should not interrupt

        long now = System.currentTimeMillis();
        if (now < nextAttemptAt) return;

        long delay = Math.min(MAX_DELAY_MILLIS, BASE_DELAY_MILLIS << Math.min(failures, 5));
        failures++;
        nextAttemptAt = now + delay;

        GuildBridgeClient.LOG.info("Auto-joining {} (attempt {}, next retry in {} s if this fails)",
                address, failures, delay / 1000);
        ServerData data = new ServerData("Hypixel", address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(screen, mc, ServerAddress.parseString(address), data, false, null);
    }
}
