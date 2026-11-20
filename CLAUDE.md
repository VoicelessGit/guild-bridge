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
3. `ChatParser.parse(line)` returns a `BridgeEvent` or empty (non-guild lines like
   "X joined the lobby!" must return empty).
4. `LoopGuard` drops lines authored by the bot's own IGN.
5. `WebhookPayload.forChat(event)` builds JSON. It ALWAYS sets `allowed_mentions: {parse: []}`
   so nobody in game can ping @everyone or roles.
6. One shared `java.net.http.HttpClient` posts it asynchronously. Never block the render thread.

Discord to game (phase 2, not built yet):
1. Bot (JDA) receives a message in the configured channel. Ignore bots and webhooks.
2. `OutgoingSanitizer.toGuildCommand(author, text)` builds `gc <author>: <text>`,
   strips newlines and section signs, enforces the 256 char chat limit.
3. Queue it and send on the client thread via
   `Minecraft.getInstance().execute(() -> player.connection.sendCommand(cmd))`,
   at most one message per ~1.5 s to respect Hypixel's chat cooldown.

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
- Guild join, leave, kick, promote messages

Add every new real sample as a test case in `ChatParserTest` before changing the regex.

## Roadmap

1. [x] core: parser, loop guard, webhook payload, outgoing sanitizer, tests
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
