# Guild Bridge

Bridges Hypixel guild chat and a Discord channel. Two programs:

- **Supervisor** (`supervisor/`): always running. Holds the ONLY Discord bot. Starts, stops and
  auto-restarts Minecraft (through HeadlessMc), watches for hangs, handles kicks, sends alert pings.
- **Mod** (`mod/`): Fabric client mod inside a real Minecraft client (HeadlessMc `-lwjgl`, parked in
  Hypixel limbo). Reads chat, reports location and disconnects, runs commands it is given.
  No Discord code, never sees the bot token.

They talk over a private TCP connection on 127.0.0.1 (core `Ipc`). The supervisor launches the game
with `-Dguildbridge.ipc.port` and `-Dguildbridge.ipc.secret` (fresh random secret per run); the mod
connects, says hello with the secret, and sends a heartbeat every 20 s. Started any other way, the
mod stays idle.

## Why it is built this way

- Mineflayer and console clients reimplement the protocol and carry a known ban risk on Hypixel.
  A Fabric mod on the real client sends normal client traffic.
- Hypixel's public API does not expose guild chat, so reading chat in game is the only route.
- One bot that stays online even when the game is down, so `!start`/`!stop`/`!logs` always work and
  the status light is always accurate. Justin explicitly wanted one bot, not two.
- Never use a personal Discord account (self-botting breaks Discord ToS).

## Layout

```
core/        Plain Java (compiles at --release 21). All logic that can be tested without Minecraft or
             Discord: parsing, routing, filters, embeds, commands, IPC messages, disconnect kinds,
             alert limiting. JUnit 5. Compiled into BOTH the mod and the supervisor.
mod/         Fabric client mod (Java 25, Minecraft 26.2 unobfuscated, Mojang names). Thin.
supervisor/  Standalone app (targets Java 21), JDA bundled with Shadow -> guildbridge-supervisor.jar.
```

Rule: if a change can be tested without Minecraft or Discord, it goes in `core` with a test.

## Commands

- `./gradlew :core:test` logic tests
- `./gradlew :mod:build` -> `mod/build/libs/guildbridge-0.1.0.jar`
- `./gradlew :supervisor:build` -> `supervisor/build/libs/guildbridge-supervisor.jar`
- Run: in the supervisor folder, `java -jar guildbridge-supervisor.jar` (needs Java 21+)
- GitHub Actions builds all three and uploads both jars.

## Supervisor folder

`guildbridge.json` (config, written with defaults on first run), `guildbridge-admins.txt`,
`guildbridge-welcomed.txt`, optional `guildbridge-filter.txt`, the HeadlessMc launcher
(`headlessmc-launcher*`, found automatically or set `headlessMcPath`), and `game-console.log`
(HeadlessMc output, overwritten each start). The mod jars go in HeadlessMc's game folder
(`~/.minecraft/fabric-26.2/mods` on Linux, `%APPDATA%\.minecraft\fabric-26.2\mods` on Windows).
HeadlessMc must be logged in once interactively (`login`, `account select <name>`).

## Data flow

Game to Discord: mod `ChatRouter` -> `Ipc.event` -> supervisor `LoopGuard` (account from hello) ->
`DiscordEmbed.forEvent(event, filter)` (link filter, profanity filter, markdown escape) -> channel.
Welcome: announcement with `joinedMember` + `WelcomeTracker.firstTime` -> `Ipc.command(... gc welcome ...)`.

Discord to game: supervisor `OutgoingSanitizer.toGuildCommand(name, text, filter, discordPrefix)` ->
`Ipc.command(id, cmd, checkRepeats=true, capture=false)` -> mod `GameSender.queueChat` (gap, last-3
repeat check) -> `sent`/`rejected` back (rejected -> ❌ reaction). Not connected -> ❌ right away.

Command replies (`!g online`, `!g top`, `!mc`): `capture=true`; mod `CommandCapture` collects
non-guild lines for 2.5 s after sending and returns `capture{id,text}`; supervisor replies with
`DiscordEmbed.response`. Busy -> `rejected{reason:busy}` -> ⏳. Members share a 10 s cooldown.

## Supervisor behavior

- Status light: Online "In limbo" / Idle "On Hypixel, not in limbo" / DND "Not on Hypixel" /
  Idle "Starting the game" / DND "Game stopped" / Idle "Restarting the game" /
  DND "Banned, auto-join paused". Offline only if the supervisor itself is down.
- Game process (`GameProcess`): runs `headlessmc launch fabric <ver> -lwjgl --jvm "<jvmArgs> -D...ipc..."`.
  Unexpected exit -> restart after `restartDelaySeconds`; 3 crashes in 15 min -> alert, wait 10 min.
  Stop asks the mod to quit (`stop` message -> `Minecraft.stop()`), then kills the process tree after 20 s.
- Watchdog: connected but no heartbeat for 2 min -> alert + restart. Running 5 min without the mod
  connecting -> alert + restart.
- Disconnects: mod `DisconnectWatcher` reads all text on `DisconnectedScreen` by reflection
  (title + Component fields + record components; 26.x is unobfuscated), falls back to a generic reason
  3 s after `DISCONNECT` if no screen explains it. Supervisor posts every disconnect with its real
  reason and classifies it (core `DisconnectKind`):
  - BANNED: pause auto-join until `!rejoin`, alert.
  - DUPLICATE_LOGIN: pause 10 min, alert.
  - LOGIN_FAILED: alert if 3+ in 30 min.
  - NETWORK / KICKED: rejoin normally; alert if 5+ disconnects in 30 min.
  - UNKNOWN: alert with the exact text (add a pattern + test in `DisconnectKind`).
- Outside limbo longer than `notInLimboAlertMinutes` -> alert. Discord permission failures -> alert
  (falls back to DMs when the channel itself cannot be used).
- Alerts: `alertPing` = user IDs or `role:<id>`. Only alerts can ping; everything else sends with
  mentions disabled. Same alert key pings at most once per `alertCooldownMinutes`.

## Bot commands (core `BotCommand`, never relayed)

Members: `!commands`, `!g online`, `!g top`, `!g top <1-10>`.
Admins (`guildbridge-admins.txt`, re-read on change): also `!say`, `!mc`, `!status`, `!uptime`,
`!start`, `!stop`, `!restart`, `!logs [1-50]`, `!rejoin`. Non-admins get ⛔.
Owner (`owner` in guildbridge.json, always an admin): `!admin add|remove <id or @mention>`, `!admin list`.
These edit guildbridge-admins.txt in place and keep hand-written lines and comments.

## Mod behavior

- Auto-join (`AutoJoin`): settings from the supervisor. Screen-independent: when not in a world and
  not connecting, `ConnectScreen.startConnecting`. Backoff from `autoJoinRetrySeconds` doubling to
  `autoJoinMaxRetrySeconds`, 5 s floor, reset on join. Pausable by the supervisor.
- Limbo (`LimboTracker`): `/locraw` after every join/level change (3 s delay) and every 10 min,
  reply hidden via `ALLOW_GAME`. Auto `/limbo` after `autoLimboDelaySeconds` when not in limbo,
  attempts at least 30 s apart, never stacked.
- `Minecraft.screen` and `getOverlay()` are not accessible in 26.x; screens are tracked via
  Fabric `ScreenEvents`. Fabric API 26.1 renamed `ClientWorldEvents` -> `ClientLevelEvents`.

## Moderation

- `ProfanityFilter`: severities 1 mild (not censored by default), 2 strong, 3 slurs; `filterThreshold`
  default 2; both directions; same-length asterisks. Severity 3 tier of the bundled list is EMPTY and
  must be filled by Justin from a maintained list. Do not generate slur lists.
- `LinkFilter`: `[blocked link]`, both directions, always on.
- Attachments and stickers are never relayed (`[attachment]` / `[sticker]`).

## Hypixel chat formats (verified Nov 2026 unless noted)

```
Guild > [VIP+] ExamplePlayer [GM]: hi            Guild > BridgeBot: hey!
BridgeBot joined the guild!                   (ranked: [VIP] Name joined the guild!)
BridgeBot was kicked from the guild by [VIP+] ExamplePlayer!
You were kicked from the guild by ... / You joined NAME! / invites  -> bridge-side, handled
```
Announcement blocks sit between strikethrough bars (plain text: a long run of dashes).
Unverified: `Guild > Name joined.`/`left.`, `Officer > ...`, exact /locraw limbo reply, ban texts.

## Discord setup

Bot tab: Message Content Intent ON, Public Bot OFF, token in `botToken`. Channel permissions for the
bot: View Channel, Send Messages, Embed Links, Add Reactions, Read Message History, Bypass Slowmode
(Manage Messages no longer bypasses slowmode since Feb 2026). Slowmode 5 to 10 s on the channel.

## Conventions

- NEVER let an exception escape a handler on the game thread (chat events, ticks, connection and
  screen events): Minecraft turns it into "Network Protocol Error" and disconnects. Every Fabric
  handler is wrapped. JDA throws synchronously on missing permissions, so every Discord call in the
  supervisor is wrapped too. Learned the hard way, Nov 2026.
- No em dashes in user-facing strings or docs.
- Secrets live only in the supervisor's `guildbridge.json`, never in git, never in the mod.
- No automation beyond chat relay, limbo upkeep and reconnecting.
- Bounded queues and windows everywhere; nothing grows without limit.
- When giving Justin file locations, give a ready-to-paste command that opens the file, not a bare path.
