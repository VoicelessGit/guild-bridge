# Guild Bridge

A client-side Fabric mod that bridges Hypixel guild chat and a Discord channel.
It runs inside a real Minecraft client (normal window or HeadlessMc with `-lwjgl`)
parked in Hypixel limbo. It does NOT use Mineflayer or any reimplemented protocol client.

## Why it is built this way

- Mineflayer and console clients reimplement the protocol and carry a known ban risk on Hypixel.
  A Fabric mod on the real client sends normal client traffic.
- Hypixel's public API does not expose guild chat, so reading chat in-game is the only route.
- Discord side uses a webhook (game to Discord) and a bot (Discord to game).
  Never use a personal Discord account (self-botting breaks Discord ToS).

## Layout

```
core/   Plain Java 21, no Minecraft dependency. All parsing, sanitizing, payload building.
        Fully unit-tested with JUnit 5. Put every piece of logic that can live here, here.
mod/    Fabric client mod. Thin glue only: chat event in, core calls, HTTP out, commands out.
```

Rule: if a change can be tested without Minecraft, it goes in `core` with a test.

## Commands

- `./gradlew :core:test` runs the parser and sanitizer tests (fast, no Minecraft).
- `./gradlew :mod:runClient` launches a dev client with the mod.
- `./gradlew :mod:build` produces the mod jar in `mod/build/libs/`.

## Data flow

Game to Discord:
1. Fabric `ClientReceiveMessageEvents.GAME` fires with a `Component`.
2. Take `component.getString()` (plain text, color codes already stripped).
3. `ChatRouter.accept(text)` splits on newlines and returns `BridgeEvent`s:
   - Lines between separator bars become one `Announcement` (only if the block mentions "guild"
     and is not an invite). If it says "X joined the guild!", `joinedMember` is set.
   - Other lines go to `ChatParser` (guild/officer chat, online status). Everything else is dropped.
4. `LoopGuard` drops lines authored by the bot's own IGN.
5. `WebhookPayload.forEvent(event, filter)` censors player text with `ProfanityFilter`, escapes
   markdown, and ALWAYS sets `allowed_mentions: {parse: []}` so nobody can ping @everyone.
6. `WebhookSender` posts in order on one background thread. Never block the render thread.
7. If an announcement has `joinedMember` and `WelcomeTracker.firstTime(name)` is true, queue
   `OutgoingSanitizer.welcomeCommand(config.welcomeMessage, name)` on `GameSender`.

Discord to game (phase 2, not built yet):
1. Bot (JDA) receives a message in the configured channel. Ignore bots and webhooks.
2. `OutgoingSanitizer.toGuildCommand(author, text, filter)` censors, builds `gc <author>: <text>`,
   strips newlines and section signs, enforces the 256 char chat limit.
3. `GameSender.queue(cmd)`. It already spaces sends by `gameSendGapMillis`, skips repeats of the
   last 3 sends (`DuplicateGuard`, Hypixel rejects them anyway), and sends on the client thread.

## Moderation

- `ProfanityFilter` severities: 1 mild (not censored by default), 2 strong profanity, 3 slurs.
  `filterThreshold` in config defaults to 2 ("mid to high"). Applies in BOTH directions.
- Bundled list: `core/src/main/resources/guildbridge/filter-words.txt`. Users can override with
  `config/guildbridge-filter.txt`. The severity 3 tier is EMPTY and must be filled by Justin
  from a maintained list. Do not generate slur lists.
- Censored words become asterisks of the same length.

## Discord channel setup (manual, not code)

- Slowmode 5 to 10 s on the bridge channel (Channel settings > Overview > Slowmode).
- Game to Discord uses a webhook, which slowmode does not throttle.
- For phase 2, give the bot's role the standalone "Bypass Slowmode" permission. As of Feb 2026,
  Manage Messages / Manage Channel no longer bypass slowmode automatically.
- Users sending from Discord to game are rate-limited by slowmode, which also protects the
  in-game chat cooldown.

## Hypixel chat formats (verified from a real screenshot, Nov 2026)

```
Guild > [VIP+] ExamplePlayer [GM]: hi          ranked member with guild rank
Guild > BridgeBot: hey!                     non, no guild rank
[MVP+] Mc_Gregory joined the lobby!        NOT guild chat, must be ignored
```

Still unverified, collect real samples before relying on them:
- `Guild > Name joined.` / `Guild > Name left.` (online status)
- `Officer > ...` (officer chat)
- `[MVP++]`, `[YOUTUBE]`, staff ranks
- Guild announcement blocks: separator style, and exact wording of join, leave, kick, promote,
  demote, level up, quest tier. `ChatRouter` currently guesses `[VIP] Name joined the guild!`
  between lines of 10+ dashes or underscores. Justin will send real samples.

Add every new real sample as a test case (`ChatParserTest` or `FeaturesTest`) before changing a regex.

## Roadmap

1. [x] core: parser, loop guard, webhook payload, outgoing sanitizer, tests
1b. [x] core: profanity filter, duplicate guard, welcome tracker, announcement router (30 tests)
1c. [ ] Fill severity 3 tier of the filter list; replace guessed announcement formats with real ones
2. [ ] Fill in exact Fabric versions in `gradle.properties` from https://fabricmc.net/develop
       and confirm `:mod:build` works. Generate the Gradle wrapper (`gradle wrapper`).
3. [~] mod: config file at `config/guildbridge.json` (webhook URL now; bot token and channel id
       reserved for phase 2). Own IGN comes from `Minecraft.getInstance().getUser().getName()`.
4. [~] mod: game to Discord via webhook. Glue code is written in `GuildBridgeClient` and
       `WebhookSender` but has never been compiled. Fix any Fabric API or mapping name errors first.
5. [ ] Rate-limit handling for the webhook (Discord returns 429 with `retry_after`)
6. [ ] Disconnect detection, auto-reconnect after a delay, get back to limbo (verify in game which
       route works reliably; do not guess). Post "bridge offline/online" to Discord.
7. [ ] Phase 2: JDA bot for Discord to game, outgoing queue with cooldown
8. [ ] Trial run in HeadlessMc, watch memory with the Spark mod (`/spark heapsummary`)

## Conventions

- Java 21. No em dashes in user-facing strings or docs.
- Secrets (webhook URL, bot token) live only in `config/guildbridge.json`, never in git.
- Do not add any automation beyond chat relay (no auto-movement, no anti-AFK, no auto-accepting
  invites). Keeping the client's behavior boring is the whole point.
- Memory: never keep an unbounded collection of messages. Send and drop.
