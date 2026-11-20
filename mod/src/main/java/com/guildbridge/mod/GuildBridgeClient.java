package com.guildbridge.mod;

import com.guildbridge.core.BridgeEvent;
import com.guildbridge.core.ChatParser;
import com.guildbridge.core.LoopGuard;
import com.guildbridge.core.WebhookPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point. Thin glue: chat event in, core logic, webhook out. */
public class GuildBridgeClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("guildbridge");

    private WebhookSender sender;
    private LoopGuard guard;

    @Override
    public void onInitializeClient() {
        BridgeConfig config = BridgeConfig.load(
                FabricLoader.getInstance().getConfigDir().resolve("guildbridge.json"));
        if (config.webhookUrl == null || config.webhookUrl.isBlank()) {
            LOG.warn("No webhookUrl in config/guildbridge.json, bridge is disabled");
            return;
        }
        sender = new WebhookSender(config.webhookUrl);

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return; // action bar text, not chat
            ChatParser.parse(message.getString())
                    .filter(this::shouldRelay)
                    .map(WebhookPayload::forEvent)
                    .ifPresent(sender::send);
        });
        LOG.info("Guild Bridge ready");
    }

    private boolean shouldRelay(BridgeEvent event) {
        if (guard == null) {
            // The account name is only known once the client is up.
            guard = new LoopGuard(Minecraft.getInstance().getUser().getName());
        }
        return guard.shouldRelay(event);
    }
}
