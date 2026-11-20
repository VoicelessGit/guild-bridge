package com.guildbridge.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings from config/guildbridge.json. Written with defaults on first launch. */
final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    String webhookUrl = "";

    // Welcome new guild members on their first join only.
    boolean welcomeEnabled = true;
    String welcomeMessage = "Welcome to the guild, {name}!";

    // 1 = censor everything in the list, 2 = strong profanity and slurs (default), 3 = slurs only.
    int filterThreshold = 2;

    // Gap between messages the bot sends in game, in milliseconds.
    long gameSendGapMillis = 1500;

    // Phase 2 (Discord to game):
    String botToken = "";
    String channelId = "";

    static BridgeConfig load(Path file) {
        try {
            BridgeConfig c = null;
            if (Files.exists(file)) c = GSON.fromJson(Files.readString(file), BridgeConfig.class);
            if (c == null) c = new BridgeConfig();
            // Rewrite so new fields from updates show up in the file with their defaults.
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(c));
            return c;
        } catch (IOException | RuntimeException e) {
            GuildBridgeClient.LOG.error("Could not read {}: {}", file, e.toString());
            return new BridgeConfig();
        }
    }
}
