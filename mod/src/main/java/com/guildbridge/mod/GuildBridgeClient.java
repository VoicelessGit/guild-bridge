package com.guildbridge.mod;

import com.guildbridge.core.BridgeEvent;
import com.guildbridge.core.ChatRouter;
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
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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
        try {
            bot = new DiscordBot(config.botToken, config.channelId, game, filter);
        } catch (RuntimeException e) {
            LOG.error("Discord bot failed to start (bad token?): {}", e.toString());
            return;
        }

        limbo = new LimboTracker(game, this::limboChanged);

        // Hide our own /locraw replies from chat before anything else sees them.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
                overlay || limbo.allowChatLine(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return; // action bar text, not chat
            for (BridgeEvent event : router.accept(message.getString())) handle(event);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> limbo.worldChanged());
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> limbo.worldChanged());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> limbo.disconnected());
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

    /** Status light plus a one-line notice in the channel on every change. */
    private void limboChanged(LimboTracker.State state) {
        bot.setState(state);
        String text = switch (state) {
            case IN_LIMBO -> "Bridge is in limbo and relaying.";
            case ON_HYPIXEL_NOT_LIMBO -> "Bridge is on Hypixel but NOT in limbo (server: "
                    + limbo.lastServer() + "). Hypixel's AFK system should move it to limbo within a few minutes.";
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
