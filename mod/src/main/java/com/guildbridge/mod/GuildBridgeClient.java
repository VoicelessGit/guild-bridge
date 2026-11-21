package com.guildbridge.mod;

import com.google.gson.JsonObject;
import com.guildbridge.core.BridgeEvent;
import com.guildbridge.core.ChatParser;
import com.guildbridge.core.ChatRouter;
import com.guildbridge.core.Ipc;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. The mod is the supervisor's hands and eyes inside Minecraft: it reports chat,
 * location and disconnects, and runs the commands it is given. It has no Discord code and never
 * sees the bot token. Without a supervisor (no -Dguildbridge.ipc.* on the command line) it stays idle.
 *
 * <p>Every handler that runs on the game thread is wrapped: an exception there makes Minecraft
 * disconnect with "Network Protocol Error".
 */
public class GuildBridgeClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("guildbridge");

    private final ChatRouter router = new ChatRouter();
    private final CommandCapture capture = new CommandCapture();
    private GameSender game;
    private LimboTracker limbo;
    private AutoJoin autoJoin;
    private DisconnectWatcher disconnects;
    private IpcClient ipc;

    @Override
    public void onInitializeClient() {
        String port = System.getProperty("guildbridge.ipc.port");
        String secret = System.getProperty("guildbridge.ipc.secret");
        if (port == null || secret == null || !port.matches("\\d{1,5}")) {
            LOG.warn("Guild Bridge: not started by the supervisor, so the bridge is idle in this client.");
            return;
        }

        game = new GameSender(1500);
        limbo = new LimboTracker(game, this::limboChanged);
        autoJoin = new AutoJoin();
        ipc = new IpcClient(Integer.parseInt(port), secret,
                () -> Minecraft.getInstance().getUser().getName(), this::onSupervisorMessage);
        disconnects = new DisconnectWatcher(reason -> {
            JsonObject m = Ipc.msg("disconnect");
            m.addProperty("reason", reason);
            ipc.send(m);
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            try {
                return overlay || limbo.allowChatLine(message.getString());
            } catch (Throwable t) {
                LOG.warn("Error in limbo check, letting message through: {}", t.toString());
                return true;
            }
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            try {
                onGameMessage(message.getString(), overlay);
            } catch (Throwable t) {
                LOG.warn("Error handling chat message: {}", t.toString());
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            try {
                autoJoin.screenOpened(screen);
                disconnects.screenOpened(screen);
            } catch (Throwable t) {
                LOG.warn("Screen handler error: {}", t.toString());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                autoJoin.tick(client);
            } catch (Throwable t) {
                LOG.warn("Auto-join error: {}", t.toString());
            }
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                autoJoin.clientStarted();
            } catch (Throwable t) {
                LOG.warn("Startup handler error: {}", t.toString());
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                ipc.send(Ipc.msg("joined"));
                autoJoin.joined();
                limbo.worldChanged();
            } catch (Throwable t) {
                LOG.warn("Join handler error: {}", t.toString());
            }
        });
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            try {
                limbo.worldChanged();
            } catch (Throwable t) {
                LOG.warn("Level change handler error: {}", t.toString());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            try {
                limbo.disconnected();
                disconnects.disconnected();
            } catch (Throwable t) {
                LOG.warn("Disconnect handler error: {}", t.toString());
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            try {
                ipc.close();
            } catch (Throwable t) {
                LOG.warn("Shutdown error: {}", t.toString());
            }
        });

        ipc.start();
        LOG.info("Guild Bridge ready, connecting to the supervisor on port {}", port);
    }

    // ---- game to supervisor ----

    private void onGameMessage(String text, boolean overlay) {
        if (overlay) return; // action bar text, not chat
        boolean capturing = capture.active();
        if (capturing) captureNonGuildLines(text);
        for (BridgeEvent event : router.accept(text)) {
            // While a command reply is being captured, its blocks (e.g. /g online) belong to the reply.
            if (capturing && event instanceof BridgeEvent.Announcement) continue;
            ipc.send(Ipc.event(event));
        }
    }

    private void limboChanged(LimboTracker.State state) {
        JsonObject m = Ipc.msg("state");
        m.addProperty("state", state.name());
        m.addProperty("server", limbo.lastServer());
        ipc.send(m);
    }

    private void captureNonGuildLines(String text) {
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (ChatParser.parse(line).isEmpty()) kept.append(line).append('\n');
        }
        if (!kept.isEmpty()) capture.add(kept.toString());
    }

    // ---- supervisor to game ----

    private void onSupervisorMessage(JsonObject m) {
        switch (Ipc.type(m)) {
            case "settings" -> {
                autoJoin.configure(Ipc.str(m, "autoJoinServer"),
                        (int) Ipc.num(m, "autoJoinRetrySeconds", 10), (int) Ipc.num(m, "autoJoinMaxRetrySeconds", 300));
                limbo.configure(Ipc.bool(m, "autoLimbo", true), Ipc.num(m, "autoLimboDelaySeconds", 5) * 1000L);
                game.setGap(Ipc.num(m, "gameSendGapMillis", 1500));
                // A fresh connection should learn where we are right away.
                limbo.worldChanged();
            }
            case "command" -> runCommand(m);
            case "autojoin" -> autoJoin.pause(Ipc.num(m, "pausedMinutes", 0));
            case "stop" -> {
                LOG.info("Supervisor asked the game to quit");
                Minecraft mc = Minecraft.getInstance();
                mc.execute(mc::stop);
            }
            default -> { }
        }
    }

    private void runCommand(JsonObject m) {
        long id = Ipc.num(m, "id", -1);
        String cmd = Ipc.str(m, "command");
        Runnable sent = () -> reply("sent", id, null);
        Runnable rejected = () -> reply("rejected", id, null);

        if (Ipc.bool(m, "capture", false)) {
            if (!capture.reserve()) {
                reply("rejected", id, "busy");
                return;
            }
            game.queueUtility(cmd,
                    () -> capture.begin(text -> {
                        JsonObject c = Ipc.msg("capture");
                        c.addProperty("id", id);
                        c.addProperty("text", text);
                        ipc.send(c);
                    }),
                    () -> {
                        capture.cancel();
                        rejected.run();
                    });
        } else if (Ipc.bool(m, "checkRepeats", true)) {
            game.queueChat(cmd, sent, rejected);
        } else {
            game.queueUtility(cmd, sent, rejected);
        }
    }

    private void reply(String type, long id, String reason) {
        JsonObject r = Ipc.msg(type);
        r.addProperty("id", id);
        if (reason != null) r.addProperty("reason", reason);
        ipc.send(r);
    }
}
