package com.guildbridge.mod;

import com.guildbridge.core.AdminList;
import com.guildbridge.core.BridgeEvent;
import com.guildbridge.core.ChatParser;
import com.guildbridge.core.ChatRouter;
import com.guildbridge.core.CommandResponse;
import com.guildbridge.core.DiscordEmbed;
import com.guildbridge.core.LoopGuard;
import com.guildbridge.core.OutgoingSanitizer;
import com.guildbridge.core.ProfanityFilter;
import com.guildbridge.core.WelcomeTracker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point. Thin glue: chat events in, core logic, Discord bot and game commands out. */
public class GuildBridgeClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("guildbridge");

    private final ChatRouter router = new ChatRouter();
    private BridgeConfig config;
    private ProfanityFilter filter;
    private GameSender game;
    private DiscordBot bot;
    private WelcomeTracker welcomes;
    private LoopGuard guard;
    private LimboTracker limbo;
    private AutoJoin autoJoin;
    private final CommandCapture capture = new CommandCapture();
    private final long startedAt = System.currentTimeMillis();
    private volatile long joinedAt; // 0 when not connected

    @Override
    public void onInitializeClient() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        config = BridgeConfig.load(dir.resolve("guildbridge.json"));
        if (!config.isComplete()) {
            LOG.warn("Set botToken and channelId in config/guildbridge.json, bridge is disabled");
            return;
        }
        filter = loadFilter(dir.resolve("guildbridge-filter.txt"), config.filterThreshold);
        game = new GameSender(config.gameSendGapMillis);
        try {
            welcomes = new WelcomeTracker(dir.resolve("guildbridge-welcomed.txt"));
        } catch (IOException e) {
            LOG.error("Could not read welcomed list, welcomes disabled: {}", e.toString());
        }
        AdminList admins = new AdminList(dir.resolve("guildbridge-admins.txt"));
        try {
            admins.createIfMissing();
        } catch (IOException e) {
            LOG.warn("Could not create admins file: {}", e.toString());
        }
        try {
            bot = new DiscordBot(config.botToken, config.channelId, game, filter, admins,
                    config.discordPrefix, capture, new DiscordBot.Control() {
                        @Override public String status() { return statusText(); }
                        @Override public String uptime() { return uptimeText(); }
                        @Override public void restart() { restartGame(); }
                    });
        } catch (RuntimeException e) {
            LOG.error("Discord bot failed to start (bad token?): {}", e.toString());
            return;
        }

        limbo = new LimboTracker(game, this::limboChanged,
                config.autoLimbo, Math.max(1, config.autoLimboDelaySeconds) * 1000L);

        // Hide our own /locraw replies from chat before anything else sees them.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
                overlay || limbo.allowChatLine(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return; // action bar text, not chat
            String text = message.getString();
            boolean capturing = capture.active();
            if (capturing) captureNonGuildLines(text);
            for (BridgeEvent event : router.accept(text)) {
                // While a command reply is being captured, its blocks (e.g. /g online) are part of
                // the reply, not announcements. Guild chat keeps flowing normally.
                if (capturing && event instanceof BridgeEvent.Announcement) continue;
                handle(event);
            }
        });
        autoJoin = new AutoJoin(config.autoJoinServer, config.autoJoinRetrySeconds, config.autoJoinMaxRetrySeconds);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> autoJoin.screenOpened(screen));
        ClientTickEvents.END_CLIENT_TICK.register(client -> autoJoin.tick(client));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> autoJoin.clientStarted());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            joinedAt = System.currentTimeMillis();
            autoJoin.joined();
            limbo.worldChanged();
        });
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> limbo.worldChanged());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            joinedAt = 0;
            limbo.disconnected();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> bot.shutdown());
        LOG.info("Guild Bridge ready");
    }

    private void handle(BridgeEvent event) {
        if (guard == null) {
            // The account name is only known once the client is up.
            guard = new LoopGuard(Minecraft.getInstance().getUser().getName());
        }
        if (!guard.shouldRelay(event)) return;

        bot.post(DiscordEmbed.forEvent(event, filter));

        if (event instanceof BridgeEvent.Announcement a && a.joinedMember() != null) {
            welcome(a.joinedMember());
        }
    }

    private void welcome(String name) {
        if (!config.welcomeEnabled || welcomes == null) return;
        try {
            if (welcomes.firstTime(name)) {
                game.queue(OutgoingSanitizer.welcomeCommand(config.welcomeMessage, name));
            }
        } catch (IOException e) {
            LOG.warn("Could not save welcomed list: {}", e.toString());
        }
    }

    /** Reply to !status. */
    private String statusText() {
        if (limbo == null || autoJoin == null) return "Starting up.";
        String where = switch (limbo.state()) {
            case IN_LIMBO -> "In limbo, relaying.";
            case ON_HYPIXEL_NOT_LIMBO -> "On Hypixel but not in limbo (server: " + limbo.lastServer() + ").";
            case NOT_ON_HYPIXEL -> "Not connected to Hypixel.";
        };
        return where + (autoJoin.enabled() ? " Auto-join is on." : " Auto-join is off.");
    }

    /** Reply to !uptime. */
    private String uptimeText() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("Game running for " + CommandResponse.formatDuration(now - startedAt) + ".");
        long joined = joinedAt;
        if (joined > 0) {
            sb.append(" Connected to the server for ").append(CommandResponse.formatDuration(now - joined)).append(".");
        } else {
            sb.append(" Not connected to a server.");
        }
        if (limbo != null && limbo.state() == LimboTracker.State.IN_LIMBO) {
            sb.append(" In limbo for ").append(CommandResponse.formatDuration(now - limbo.stateSince())).append(".");
        }
        return sb.toString();
    }

    /**
     * !restart: quits the game cleanly after a short pause (so the Discord reply goes out first).
     * The launch script's loop starts it again; without the script it just stays closed.
     */
    private void restartGame() {
        LOG.info("Restart requested from Discord");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(mc::stop);
        }, "guildbridge-restart");
        t.setDaemon(true);
        t.start();
    }

    /** Adds every line of a chat message that is not guild chat to the active capture. */
    private void captureNonGuildLines(String text) {
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (ChatParser.parse(line).isEmpty()) kept.append(line).append('\n');
        }
        if (!kept.isEmpty()) capture.add(kept.toString());
    }

    /** Status light plus a one-line notice in the channel on every change. */
    private void limboChanged(LimboTracker.State state) {
        bot.setState(state);
        String text = switch (state) {
            case IN_LIMBO -> "Bridge is in limbo and relaying.";
            case ON_HYPIXEL_NOT_LIMBO -> "Bridge is on Hypixel but NOT in limbo (server: " + limbo.lastServer() + "). "
                    + (limbo.autoLimbo()
                        ? "Sending /limbo in " + limbo.autoLimboDelayMillis() / 1000 + " s."
                        : "Hypixel's AFK system should move it to limbo within a few minutes.");
            case NOT_ON_HYPIXEL -> "Bridge is not connected to Hypixel.";
        };
        bot.post(DiscordEmbed.notice(text, state == LimboTracker.State.IN_LIMBO));
    }

    /** Uses config/guildbridge-filter.txt if present, otherwise the list bundled in the jar. */
    private static ProfanityFilter loadFilter(Path custom, int threshold) {
        if (Files.exists(custom)) {
            try {
                return new ProfanityFilter(Files.newBufferedReader(custom, StandardCharsets.UTF_8), threshold);
            } catch (IOException | RuntimeException e) {
                LOG.error("Bad custom filter list, using bundled one: {}", e.toString());
            }
        }
        return ProfanityFilter.bundled(threshold);
    }
}
