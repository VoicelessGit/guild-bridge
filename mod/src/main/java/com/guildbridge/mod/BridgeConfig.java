package com.guildbridge.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings from config/guildbridge.json. Written with empty values on first launch. */
final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    String webhookUrl = "";
    // Phase 2 (Discord to game):
    String botToken = "";
    String channelId = "";

    static BridgeConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                BridgeConfig c = GSON.fromJson(Files.readString(file), BridgeConfig.class);
                if (c != null) return c;
            }
            BridgeConfig fresh = new BridgeConfig();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(fresh));
            GuildBridgeClient.LOG.info("Wrote a blank config to {}", file);
            return fresh;
        } catch (IOException | RuntimeException e) {
            GuildBridgeClient.LOG.error("Could not read {}: {}", file, e.toString());
            return new BridgeConfig();
        }
    }
}
