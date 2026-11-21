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

        AdminList admins = new AdminList(dir.resolve("guildbridge-admins.txt"));
        admins.createIfMissing();
        WelcomeTracker welcomes = new WelcomeTracker(dir.resolve("guildbridge-welcomed.txt"));
        ProfanityFilter filter = loadFilter(dir.resolve("guildbridge-filter.txt"), cfg.filterThreshold);

        // A fresh secret every run; the game gets it on its command line from us.
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        String secret = HexFormat.of().formatHex(raw);

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
        Runtime.getRuntime().addShutdownHook(new Thread(sup::shutdown, "shutdown"));

        LOG.info("Supervisor ready in {}", dir);
        if (cfg.autoStartGame) LOG.info(game.start());
        Thread.currentThread().join(); // run until stopped
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
