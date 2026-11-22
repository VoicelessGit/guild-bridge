package com.guildbridge.mod;

import com.google.gson.JsonObject;
import com.guildbridge.core.Ipc;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Connection to the supervisor on this computer (127.0.0.1). Keeps reconnecting every few
 * seconds, says hello with the secret the supervisor passed on our command line, and sends a
 * heartbeat every 20 s so the supervisor can tell a hung game from a quiet one.
 */
final class IpcClient {
    private static final long RETRY_MILLIS = 3_000;
    private static final long HEARTBEAT_SECONDS = 20;

    private final int port;
    private final String secret;
    private final Supplier<String> account;
    private final Consumer<JsonObject> onMessage;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "guildbridge-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = true;
    private volatile Socket socket;
    private BufferedWriter out;

    IpcClient(int port, String secret, Supplier<String> account, Consumer<JsonObject> onMessage) {
        this.port = port;
        this.secret = secret;
        this.account = account;
        this.onMessage = onMessage;
    }

    void start() {
        Thread t = new Thread(this::loop, "guildbridge-ipc");
        t.setDaemon(true);
        t.start();
        heartbeat.scheduleWithFixedDelay(() -> send(Ipc.msg("heartbeat")),
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    boolean connected() {
        return socket != null;
    }

    /** Sends a message. False if not connected; game events are simply dropped then. */
    synchronized boolean send(JsonObject message) {
        if (out == null) return false;
        try {
            out.write(message.toString());
            out.write('\n');
            out.flush();
            return true;
        } catch (IOException e) {
            closeSocket();
            return false;
        }
    }

    void close() {
        running = false;
        closeSocket();
    }

    private void loop() {
        while (running) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                synchronized (this) {
                    socket = s;
                    out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                }
                send(Ipc.hello(secret, accountName(), com.guildbridge.core.BuildInfo.read("mod")));
                GuildBridgeClient.LOG.info("Connected to the Guild Bridge supervisor");
                String line;
                while (running && (line = in.readLine()) != null) {
                    JsonObject m = Ipc.parse(line);
                    if (m == null) continue;
                    try {
                        onMessage.accept(m);
                    } catch (RuntimeException e) {
                        GuildBridgeClient.LOG.warn("Error handling supervisor message: {}", e.toString());
                    }
                }
            } catch (IOException e) {
                // Supervisor not reachable yet, or it went away. Retry below.
            } finally {
                closeSocket();
            }
            sleep(RETRY_MILLIS);
        }
    }

    private String accountName() {
        // The account is known once the client is up; wait for it briefly if needed.
        for (int i = 0; i < 30; i++) {
            try {
                String name = account.get();
                if (name != null) return name;
            } catch (RuntimeException ignored) {
            }
            sleep(1_000);
        }
        return "unknown";
    }

    private synchronized void closeSocket() {
        Socket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }
}
