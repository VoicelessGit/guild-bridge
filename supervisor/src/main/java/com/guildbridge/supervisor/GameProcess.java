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

    /** The launcher process we started, or one a previous supervisor started and we reattached to. */
    private ProcessHandle process;
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

    /**
     * Takes over a game a previous supervisor left running (after an update or a supervisor
     * crash). True when the saved process is still the same game.
     */
    synchronized boolean adopt() {
        ProcessHandle h = readPidFile();
        if (h == null) return false;
        wanted = true;
        stopping = false;
        process = h;
        startedAt = System.currentTimeMillis(); // give the mod time to reconnect before the watchdog judges it
        Main.LOG.info("Reattached to the running game (pid {})", h.pid());
        listener.onStarted();
        ProcessHandle mine = h;
        mine.onExit().thenAccept(x -> exited(mine, -1));
        return true;
    }

    private Path pidFile() {
        return dir.resolve(".game.pid");
    }

    private void writePidFile(ProcessHandle h) {
        try {
            long start = h.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
            Files.writeString(pidFile(), h.pid() + " " + start + "\n");
        } catch (IOException e) {
            Main.LOG.warn("Could not save the game's process id: {}", e.toString());
        }
    }

    private ProcessHandle readPidFile() {
        try {
            if (!Files.exists(pidFile())) return null;
            String[] parts = Files.readString(pidFile()).strip().split("\\s+");
            ProcessHandle h = ProcessHandle.of(Long.parseLong(parts[0])).orElse(null);
            if (h == null || !h.isAlive()) return null;
            long start = h.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
            // Same pid but a different start time means the pid was reused by something else.
            if (parts.length > 1 && Long.parseLong(parts[1]) != 0 && start != 0 && Long.parseLong(parts[1]) != start) return null;
            return h;
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
        ProcessHandle p;
        synchronized (this) {
            wanted = false;
            p = process;
        }
        if (p == null || !p.isAlive()) return;
        stopping = true;
        askModToQuit.run();
        if (!waitFor(p, GRACEFUL_STOP_SECONDS)) killTree(p);
    }

    /** Leaves the game running for the next supervisor (supervisor-only updates). */
    synchronized void detach() {
        process = null;
        Main.LOG.info("Leaving the game running for the next supervisor");
    }

    private static boolean waitFor(ProcessHandle p, long seconds) {
        try {
            p.onExit().get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (Exception e) {
            return !p.isAlive();
        }
    }

    private void launch() throws IOException {
        Path hmc = cfg.headlessMc(dir);
        if (!Files.exists(hmc)) {
            throw new IOException("HeadlessMc launcher not found at " + hmc
                    + " (put it next to the supervisor jar or set headlessMcPath)");
        }
        String jvm = cfg.jvmArgs
                + " -Dguildbridge.ipc.port=" + cfg.ipcPort
                + " -Dguildbridge.ipc.secret=" + ipcSecret;
        List<String> cmd = new java.util.ArrayList<>();
        if (hmc.getFileName().toString().endsWith(".jar")) {
            // The plain jar build of HeadlessMc runs on the same Java as the supervisor.
            cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            cmd.add("-jar");
        } else {
            hmc.toFile().setExecutable(true);
        }
        cmd.add(hmc.toAbsolutePath().toString());
        cmd.addAll(List.of("launch", "fabric", cfg.minecraftVersion, "-lwjgl", "--jvm", jvm));

        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (!windows) {
            // HeadlessMc reads commands from its input. Feed it from "tail -f /dev/null" so the input
            // never ends, even after this supervisor exits and a new one reattaches to the game.
            StringBuilder sh = new StringBuilder("tail -f /dev/null | exec");
            for (String c : cmd) sh.append(' ').append(shellQuote(c));
            cmd = List.of("/bin/sh", "-c", sh.toString());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(hmc.toAbsolutePath().getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("game-console.log").toFile()))
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        stopping = false;
        Process started = pb.start();
        process = started.toHandle();
        startedAt = System.currentTimeMillis();
        writePidFile(process);
        Main.LOG.info("Game started (pid {})", process.pid());
        listener.onStarted();

        ProcessHandle mine = process;
        started.onExit().thenAccept(x -> exited(mine, started.exitValue()));
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void exited(ProcessHandle p, int code) {
        boolean expected;
        synchronized (this) {
            if (p != process) return; // an old process, or one we handed over
            expected = stopping || !wanted;
        }
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
        ProcessHandle p = process;
        stopping = true;
        try {
            askModToQuit.run();
        } catch (RuntimeException ignored) {
        }
        exec.execute(() -> {
            if (!waitFor(p, GRACEFUL_STOP_SECONDS)) {
                Main.LOG.warn("Game did not quit in {} s, ending it", GRACEFUL_STOP_SECONDS);
                killTree(p);
                waitFor(p, 10);
            }
            if (afterExit != null) afterExit.run();
        });
    }

    /** Ends HeadlessMc and the Minecraft process it started. */
    private static void killTree(ProcessHandle p) {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException ignored) {
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }
}
