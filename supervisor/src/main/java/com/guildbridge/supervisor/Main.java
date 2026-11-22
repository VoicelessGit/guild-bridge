package com.guildbridge.supervisor;

import com.guildbridge.core.AdminList;
import com.guildbridge.core.ProfanityFilter;
import com.guildbridge.core.WelcomeTracker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guild Bridge supervisor. Run from its own folder:  java -jar guildbridge-supervisor.jar
 * That folder holds guildbridge.json, guildbridge-admins.txt, guildbridge-welcomed.txt,
 * optional guildbridge-filter.txt, and the HeadlessMc launcher.
 */
public final class Main {
    static final Logger LOG = LoggerFactory.getLogger("guildbridge");

    public static void main(String[] args) throws Exception {
        Path dir = (args.length > 0 ? Path.of(args[0]) : Path.of("")).toAbsolutePath();
        SupervisorConfig cfg = SupervisorConfig.load(dir.resolve("guildbridge.json"));
        if (!cfg.isComplete()) {
            LOG.error("Set botToken and channelId in {} and start again.", dir.resolve("guildbridge.json"));
            System.exit(2);
        }

        writeUpdateScript(dir);
        AdminList admins = new AdminList(dir.resolve("guildbridge-admins.txt"));
        admins.createIfMissing();
        WelcomeTracker welcomes = new WelcomeTracker(dir.resolve("guildbridge-welcomed.txt"));
        ProfanityFilter filter = loadFilter(dir.resolve("guildbridge-filter.txt"), cfg.filterThreshold);

        // The game gets the secret on its command line from us. It is kept in a file so a new
        // supervisor (after an update) can talk to a game the previous one started.
        String secret = loadOrCreateSecret(dir.resolve(".ipc-secret"));

        Supervisor sup = new Supervisor(cfg, dir, filter, admins, welcomes);
        IpcServer ipc = new IpcServer(cfg.ipcPort, secret, sup);
        try {
            ipc.start();
        } catch (IOException e) {
            LOG.error("Port {} is in use. Is another supervisor already running? ({})", cfg.ipcPort, e.toString());
            System.exit(3);
        }
        DiscordBot bot = new DiscordBot(cfg.botToken, cfg.channelId, sup::onDiscordMessage);
        GameProcess game = new GameProcess(cfg, dir, secret, sup);
        sup.attach(bot, ipc, game);
        HypixelApi api = new HypixelApi(() -> cfg.hypixelApiKey);
        NodeHelper node = new NodeHelper(dir);
        sup.setPvService(new PvService(api, node, sup::versionLine, () -> cfg.commandsHint), api,
                new com.guildbridge.core.LinkStore(dir.resolve("guildbridge-links.json")));
        node.warmUp();
        if (!api.hasKey()) LOG.warn("No hypixelApiKey in guildbridge.json: profile commands (!sb, !skill...) will say they aren't set up.");
        Runtime.getRuntime().addShutdownHook(new Thread(sup::shutdown, "shutdown"));

        LOG.info("Supervisor ready in {} (version {})", dir, com.guildbridge.core.BuildInfo.read("supervisor").version());
        if (game.adopt()) {
            LOG.info("The game kept running through the supervisor restart");
        } else if (cfg.autoStartGame) {
            LOG.info(game.start());
        }
        Thread.currentThread().join(); // run until stopped
    }

    /** Keeps ~/bridge/update.sh current; it ships inside this jar. */
    private static void writeUpdateScript(Path dir) {
        try (var in = Main.class.getResourceAsStream("/deploy/update.sh")) {
            if (in == null) return;
            byte[] bundled = in.readAllBytes();
            Path target = dir.resolve("update.sh");
            if (!Files.exists(target) || !java.util.Arrays.equals(Files.readAllBytes(target), bundled)) {
                Files.write(target, bundled);
                target.toFile().setExecutable(true);
            }
        } catch (IOException e) {
            LOG.warn("Could not write update.sh: {}", e.toString());
        }
    }

    private static String loadOrCreateSecret(Path file) throws IOException {
        if (Files.exists(file)) {
            String s = Files.readString(file).strip();
            if (s.matches("[0-9a-f]{32,}")) return s;
        }
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        String secret = HexFormat.of().formatHex(raw);
        Files.writeString(file, secret + "\n");
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows
        }
        return secret;
    }

    private static ProfanityFilter loadFilter(Path custom, int threshold) {
        if (Files.exists(custom)) {
            try {
                return new ProfanityFilter(Files.newBufferedReader(custom, StandardCharsets.UTF_8), threshold);
            } catch (IOException | RuntimeException e) {
                LOG.error("Bad custom filter list, using the bundled one: {}", e.toString());
            }
        }
        return ProfanityFilter.bundled(threshold);
    }
}
