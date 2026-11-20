# Guild Bridge

A client-side Fabric mod that bridges Hypixel guild chat and a Discord channel through a Discord bot.
It runs inside a real Minecraft client (normal window or HeadlessMc with `-lwjgl`) parked in
Hypixel limbo. It does NOT use Mineflayer or any reimplemented protocol client.

## Why it is built this way

- Mineflayer and console clients reimplement the protocol and carry a known ban risk on Hypixel.
  A Fabric mod on the real client sends normal client traffic.
- Hypixel's public API does not expose guild chat, so reading chat in game is the only route.
- Discord side is a bot account (JDA), not a webhook. Justin wants the bot's online status as a
  health light. Never use a personal Discord account (self-botting breaks Discord ToS).

## Layout

```
core/   Plain Java 25, no Minecraft or Discord dependency. Parsing, filtering, embed JSON,
        command building. Fully unit-tested with JUnit 5. Put every piece of logic that can
        live here, here.
mod/    Fabric client mod. Thin glue: chat events in, core calls, JDA bot out, commands out.
        JDA is bundled and relocated by the Shadow plugin.
```

Rule: if a change can be tested without Minecraft or Discord, it goes in `core` with a test.

## Commands

- `./gradlew :core:test` runs the logic tests (fast, no Minecraft).
- `./gradlew :mod:build` produces `mod/build/libs/guildbridge-<version>.jar` (with JDA inside).
  Ignore the `-slim` jar.
- `./gradlew :mod:runClient` launches a dev client with the mod.
- GitHub Actions (`.github/workflows/build.yml`) runs both on every push and uploads the jar.

## Data flow

Game to Discord:
1. Fabric `ClientReceiveMessageEvents.GAME` fires with a `Component`; take `getString()`.
2. `ChatRouter.accept(text)` splits on newlines and returns `BridgeEvent`s:
   - Lines between separator bars become one `Announcement`, only if the block mentions "guild",
     is not an invite, and does not start with "You " (those are about the bridge account itself;
     "You were kicked from the guild" becomes a "bridge account was kicked" notice).
     "X joined the guild!" sets `joinedMember`.
   - Other lines go to `ChatParser` (guild/officer chat, online status). Everything else is dropped.
3. `LoopGuard` drops lines authored by the bot's own IGN.
4. `DiscordEmbed.forEvent(event, filter)` builds embed JSON. Chat: author line = name + guild rank
   with skin head icon, green bar (aqua for officer). Announcement: gold, titled, new member's head
   as thumbnail. Status: gray. Player text goes through `LinkFilter`, `ProfanityFilter`, then
   markdown escaping.
5. `DiscordBot.post(json)` converts with `EmbedBuilder.fromData(DataObject.fromJson(json))` and
   queues it. JDA handles Discord rate limits. Mentions are disabled globally via
   `MessageRequest.setDefaultMentions(none)`.
6. If an announcement has `joinedMember` and `WelcomeTracker.firstTime(name)` is true, queue
   `OutgoingSanitizer.welcomeCommand(config.welcomeMessage, name)` on `GameSender`.

Discord to game:
1. `DiscordBot.onMessageReceived` takes messages in the configured channel, ignoring bots
   (including itself, so no echo loop) and webhooks.
2. `OutgoingSanitizer.discordText(content, attachments, stickers)`: attachments and stickers are
   never relayed, they become `[attachment]` / `[sticker]`.
3. `OutgoingSanitizer.toGuildCommand(name, text, filter)`: blocks links, censors, strips newlines and
   section signs, builds `gc <name>: <text>` within the 256 char chat limit.
4. `GameSender.queue(cmd, onRejected)`: sends one command per `gameSendGapMillis` on the client
   thread, skips repeats of the last 3 sends (`DuplicateGuard`; Hypixel rejects them anyway).
   On skip or full queue, the bot reacts with ❌ to the Discord message.

Limbo check (`LimboTracker`, the bridge must always sit in limbo):
- After every server join and world change (3 s delay) and every 10 minutes, if the server address
  contains hypixel.net, send `/locraw` via `GameSender.queueUtility` (no repeat check).
- The JSON reply (`{"server":"limbo"}` in limbo, per community knowledge; confirm in game) is
  parsed by core `Locraw` and hidden from chat through `ClientReceiveMessageEvents.ALLOW_GAME`, world changes via `ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE` (renamed from ClientWorldEvents in 26.1),
  but only within 15 s of asking.
- It never moves the player. Hypixel's AFK system moves idle lobby players to limbo by itself.
  Relaying continues outside limbo; the status light and a channel notice flag it.

Bot status (health light), set on every limbo state change, with a one-line notice in the channel:
- Online, "In limbo, bridging guild chat".
- Idle, "On Hypixel, not in limbo" (notice includes the server name).
- Do Not Disturb, "Not on Hypixel" (startup and disconnect).
- Offline: game closed (`jda.shutdown()` on `CLIENT_STOPPING`) or crashed (Discord times it out).

Auto-join (`AutoJoin`, for headless runs):
- `autoJoinServer` in config ("" = off; "mc.hypixel.net" for the bridge). Screen-independent
  (the screen-based version never fired under HeadlessMc): after `CLIENT_STARTED` + 10 s, whenever
  `level == null && getConnection() == null`, call `ConnectScreen.startConnecting`. At least 60 s
  between attempts so a slow connect is never doubled; backoff 60 s, 2, 4, 8, max 10 min; reset on
  join. Logs "Auto-join is ON/OFF" at startup. `Minecraft.screen` and `getOverlay()` are not
  accessible in 26.x; the open screen is tracked via Fabric `ScreenEvents` only as the parent.

Admin console (in the bridge channel, never relayed):
- `config/guildbridge-admins.txt`: Discord user IDs, one per line, # comments. Created with
  instructions on first launch. Re-read when the file changes, no restart needed (`AdminList`).
- `!mc <command>` runs a game command via `GameSender.queueUtility` and reacts ✅.
  `!status` replies with limbo state and auto-join state. Non-admins get ⛔.

## Moderation

- `ProfanityFilter` severities: 1 mild (not censored by default), 2 strong profanity, 3 slurs.
  `filterThreshold` defaults to 2 ("mid to high"). Applies in BOTH directions. Censored words become
  asterisks of the same length. Handles caps, leetspeak, stretched letters, common suffixes, whole
  words only.
- Bundled list: `core/src/main/resources/guildbridge/filter-words.txt`. Override with
  `config/guildbridge-filter.txt`. The severity 3 tier is EMPTY and must be filled by Justin from a
  maintained list. Do not generate slur lists.
- `LinkFilter` replaces links with `[blocked link]` in BOTH directions, always on (URLs, www.,
  bare domains on common TLDs, IPv4 with port). Wired into `DiscordEmbed` and `OutgoingSanitizer`,
  so new code paths get it by using those.

## Discord setup (manual, not code)

- Developer Portal > Bot: Reset Token (goes in `botToken`), enable Message Content Intent,
  disable Public Bot. The client secret is NOT needed.
- Invite permissions: View Channels, Send Messages, Read Message History, Add Reactions,
  Embed Links, Bypass Slowmode (Justin's invite URL already has exactly these).
- Bridge channel: slowmode 5 to 10 s. The bot needs Bypass Slowmode to post game chat at full
  speed. As of Feb 2026, Manage Messages / Manage Channel no longer bypass slowmode automatically.
- `channelId`: Developer Mode on, right-click channel, Copy Channel ID.

## Hypixel chat formats

Verified from real screenshots (Nov 2026):
```
Guild > [VIP+] ExamplePlayer [GM]: hi                              ranked member with guild rank
Guild > BridgeBot: hey!                                         non, no guild rank
[MVP+] Mc_Gregory joined the lobby!                            not guild chat, ignored
```
Announcement blocks sit between cyan strikethrough bars (plain text: a long run of dashes).
Guild side: `BridgeBot joined the guild!` (ranks shown for ranked players, e.g. `[VIP] Name ...`),
`BridgeBot was kicked from the guild by [VIP+] ExamplePlayer!`.
Bridge side (ignored or turned into a notice): `You were kicked from the guild by ... for reason
'test'.`, `[VIP+] X has invited you to join their guild, NAME!` + `Click here to accept or type
/guild accept X!`, `You joined NAME!`, `You invited X to your guild. They have 5 minutes to accept.`

Unverified (guessed): `Guild > Name joined.` / `left.` online status, `Officer > ...`.
Leave, promote, demote, level up, quest tier blocks are relayed as-is without special handling.

Add every new real sample as a test case before changing a regex.

## Roadmap

1. [x] core: parser, router, filters, duplicate guard, welcome tracker, embed builder, locraw (49 tests)
2. [x] First real build works on Justin's Mac (only fix needed: ClientWorldEvents rename).
       Original notes: Targets Minecraft 26.2 (unobfuscated), Loader 0.19.3, Loom 1.17,
       Fabric API 0.155.2+26.2, JDA 6.5.0, Shadow 9.4.3, Gradle 9.5.1, Java 25. The mod code has
       NEVER been compiled. Expect to fix Fabric API names changed in 26.x (see the Fabric API
       26.1 porting guide on docs.fabricmc.net), Mojang names (e.g. `ServerData.ip`), and JDA 6
       API details. Check the shadow jar does not include voice/DAVE natives; exclude them if so.
3. [ ] Fill severity 3 tier of the filter list
4. [ ] Test in a normal client with a private test guild and test Discord server
5. [~] Auto-join and reconnect with backoff (written, not yet compiled). Limbo is reached by
       Hypixel's AFK system; confirm how long that takes in practice.
6. [~] HeadlessMc 3.0.0-RC9 runs Fabric 26.2 headless on macOS (verified). Launch with
       `launch fabric 26.2 -lwjgl`; its game folder is `~/Library/Application Support/minecraft/fabric-26.2/`
       (mods/ and config/ go there). Next: multi-day trial, watch memory with the Spark mod.

## Where it runs

Everything is cross-platform. Justin plans to run it on his Mac (macOS) so his PC is free.
Build with `./gradlew` on macOS too. For long runs on a Mac, prevent sleep (`caffeinate -dimsu`
in a terminal, or System Settings power options); a closed MacBook lid sleeps unless an external
display and power are connected.

## Conventions

- Java 25. No em dashes in user-facing strings or docs.
- Secrets (bot token) live only in `config/guildbridge.json`, never in git.
- Do not add any automation beyond chat relay (no auto-movement, no anti-AFK, no auto-accepting
  invites). Keeping the client's behavior boring is the whole point.
- Memory: never keep an unbounded collection of messages. Queues are bounded; send and drop.
