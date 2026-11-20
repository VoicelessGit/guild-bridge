# Guild Bridge

Fabric client mod that relays Hypixel guild chat to Discord. See `CLAUDE.md` for the full spec.

## Setup

1. Install JDK 21.
2. Fill in versions in `gradle.properties` from https://fabricmc.net/develop
3. Generate the wrapper once: `gradle wrapper`
4. Run tests: `./gradlew :core:test`
5. Run the dev client: `./gradlew :mod:runClient`
6. On first launch the mod writes `run/config/guildbridge.json`. Paste your Discord webhook URL into it and restart.

## Using Claude Code

Open this folder and run `claude`. It reads `CLAUDE.md` automatically.
A good first prompt: "Do roadmap step 2, then step 3."
