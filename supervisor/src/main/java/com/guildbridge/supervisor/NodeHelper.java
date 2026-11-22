package com.guildbridge.supervisor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the Node helper (networth and weight, using SkyHelper-Networth, lilyweight and
 * senitherweight) and talks to it with JSON lines. The helper files ship inside this jar and are
 * written to {@code <bridge>/node-helper}; on first use their libraries are installed with npm.
 */
final class NodeHelper {
    private static final String[] FILES = {"helper.js", "package.json"};

    private final Path dir;
    private final Map<Long, CompletableFuture<JsonObject>> waiting = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private Process process;
    private BufferedWriter out;
    private volatile String problem; // why the helper isn't usable
    private volatile long failedAt;

    NodeHelper(Path bridgeDir) {
        this.dir = bridgeDir.resolve("node-helper");
    }

    /** Sends a request and waits up to {@code seconds} for the reply. Throws ApiException with a player-facing message. */
    JsonObject call(JsonObject request, int seconds) {
        ensureRunning();
        long id = ids.incrementAndGet();
        request.addProperty("id", id);
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        waiting.put(id, f);
        try {
            synchronized (this) {
                out.write(request.toString());
                out.write('\n');
                out.flush();
            }
            JsonObject reply = f.get(seconds, TimeUnit.SECONDS);
            if (!reply.has("ok") || !reply.get("ok").getAsBoolean()) {
                String err = reply.has("error") ? reply.get("error").getAsString() : "unknown error";
                Main.LOG.warn("Node helper error: {}", err);
                throw new HypixelApi.ApiException("Calculation failed, try again later.");
            }
            return reply;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new HypixelApi.ApiException("Calculation took too long, try again.");
        } catch (IOException | java.util.concurrent.ExecutionException e) {
            stop();
            throw new HypixelApi.ApiException("Calculation failed, try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HypixelApi.ApiException("Calculation interrupted.");
        } finally {
            waiting.remove(id);
        }
    }

    private synchronized void ensureRunning() {
        if (process != null && process.isAlive()) return;
        // After a failed start, wait a while before trying again (Node may get installed meanwhile).
        if (problem != null && System.currentTimeMillis() - failedAt < 10 * 60_000) {
            throw new HypixelApi.ApiException("Networth and weight need setup on the server.");
        }
        try {
            writeFiles();
            if (!Files.isDirectory(dir.resolve("node_modules"))) install();
            ProcessBuilder pb = new ProcessBuilder(nodeCommand("node"), "helper.js").directory(dir.toFile())
                    .redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve("helper.log").toFile()));
            process = pb.start();
            out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Process p = process;
            Thread reader = new Thread(() -> readLoop(p), "node-helper-reader");
            reader.setDaemon(true);
            reader.start();
            problem = null;
            Main.LOG.info("Node helper started");
        } catch (IOException e) {
            problem = e.getMessage();
            failedAt = System.currentTimeMillis();
            Main.LOG.warn("Node helper unavailable ({}). Install Node.js to enable !nw and !weight.", problem);
            throw new HypixelApi.ApiException("Networth and weight need setup on the server.");
        }
    }

    private void readLoop(Process p) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    CompletableFuture<JsonObject> f = waiting.get(o.get("id").getAsLong());
                    if (f != null) f.complete(o);
                } catch (RuntimeException ignored) {
                    // not one of our replies
                }
            }
        } catch (IOException ignored) {
        }
        waiting.values().forEach(f -> f.completeExceptionally(new IOException("helper exited")));
    }

    /** Writes the helper files from the jar when they are missing or changed. */
    private void writeFiles() throws IOException {
        Files.createDirectories(dir);
        for (String name : FILES) {
            byte[] bundled;
            try (InputStream in = NodeHelper.class.getResourceAsStream("/node-helper/" + name)) {
                if (in == null) throw new IOException("helper file missing from jar: " + name);
                bundled = in.readAllBytes();
            }
            Path target = dir.resolve(name);
            if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bundled)) {
                Files.write(target, bundled);
                if (name.equals("package.json")) {
                    // New library versions: install again.
                    deleteTree(dir.resolve("node_modules"));
                }
            }
        }
    }

    private void install() throws IOException {
        Main.LOG.info("Installing Node helper libraries (one time, about a minute)...");
        ProcessBuilder pb = new ProcessBuilder(nodeCommand("npm"), "install", "--omit=dev", "--no-audit", "--no-fund")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("helper.log").toFile()));
        Process p = pb.start();
        try {
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("npm install took too long");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (p.exitValue() != 0) throw new IOException("npm install failed, see node-helper/helper.log");
        Main.LOG.info("Node helper libraries installed");
    }

    /** "npm" is "npm.cmd" on Windows. */
    private static String nodeCommand(String name) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return windows && name.equals("npm") ? "npm.cmd" : name;
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path x : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }

    /** Starts the helper in the background so the first !nw doesn't wait for npm. */
    void warmUp() {
        Thread t = new Thread(() -> {
            try {
                JsonObject ping = new JsonObject();
                ping.addProperty("op", "ping");
                call(ping, 120);
            } catch (RuntimeException ignored) {
                // logged inside
            }
        }, "node-helper-warmup");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
