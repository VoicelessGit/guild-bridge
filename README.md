# Guild Bridge

Fabric client mod that relays Hypixel guild chat to Discord. See `CLAUDE.md` for the full spec.

## Setup

1. Install JDK 25 (https://adoptium.net) and make sure `java -version` shows 25.
2. Run tests: `./gradlew :core:test`
3. Run the dev client: `./gradlew :mod:runClient`
4. On first launch the mod writes `run/config/guildbridge.json`. Fill in `botToken` and `channelId`, then restart.

## Using Claude Code

Open this folder and run `claude`. It reads `CLAUDE.md` automatically.
A good first prompt: "Do roadmap step 2: get :mod:build passing."
