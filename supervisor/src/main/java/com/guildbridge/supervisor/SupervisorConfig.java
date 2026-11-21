package com.guildbridge.supervisor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** guildbridge.json next to the supervisor jar. Written with defaults on first run. */
final class SupervisorConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // --- Discord ---
    String botToken = "";
    String channelId = "";
    String discordPrefix = "[Discord]";

    // --- Guild features ---
    boolean welcomeEnabled = true;
    String welcomeMessage = "Welcome to the guild, {name}!";
    int filterThreshold = 2;

    // --- In-game behavior (sent to the mod when it connects) ---
    String autoJoinServer = "mc.hypixel.net";
    int autoJoinRetrySeconds = 10;
    int autoJoinMaxRetrySeconds = 300;
    boolean autoLimbo = true;
    int autoLimboDelaySeconds = 5;
    long gameSendGapMillis = 1500;

    // --- Alerts ---
    // Who to ping for real problems: Discord user IDs, or "role:<role id>" for a role.
    List<String> alertPing = new ArrayList<>();
    int alertCooldownMinutes = 30;
    int notInLimboAlertMinutes = 5;

    // --- Game process ---
    // Path to the HeadlessMc launcher. Blank = look for headlessmc-launcher* next to this jar.
    String headlessMcPath = "";
    String minecraftVersion = "26.2";
    String jvmArgs = "-Xms512M -Xmx1536M -Djava.net.preferIPv4Stack=true";
    // Blank = the default HeadlessMc game folder for this OS.
    String gameLogPath = "";
    boolean autoStartGame = true;
    int restartDelaySeconds = 30;
    int ipcPort = 25599;

    boolean isComplete() {
        return botToken != null && !botToken.isBlank()
                && channelId != null && channelId.strip().matches("\\d{15,25}");
    }

    static SupervisorConfig load(Path file) throws IOException {
        SupervisorConfig c = null;
        if (Files.exists(file)) c = GSON.fromJson(Files.readString(file), SupervisorConfig.class);
        if (c == null) c = new SupervisorConfig();
        if (c.alertPing == null) c.alertPing = new ArrayList<>();
        // Rewrite so new settings appear in the file with their defaults.
        Files.writeString(file, GSON.toJson(c));
        return c;
    }

    /** The HeadlessMc launcher to run. */
    Path headlessMc(Path dir) {
        if (headlessMcPath != null && !headlessMcPath.isBlank()) return Path.of(headlessMcPath);
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("headlessmc-launcher"))
                    .filter(Files::isRegularFile)
                    .findFirst().orElse(dir.resolve("headlessmc-launcher"));
        } catch (IOException e) {
            return dir.resolve("headlessmc-launcher");
        }
    }

    /** The game's latest.log, for !logs. */
    Path gameLog() {
        if (gameLogPath != null && !gameLogPath.isBlank()) return Path.of(gameLogPath);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        Path mc;
        if (os.contains("win")) {
            mc = Path.of(System.getenv().getOrDefault("APPDATA", home), ".minecraft");
        } else if (os.contains("mac")) {
            mc = Path.of(home, "Library", "Application Support", "minecraft");
        } else {
            mc = Path.of(home, ".minecraft");
        }
        return mc.resolve("fabric-" + minecraftVersion).resolve("logs").resolve("latest.log");
    }
}
