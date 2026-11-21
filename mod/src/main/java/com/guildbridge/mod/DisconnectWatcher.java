package com.guildbridge.mod;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Reports why the client was disconnected, with the exact text Minecraft shows: kicks, bans,
 * network errors, failed logins and failed connects all end on the disconnected screen.
 * The supervisor classifies the text (core DisconnectKind) and decides what to do.
 *
 * <p>Minecraft 26.x is unobfuscated, so the screen's text fields can be read by reflection
 * without depending on exact field names. If no screen shows up, a generic reason is sent.
 */
final class DisconnectWatcher {
    private static final long DEDUPE_MILLIS = 5_000;
    private static final long FALLBACK_DELAY_SECONDS = 3;

    private final Consumer<String> report;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "guildbridge-disconnect");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastReportAt;

    DisconnectWatcher(Consumer<String> report) {
        this.report = report;
    }

    /** Call from ScreenEvents.AFTER_INIT. */
    void screenOpened(Screen screen) {
        if (screen instanceof DisconnectedScreen) reportOnce(reasonOf(screen));
    }

    /** Call from ClientPlayConnectionEvents.DISCONNECT. Sends a generic reason if no screen explains it. */
    void disconnected() {
        long at = System.currentTimeMillis();
        timer.schedule(() -> {
            if (lastReportAt < at) reportOnce("Disconnected (no reason shown)");
        }, FALLBACK_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void reportOnce(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastReportAt < DEDUPE_MILLIS) return;
        lastReportAt = now;
        GuildBridgeClient.LOG.info("Disconnected: {}", reason);
        report.accept(reason);
    }

    /** Collects every piece of text on the disconnected screen: title, reason, details. */
    static String reasonOf(Screen screen) {
        Set<String> parts = new LinkedHashSet<>();
        try {
            add(parts, screen.getTitle());
        } catch (RuntimeException ignored) {
        }
        for (Class<?> c = screen.getClass(); c != null && c != Screen.class && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(screen);
                    if (v instanceof Component comp) {
                        add(parts, comp);
                    } else if (v != null && v.getClass().isRecord()) {
                        for (RecordComponent rc : v.getClass().getRecordComponents()) {
                            Object x = rc.getAccessor().invoke(v);
                            if (x instanceof Component cc) add(parts, cc);
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Field not readable; skip it.
                }
            }
        }
        String joined = String.join(" | ", parts);
        return joined.isBlank() ? "Disconnected (no reason shown)" : joined;
    }

    private static void add(Set<String> parts, Component c) {
        if (c == null) return;
        String s = c.getString().strip();
        if (!s.isEmpty() && s.length() < 1000) parts.add(s);
    }
}
