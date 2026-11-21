package com.guildbridge.supervisor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts Minecraft through HeadlessMc and keeps it running. If it exits without being asked to,
 * the supervisor decides how long to wait before starting it again (longer during a crash loop).
 * Stopping asks the mod to quit cleanly first, then ends the whole process tree if needed.
 */
final class GameProcess {
    interface Listener {
        void onStarted();

        /** Called when the game exits. Return the seconds to wait before restarting, or -1 for no restart. */
        int onExited(int exitCode, boolean expected);
    }

    private static final long GRACEFUL_STOP_SECONDS = 20;

    private final SupervisorConfig cfg;
    private final Path dir;
    private final String ipcSecret;
    private final Listener listener;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "game-process");
        t.setDaemon(true);
        return t;
    });

    private Process process;
    private volatile boolean wanted;
    private volatile boolean stopping;
    private volatile long startedAt;

    GameProcess(SupervisorConfig cfg, Path dir, String ipcSecret, Listener listener) {
        this.cfg = cfg;
        this.dir = dir;
        this.ipcSecret = ipcSecret;
        this.listener = listener;
    }

    synchronized boolean running() {
        return process != null && process.isAlive();
    }

    long startedAt() {
        return startedAt;
    }

    boolean wanted() {
        return wanted;
    }

    /** Starts the game if it is not running. Returns a short message for Discord. */
    synchronized String start() {
        wanted = true;
        if (running()) return "The game is already running.";
        try {
            launch();
            return "Starting the game. It joins Hypixel by itself in about a minute.";
        } catch (IOException e) {
            Main.LOG.error("Could not start the game: {}", e.toString());
            return "Could not start the game: " + e.getMessage();
        }
    }

    /** Stops the game and keeps it stopped. askModToQuit is tried first for a clean exit. */
    synchronized String stop(Runnable askModToQuit) {
        wanted = false;
        if (!running()) return "The game is not running.";
        shutDown(askModToQuit, null);
        return "Stopping the game. The bot stays online; use `!start` to bring it back.";
    }

    /** Stops the game, then starts it again. */
    synchronized String restart(Runnable askModToQuit) {
        wanted = true;
        if (!running()) return start();
        shutDown(askModToQuit, () -> {
            synchronized (this) {
                if (wanted && !running()) start();
            }
        });
        return "Restarting the game.";
    }

    /** Called on supervisor shutdown: stop the game and wait for it. */
    void shutdownAndWait(Runnable askModToQuit) {
        Process p;
        synchronized (this) {
            wanted = false;
            p = process;
        }
        if (p == null || !p.isAlive()) return;
        stopping = true;
        askModToQuit.run();
        try {
            if (!p.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) killTree(p);
        } catch (InterruptedException e) {
            killTree(p);
        }
    }

    private void launch() throws IOException {
        Path hmc = cfg.headlessMc(dir);
        if (!Files.exists(hmc)) {
            throw new IOException("HeadlessMc launcher not found at " + hmc
                    + " (put it next to the supervisor jar or set headlessMcPath)");
        }
        hmc.toFile().setExecutable(true);
        String jvm = cfg.jvmArgs
                + " -Dguildbridge.ipc.port=" + cfg.ipcPort
                + " -Dguildbridge.ipc.secret=" + ipcSecret;
        List<String> cmd = List.of(hmc.toAbsolutePath().toString(),
                "launch", "fabric", cfg.minecraftVersion, "-lwjgl", "--jvm", jvm);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(hmc.toAbsolutePath().getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("game-console.log").toFile())
                .redirectInput(ProcessBuilder.Redirect.from(nullInput()));
        stopping = false;
        process = pb.start();
        startedAt = System.currentTimeMillis();
        Main.LOG.info("Game started (pid {})", process.pid());
        listener.onStarted();

        Process mine = process;
        mine.onExit().thenAccept(p -> exited(mine));
    }

    private void exited(Process p) {
        boolean expected;
        synchronized (this) {
            if (p != process) return; // an old process
            expected = stopping || !wanted;
        }
        int code = p.exitValue();
        Main.LOG.info("Game exited with code {} ({})", code, expected ? "expected" : "unexpected");
        int delay = listener.onExited(code, expected);
        if (!expected && delay >= 0) {
            exec.schedule(() -> {
                synchronized (this) {
                    if (wanted && !running()) start();
                }
            }, delay, TimeUnit.SECONDS);
        }
    }

    private void shutDown(Runnable askModToQuit, Runnable afterExit) {
        Process p = process;
        stopping = true;
        try {
            askModToQuit.run();
        } catch (RuntimeException ignored) {
        }
        exec.execute(() -> {
            try {
                if (!p.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                    Main.LOG.warn("Game did not quit in {} s, ending it", GRACEFUL_STOP_SECONDS);
                    killTree(p);
                    p.waitFor(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ignored) {
            }
            if (afterExit != null) afterExit.run();
        });
    }

    /** Ends HeadlessMc and the Minecraft process it started. */
    private static void killTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException ignored) {
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static java.io.File nullInput() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return new java.io.File(os.contains("win") ? "NUL" : "/dev/null");
    }
}
