package com.guildbridge.supervisor;

import com.google.gson.JsonObject;
import com.guildbridge.core.Ipc;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Local connection to the mod. Listens on 127.0.0.1 only, so nothing outside this computer can
 * connect, and the first line must be a hello carrying the per-run secret. One mod at a time;
 * a new connection replaces the old one.
 */
final class IpcServer {
    interface Handler {
        void onConnected(String account);
        void onMessage(JsonObject message);
        void onDisconnected();
    }

    /** The mod sends a heartbeat every 20 s; silence this long means the game is hung or gone. */
    private static final int READ_TIMEOUT_MILLIS = 90_000;

    private final int port;
    private final byte[] secret;
    private final Handler handler;
    private ServerSocket server;
    private volatile Connection current;

    IpcServer(int port, String secret, Handler handler) {
        this.port = port;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.handler = handler;
    }

    /** Throws if the port is taken, which usually means another supervisor is already running. */
    void start() throws IOException {
        server = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "ipc-accept");
        t.setDaemon(true);
        t.start();
    }

    boolean connected() {
        return current != null;
    }

    /** Sends one message to the mod. False if no mod is connected or the write failed. */
    boolean send(JsonObject message) {
        Connection c = current;
        return c != null && c.send(message.toString());
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "ipc-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!server.isClosed()) Main.LOG.warn("IPC accept failed: {}", e.toString());
            }
        }
    }

    private void handle(Socket socket) {
        Connection conn = null;
        try (socket) {
            socket.setSoTimeout(10_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            JsonObject hello = Ipc.parse(in.readLine());
            String given = hello == null ? null : Ipc.str(hello, "secret");
            if (hello == null || !"hello".equals(Ipc.type(hello)) || given == null
                    || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
                Main.LOG.warn("Rejected a local connection without the right secret");
                return;
            }
            conn = new Connection(socket);
            Connection old = current;
            current = conn;
            if (old != null) old.close();
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            handler.onConnected(Ipc.str(hello, "account"));

            String line;
            while ((line = in.readLine()) != null) {
                JsonObject m = Ipc.parse(line);
                if (m == null) continue;
                try {
                    handler.onMessage(m);
                } catch (RuntimeException e) {
                    Main.LOG.warn("Error handling message from the game: {}", e.toString());
                }
            }
        } catch (SocketTimeoutException e) {
            Main.LOG.warn("No word from the game for {} s, dropping the connection", READ_TIMEOUT_MILLIS / 1000);
        } catch (IOException e) {
            // Connection closed; normal when the game stops.
        } finally {
            if (conn != null && current == conn) {
                current = null;
                handler.onDisconnected();
            }
        }
    }

    private static final class Connection {
        private final Socket socket;
        private final BufferedWriter out;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        synchronized boolean send(String line) {
            try {
                out.write(line);
                out.write('\n');
                out.flush();
                return true;
            } catch (IOException e) {
                close();
                return false;
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
