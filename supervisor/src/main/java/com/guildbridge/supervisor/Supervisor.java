package com.guildbridge.supervisor;

import com.google.gson.JsonObject;
import com.guildbridge.core.AdminList;
import com.guildbridge.core.AlertLimiter;
import com.guildbridge.core.BotCommand;
import com.guildbridge.core.BridgeEvent;
import com.guildbridge.core.CommandResponse;
import com.guildbridge.core.DiscordEmbed;
import com.guildbridge.core.DisconnectKind;
import com.guildbridge.core.Ipc;
import com.guildbridge.core.LoopGuard;
import com.guildbridge.core.MentionText;
import com.guildbridge.core.OutgoingSanitizer;
import com.guildbridge.core.ProfanityFilter;
import com.guildbridge.core.WelcomeTracker;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

/**
 * Ties everything together: game events to Discord, Discord messages and commands to the game,
 * process control, kick handling, the status light and alert pings.
 */
final class Supervisor implements IpcServer.Handler, GameProcess.Listener {
    private static final long MEMBER_COOLDOWN_MILLIS = 10_000;
    private static final long HEARTBEAT_TIMEOUT_MILLIS = 120_000;
    private static final long STARTUP_TIMEOUT_MILLIS = 5 * 60_000;
    private static final long WINDOW_30_MIN = 30 * 60_000;
    private static final long WINDOW_15_MIN = 15 * 60_000;

    private record Pending(Message msg, String title, boolean capture, long createdAt) {}

    private final SupervisorConfig cfg;
    private final Path dir;
    private final ProfanityFilter filter;
    private final AdminList admins;
    private final WelcomeTracker welcomes;
    private final AlertLimiter limiter;
    private final long startedAt = System.currentTimeMillis();
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "supervisor-timer");
        t.setDaemon(true);
        return t;
    });

    private DiscordBot bot;
    private IpcServer ipc;
    private GameProcess game;

    // State reported by the mod.
    private volatile boolean modConnected;
    private volatile LoopGuard guard;
    private volatile String limboState;      // IN_LIMBO, ON_HYPIXEL_NOT_LIMBO, NOT_ON_HYPIXEL or null
    private volatile String lastServer;
    private volatile long stateSince;
    private volatile long joinedAt;
    private volatile long lastHeartbeat;
    private volatile long lastModContact;
    private volatile String bannedReason;    // non-null while auto-join is paused for a ban
    private volatile long lastMemberCommandAt;
    private volatile com.guildbridge.core.BuildInfo modInfo;   // reported by the mod in its hello
    private PvService pv;
    /** In-game command cooldown per player (lowercase name to last use). */
    private final Map<String, Long> playerCommandAt = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger commandsInFlight = new java.util.concurrent.atomic.AtomicInteger();
    /** Last few answers sent; Hypixel and the mod refuse a line repeated this soon. */
    private final java.util.ArrayDeque<String> recentAnswers = new java.util.ArrayDeque<>();

    Supervisor(SupervisorConfig cfg, Path dir, ProfanityFilter filter, AdminList admins, WelcomeTracker welcomes) {
        this.cfg = cfg;
        this.dir = dir;
        this.filter = filter;
        this.admins = admins;
        this.welcomes = welcomes;
        this.limiter = new AlertLimiter(Math.max(1, cfg.alertCooldownMinutes) * 60_000L);
    }

    void setPvService(PvService pv) {
        this.pv = pv;
    }

    String versionLine() {
        return com.guildbridge.core.BuildInfo.versionLine(com.guildbridge.core.BuildInfo.read("supervisor"),
                modConnected ? modInfo : null, zone());
    }

    private java.time.ZoneId zone() {
        try {
            return java.time.ZoneId.of(cfg.timeZone);
        } catch (RuntimeException e) {
            return java.time.ZoneId.of("UTC");
        }
    }

    void attach(DiscordBot bot, IpcServer ipc, GameProcess game) {
        this.bot = bot;
        this.ipc = ipc;
        this.game = game;
        bot.setOnPermissionProblem(() -> alert("discord-permission",
                "The bot could not post in the bridge channel. Check that it has View Channel, Send Messages, "
                        + "Embed Links, Add Reactions and Read Message History there.", false));
        timer.scheduleWithFixedDelay(this::checks, 15, 15, TimeUnit.SECONDS);
        updatePresence();
    }

    void shutdown() {
        // update.sh leaves this file when only the supervisor changed: keep the game online.
        Path keep = dir.resolve(".keep-game");
        boolean keepGame = Files.exists(keep);
        try {
            Files.deleteIfExists(keep);
        } catch (IOException ignored) {
        }
        if (keepGame && game.running()) {
            game.detach();
        } else {
            game.shutdownAndWait(this::askModToQuit);
        }
        if (pv != null) pv.shutdown();
        bot.shutdown();
    }

    private void askModToQuit() {
        ipc.send(Ipc.msg("stop"));
    }

    // =====================================================================
    // Messages from the mod
    // =====================================================================

    @Override
    public void onConnected(String account, String modVersion, long modBuilt) {
        modInfo = new com.guildbridge.core.BuildInfo(modVersion == null ? "?" : modVersion, modBuilt);
        modConnected = true;
        guard = new LoopGuard(account);
        long now = System.currentTimeMillis();
        lastHeartbeat = now;
        lastModContact = now;
        Main.LOG.info("Game connected to the supervisor (account {})", account);

        JsonObject s = Ipc.msg("settings");
        s.addProperty("autoJoinServer", cfg.autoJoinServer);
        s.addProperty("autoJoinRetrySeconds", cfg.autoJoinRetrySeconds);
        s.addProperty("autoJoinMaxRetrySeconds", cfg.autoJoinMaxRetrySeconds);
        s.addProperty("autoLimbo", cfg.autoLimbo);
        s.addProperty("autoLimboDelaySeconds", cfg.autoLimboDelaySeconds);
        s.addProperty("gameSendGapMillis", cfg.gameSendGapMillis);
        ipc.send(s);
        if (bannedReason != null) ipc.send(Ipc.autoJoin(-1));
        updatePresence();
    }

    @Override
    public void onDisconnected() {
        modConnected = false;
        limboState = null;
        joinedAt = 0;
        Main.LOG.info("Game disconnected from the supervisor");
        updatePresence();
    }

    @Override
    public void onMessage(JsonObject m) {
        long now = System.currentTimeMillis();
        lastModContact = now;
        switch (Ipc.type(m)) {
            case "heartbeat" -> lastHeartbeat = now;
            case "event" -> onGameEvent(Ipc.toEvent(m.getAsJsonObject("event")));
            case "state" -> onState(Ipc.str(m, "state"), Ipc.str(m, "server"));
            case "joined" -> joinedAt = now;
            case "disconnect" -> onDisconnect(Ipc.str(m, "reason"));
            case "sent" -> onSent(Ipc.num(m, "id", -1));
            case "rejected" -> onRejected(Ipc.num(m, "id", -1), Ipc.str(m, "reason"));
            case "capture" -> onCapture(Ipc.num(m, "id", -1), Ipc.str(m, "text"));
            case "stuck" -> onStuck();
            default -> { }
        }
    }

    private void onGameEvent(BridgeEvent event) {
        if (event == null) return;
        LoopGuard g = guard;
        if (g != null && !g.shouldRelay(event)) return;
        // In-game commands are answered in game and not relayed to Discord.
        if (event instanceof BridgeEvent.Chat c && com.guildbridge.core.sb.PvCommands.isCommand(c.message())) {
            onGameCommand(c);
            return;
        }
        bot.post(DiscordEmbed.forEvent(event, filter));
        if (event instanceof BridgeEvent.Announcement a && a.joinedMember() != null) welcome(a.joinedMember());
    }

    /** A guild member typed !something in guild or officer chat. */
    private void onGameCommand(BridgeEvent.Chat c) {
        if (pv == null) return;
        String who = c.name().toLowerCase(java.util.Locale.ROOT);
        long now = System.currentTimeMillis();
        Long last = playerCommandAt.get(who);
        if (last != null && now - last < Math.max(1, cfg.playerCommandCooldownSeconds) * 1000L) return; // quietly ignore spam
        // Keep the bridge from flooding chat: only a few answers queue up at once.
        if (commandsInFlight.get() >= 4) return;
        playerCommandAt.put(who, now);
        commandsInFlight.incrementAndGet();
        String prefix = c.channel() == BridgeEvent.Channel.OFFICER ? "oc " : "gc ";
        Main.LOG.info("{} used {}", c.name(), c.message().strip());
        pv.answer(c.message(), c.name()).whenComplete((line, err) -> {
            commandsInFlight.decrementAndGet();
            if (line == null || line.isBlank() || !modConnected) return;
            synchronized (recentAnswers) {
                // Two people asking the same thing would get an identical line, which would be blocked.
                if (recentAnswers.contains(line)) line = line + " (for " + c.name() + ")";
                recentAnswers.addLast(line);
                while (recentAnswers.size() > 3) recentAnswers.removeFirst();
            }
            String cmd = OutgoingSanitizer.toGuildCommand("", line, null, "");
            if (cmd == null) return;
            if (cmd.startsWith("gc ")) cmd = prefix + cmd.substring(3);
            ipc.send(Ipc.command(ids.incrementAndGet(), cmd, true, false));
        });
    }

    private void welcome(String name) {
        if (!cfg.welcomeEnabled || welcomes == null) return;
        try {
            if (welcomes.firstTime(name)) {
                ipc.send(Ipc.command(ids.incrementAndGet(),
                        OutgoingSanitizer.welcomeCommand(cfg.welcomeMessage, name), true, false));
            }
        } catch (IOException e) {
            Main.LOG.warn("Could not save welcomed list: {}", e.toString());
        }
    }

    private void onState(String state, String server) {
        if (state == null || state.equals(limboState)) return;
        boolean first = limboState == null;
        limboState = state;
        lastServer = server;
        stateSince = System.currentTimeMillis();
        String text = switch (state) {
            case "IN_LIMBO" -> "Bridge is in limbo and relaying.";
            case "ON_HYPIXEL_NOT_LIMBO" -> "Bridge is on Hypixel but NOT in limbo (server: " + server + ")."
                    + (cfg.autoLimbo ? " Sending /limbo in " + cfg.autoLimboDelaySeconds + " s." : "");
            default -> "Bridge is not connected to Hypixel.";
        };
        // The game reports "not on Hypixel" right after it starts; that is expected, not news.
        if (!(first && "NOT_ON_HYPIXEL".equals(state))) bot.post(DiscordEmbed.notice(text, "IN_LIMBO".equals(state)));
        if ("IN_LIMBO".equals(state)) limiter.reset("not-in-limbo");
        updatePresence();
    }

    /** The mod saw Hypixel ignore everything it sent; only a fresh game gets a working connection. */
    private void onStuck() {
        long now = System.currentTimeMillis();
        Main.LOG.warn("The game's connection is stuck (Hypixel ignores everything it sends), restarting the game");
        if (limiter.count("stuck", now, WINDOW_30_MIN) >= 2) {
            alert("stuck", "The bridge's connection keeps getting stuck: Hypixel ignores everything it sends, "
                    + "even after a restart. It will keep trying; check `!logs` if it doesn't recover.", true);
        } else {
            bot.post(DiscordEmbed.notice("Bridge connection got stuck (Hypixel stopped answering). Restarting the game.", false));
        }
        game.restart(this::askModToQuit);
    }

    private void onDisconnect(String reason) {
        long now = System.currentTimeMillis();
        String why = reason == null || reason.isBlank() ? "(no reason given)" : reason.strip();
        DisconnectKind kind = DisconnectKind.classify(why);
        joinedAt = 0;
        bot.post(DiscordEmbed.notice(kind.label + ": " + why, false));
        Main.LOG.info("Disconnect ({}): {}", kind, why);

        switch (kind) {
            case BANNED -> {
                bannedReason = why;
                ipc.send(Ipc.autoJoin(-1));
                alert("banned", "The bridge account is **banned**. Auto-join is paused until an admin runs `!rejoin`.\n> "
                        + why, false);
            }
            case DUPLICATE_LOGIN -> {
                ipc.send(Ipc.autoJoin(10));
                alert("duplicate-login", "Someone logged into the bridge account from somewhere else. "
                        + "Auto-join is paused for 10 minutes so the bridge does not keep kicking them.\n> " + why, false);
            }
            case LOGIN_FAILED -> {
                if (limiter.count("login-failed", now, WINDOW_30_MIN) >= 3) {
                    alert("login-failed", "Login keeps failing (3 or more times in 30 minutes). "
                            + "The account session may need `login` again in HeadlessMc.\n> " + why, false);
                }
            }
            case NETWORK, KICKED -> {
                if (limiter.count("disconnects", now, WINDOW_30_MIN) >= 5) {
                    alert("flapping", "The bridge has disconnected 5 or more times in 30 minutes. Latest: " + why, false);
                }
            }
            case UNKNOWN -> alert("unknown-" + why.hashCode(),
                    "Disconnected for a reason the bridge does not recognize:\n> " + why, false);
        }
        updatePresence();
    }

    private void onSent(long id) {
        Pending p = pending.get(id);
        if (p != null && !p.capture()) {
            pending.remove(id);
        }
    }

    private void onRejected(long id, String reason) {
        Pending p = pending.remove(id);
        if (p != null && p.msg() != null) DiscordBot.react(p.msg(), "busy".equals(reason) ? "⏳" : "❌");
    }

    private void onCapture(long id, String text) {
        Pending p = pending.remove(id);
        if (p == null || p.msg() == null) return;
        DiscordBot.replyEmbedJson(p.msg(), DiscordEmbed.response(p.title(), text == null ? "" : text, filter));
    }

    // =====================================================================
    // Game process events
    // =====================================================================

    @Override
    public void onStarted() {
        updatePresence();
    }

    @Override
    public int onExited(int exitCode, boolean expected) {
        modConnected = false;
        limboState = null;
        joinedAt = 0;
        updatePresence();
        if (expected) {
            bot.post(DiscordEmbed.notice("The game has stopped.", false));
            return -1;
        }
        long now = System.currentTimeMillis();
        int crashes = limiter.count("crash", now, WINDOW_15_MIN);
        if (crashes >= 3) {
            alert("crash-loop", "The game has stopped unexpectedly " + crashes + " times in 15 minutes "
                    + "(last exit code " + exitCode + "). Waiting 10 minutes before trying again. "
                    + "Check `!logs` for the cause.", true);
            return 600;
        }
        bot.post(DiscordEmbed.notice("The game stopped unexpectedly (exit code " + exitCode
                + "). Restarting in " + cfg.restartDelaySeconds + " s.", false));
        return cfg.restartDelaySeconds;
    }

    // =====================================================================
    // Periodic checks: hang watchdog, stuck outside limbo, cleanup
    // =====================================================================

    private void checks() {
        try {
            long now = System.currentTimeMillis();
            if (game.running()) {
                if (modConnected && now - lastHeartbeat > HEARTBEAT_TIMEOUT_MILLIS) {
                    alert("hang", "The game stopped responding (no heartbeat for 2 minutes). Restarting it.", true);
                    game.restart(this::askModToQuit);
                } else if (!modConnected && now - Math.max(game.startedAt(), lastModContact) > STARTUP_TIMEOUT_MILLIS) {
                    alert("no-contact", "The game has been running for 5 minutes without reaching the bridge. "
                            + "Restarting it. Check `!logs` if this repeats.", true);
                    game.restart(this::askModToQuit);
                }
            }
            if ("ON_HYPIXEL_NOT_LIMBO".equals(limboState)
                    && now - stateSince > Math.max(1, cfg.notInLimboAlertMinutes) * 60_000L) {
                alert("not-in-limbo", "The bridge has been outside limbo for over " + cfg.notInLimboAlertMinutes
                        + " minutes (server: " + lastServer + ").", false);
            }
            pending.values().removeIf(p -> now - p.createdAt() > 120_000);
        } catch (RuntimeException e) {
            Main.LOG.warn("Check failed: {}", e.toString());
        }
    }

    /**
     * Pings the alert list if this key is not on cooldown. When it is on cooldown, the message
     * is still posted without a ping if alsoPostWhenQuiet is set.
     */
    private void alert(String key, String text, boolean alsoPostWhenQuiet) {
        if (limiter.allow(key, System.currentTimeMillis())) {
            Main.LOG.warn("ALERT: {}", text);
            bot.alert(text, cfg.alertPing);
        } else if (alsoPostWhenQuiet) {
            bot.post(DiscordEmbed.notice(text, false));
        }
    }

    // =====================================================================
    // Status light
    // =====================================================================

    private void updatePresence() {
        if (bot == null || game == null) return;
        if (!game.running()) {
            if (game.wanted()) bot.setPresence(OnlineStatus.IDLE, "Restarting the game");
            else bot.setPresence(OnlineStatus.DO_NOT_DISTURB, "Game stopped");
        } else if (!modConnected) {
            bot.setPresence(OnlineStatus.IDLE, "Starting the game");
        } else if (bannedReason != null) {
            bot.setPresence(OnlineStatus.DO_NOT_DISTURB, "Banned, auto-join paused");
        } else if ("IN_LIMBO".equals(limboState)) {
            bot.setPresence(OnlineStatus.ONLINE, "In limbo, bridging guild chat");
        } else if ("ON_HYPIXEL_NOT_LIMBO".equals(limboState)) {
            bot.setPresence(OnlineStatus.IDLE, "On Hypixel, not in limbo");
        } else {
            bot.setPresence(OnlineStatus.DO_NOT_DISTURB, "Not on Hypixel");
        }
    }

    // =====================================================================
    // Discord messages and commands
    // =====================================================================

    void onDiscordMessage(MessageReceivedEvent event) {
        Message msg = event.getMessage();
        String raw = msg.getContentRaw();
        if (com.guildbridge.core.sb.PvCommands.isCommand(raw)) {
            String word = raw.strip().substring(1).split("\\s+")[0];
            var spec = com.guildbridge.core.sb.PvCommands.find(word).orElseThrow();
            switch (spec.name()) {
                case "commands" -> bot.replyHelp(msg, HelpPages.titles(), HelpPages.pages());
                case "version" -> DiscordBot.reply(msg, versionLine());
                default -> DiscordBot.reply(msg, "Account linking isn't available yet! Please use commands in-game only.");
            }
            return; // never relayed
        }
        if (BotCommand.looksLikeCommand(raw)) {
            handleCommand(event, msg);
            return; // commands are never relayed
        }
        if (!modConnected) {
            DiscordBot.react(msg, "❌"); // nothing to relay to right now
            return;
        }
        String text = OutgoingSanitizer.discordText(
                displayText(msg), msg.getAttachments().size(), msg.getStickers().size());
        String name = event.getMember() != null ? event.getMember().getEffectiveName() : event.getAuthor().getEffectiveName();
        String cmd = OutgoingSanitizer.toGuildCommand(name, text, filter, cfg.discordPrefix);
        if (cmd == null) return;
        long id = ids.incrementAndGet();
        pending.put(id, new Pending(msg, null, false, System.currentTimeMillis()));
        if (!ipc.send(Ipc.command(id, cmd, true, false))) {
            pending.remove(id);
            DiscordBot.react(msg, "❌");
        }
    }

    private void handleCommand(MessageReceivedEvent event, Message msg) {
        var parsed = BotCommand.parse(msg.getContentRaw());
        if (parsed.isEmpty()) return;
        BotCommand.Parsed p = parsed.get();
        String authorId = event.getAuthor().getId();
        boolean owner = cfg.isOwner(authorId);
        boolean admin = owner || admins.isAdmin(authorId);
        if ((BotCommand.requiresAdmin(p) && !admin) || (BotCommand.requiresOwner(p) && !owner)) {
            DiscordBot.react(msg, "⛔");
            return;
        }
        if (BotCommand.requiresAdmin(p) || BotCommand.requiresOwner(p)) {
            Main.LOG.info("Admin {} used {}", event.getAuthor().getName(), msg.getContentRaw());
        }

        switch (p) {
            case BotCommand.Invalid i -> DiscordBot.reply(msg, i.message());
            case BotCommand.Commands c -> bot.replyHelp(msg, HelpPages.titles(), HelpPages.pages());
            case BotCommand.Status s -> DiscordBot.reply(msg, statusText());
            case BotCommand.Uptime u -> DiscordBot.reply(msg, uptimeText());
            case BotCommand.Start s -> DiscordBot.reply(msg, game.start());
            case BotCommand.Stop s -> DiscordBot.reply(msg, game.stop(this::askModToQuit));
            case BotCommand.Restart r -> DiscordBot.reply(msg, game.restart(this::askModToQuit));
            case BotCommand.Logs l -> DiscordBot.reply(msg, logsText(l.lines()));
            case BotCommand.Rejoin r -> {
                bannedReason = null;
                limiter.reset("banned");
                boolean sent = ipc.send(Ipc.autoJoin(0));
                DiscordBot.reply(msg, sent ? "Auto-join re-enabled. The bridge will try to join again."
                        : "Auto-join will be re-enabled when the game connects.");
                updatePresence();
            }
            case BotCommand.Say s -> {
                String cmd = OutgoingSanitizer.toGuildCommand("", s.text(), filter, "");
                if (cmd == null) {
                    DiscordBot.reply(msg, "Nothing left to send after filtering.");
                    return;
                }
                if (!modConnected) {
                    DiscordBot.reply(msg, "The game is not connected right now.");
                    return;
                }
                long id = ids.incrementAndGet();
                pending.put(id, new Pending(msg, null, false, System.currentTimeMillis()));
                if (ipc.send(Ipc.command(id, cmd, true, false))) DiscordBot.react(msg, "✅");
                else DiscordBot.react(msg, "❌");
            }
            case BotCommand.GuildOnline g -> runAndReply(msg, p, "Guild online", admin);
            case BotCommand.GuildTop t -> runAndReply(msg, p,
                    t.day() == null ? "Guild top (today)" : "Guild top (" + t.day() + " days ago)", admin);
            case BotCommand.Run r -> runAndReply(msg, p, "/" + r.command(), admin);
            case BotCommand.AdminAdd a -> DiscordBot.reply(msg, adminAdd(msg, a.userId()));
            case BotCommand.AdminRemove a -> DiscordBot.reply(msg, adminRemove(a.userId()));
            case BotCommand.AdminShow a -> DiscordBot.reply(msg, adminListText());
        }
    }

    /** The message as people see it, with pings shown as nicknames or display names. */
    private static String displayText(Message msg) {
        try {
            var users = new java.util.HashMap<String, String>();
            var mentions = msg.getMentions();
            mentions.getUsers().forEach(u -> users.put(u.getId(), u.getEffectiveName()));
            mentions.getMembers().forEach(mem -> users.put(mem.getId(), mem.getEffectiveName()));
            var roles = new java.util.HashMap<String, String>();
            mentions.getRoles().forEach(r -> roles.put(r.getId(), r.getName()));
            var channels = new java.util.HashMap<String, String>();
            mentions.getChannels().forEach(c -> channels.put(c.getId(), c.getName()));
            return MentionText.render(msg.getContentRaw(), users, roles, channels);
        } catch (RuntimeException e) {
            return msg.getContentDisplay(); // never lose a message over name lookup
        }
    }

    private String adminAdd(Message msg, String id) {
        if (cfg.isOwner(id)) return "That's the owner, who is always an admin.";
        String name = msg.getMentions().getUsers().stream()
                .filter(u -> u.getId().equals(id)).map(u -> u.getName()).findFirst().orElse("");
        String note = (name.isEmpty() ? "" : name + ", ") + "added from Discord " + java.time.LocalDate.now();
        try {
            return admins.add(id, note) ? "<@" + id + "> is now an admin." : "<@" + id + "> is already an admin.";
        } catch (Exception e) {
            Main.LOG.warn("Could not update the admins file: {}", e.toString());
            return "Could not update the admins file: " + e.getMessage();
        }
    }

    private String adminRemove(String id) {
        if (cfg.isOwner(id)) return "The owner is always an admin. Change `owner` in guildbridge.json instead.";
        try {
            return admins.remove(id) ? "<@" + id + "> is no longer an admin." : "<@" + id + "> wasn't an admin.";
        } catch (Exception e) {
            Main.LOG.warn("Could not update the admins file: {}", e.toString());
            return "Could not update the admins file: " + e.getMessage();
        }
    }

    private String adminListText() {
        StringBuilder sb = new StringBuilder("**Owner:** <@" + cfg.owner.strip() + ">\n**Admins:**");
        var list = admins.list().stream().filter(id -> !cfg.isOwner(id)).toList();
        if (list.isEmpty()) sb.append(" none besides the owner");
        for (String id : list) {
            sb.append("\n<@").append(id).append("> (").append(id).append(")");
        }
        return sb.toString();
    }

    private void runAndReply(Message msg, BotCommand.Parsed p, String title, boolean admin) {
        if (!modConnected) {
            DiscordBot.reply(msg, "The game is not connected right now. " + statusText());
            return;
        }
        long now = System.currentTimeMillis();
        if (!admin && now - lastMemberCommandAt < MEMBER_COOLDOWN_MILLIS) {
            DiscordBot.react(msg, "⏳");
            return;
        }
        if (!admin) lastMemberCommandAt = now;
        long id = ids.incrementAndGet();
        pending.put(id, new Pending(msg, title, true, now));
        DiscordBot.react(msg, "👀");
        if (!ipc.send(Ipc.command(id, BotCommand.gameCommand(p), false, true))) {
            pending.remove(id);
            DiscordBot.react(msg, "❌");
        }
    }

    private String statusText() {
        if (!game.running()) return game.wanted() ? "The game is restarting." : "The game is stopped. Use `!start`.";
        if (!modConnected) return "The game is starting up.";
        if (bannedReason != null) return "Banned, auto-join paused (`!rejoin` to retry): " + bannedReason;
        if (limboState == null) return "Game running, waiting for its first location check.";
        return switch (limboState) {
            case "IN_LIMBO" -> "In limbo, relaying.";
            case "ON_HYPIXEL_NOT_LIMBO" -> "On Hypixel but not in limbo (server: " + lastServer + ").";
            default -> "Game running, not connected to Hypixel.";
        };
    }

    private String uptimeText() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("Supervisor up " + CommandResponse.formatDuration(now - startedAt) + ".");
        if (game.running()) sb.append(" Game up ").append(CommandResponse.formatDuration(now - game.startedAt())).append(".");
        else sb.append(" Game stopped.");
        if (joinedAt > 0) sb.append(" Connected to Hypixel for ").append(CommandResponse.formatDuration(now - joinedAt)).append(".");
        if ("IN_LIMBO".equals(limboState)) {
            sb.append(" In limbo for ").append(CommandResponse.formatDuration(now - stateSince)).append(".");
        }
        return sb.toString();
    }

    /** The last lines of the game log, minus noise, in a code block that fits a Discord message. */
    private String logsText(int lines) {
        Path log = cfg.gameLog();
        if (!Files.exists(log)) return "No game log found at `" + log + "`.";
        try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "r")) {
            long len = f.length();
            long from = Math.max(0, len - 256 * 1024);
            byte[] buf = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(buf);
            List<String> kept = new ArrayList<>();
            for (String line : new String(buf, StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.contains("unsupported uniform") || line.contains("textures/atlas")
                        || line.startsWith("\tat ") || line.startsWith("        at ")) continue;
                kept.add(line);
            }
            List<String> tail = kept.subList(Math.max(0, kept.size() - lines), kept.size());
            String body = String.join("\n", tail).replace("```", "'''");
            while (body.length() > 1880 && body.contains("\n")) body = body.substring(body.indexOf('\n') + 1);
            return "```\n" + body + "\n```";
        } catch (IOException e) {
            return "Could not read the game log: " + e.getMessage();
        }
    }
}
