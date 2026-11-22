# Guild Bridge

Relays Hypixel guild chat to a Discord channel and back. See `CLAUDE.md` for how it works.

## Build

Needs JDK 25.

```
./gradlew :core:test :mod:build :supervisor:build
```

You get two files:
- `mod/build/libs/guildbridge-0.1.0.jar` goes in HeadlessMc's game `mods` folder (with Fabric API).
- `supervisor/build/libs/guildbridge-supervisor.jar` goes in the bridge folder and runs on Java 21+.

## Run on a Linux server (Ubuntu)

```
sudo apt update && sudo apt install -y openjdk-21-jre-headless
mkdir -p ~/bridge   # put the supervisor jar, headlessmc-launcher-linux-x64 and your config files here
cd ~/bridge && chmod +x headlessmc-launcher-linux-x64
./headlessmc-launcher-linux-x64      # then: login, account select <name>, launch fabric 26.2 -lwjgl, Ctrl+C
mkdir -p ~/.minecraft/fabric-26.2/mods && mv guildbridge-0.2.0.jar fabric-api-*.jar ~/.minecraft/fabric-26.2/mods/
printf "onboardAccessibility:false\nnarrator:0\n" >> ~/.minecraft/fabric-26.2/options.txt
java -jar guildbridge-supervisor.jar # first run writes guildbridge.json; fill it in and run again
```

Then install it as a service so it starts on boot and restarts itself, using
supervisor/src/main/resources/deploy/guildbridge.service (replace USER_NAME). For `!nw` and `!weight`,
install Node.js (`sudo apt install -y nodejs npm`). Later updates: upload the new jar(s) to your home
folder and run `~/bridge/update.sh`.

## Using Claude Code

Open this folder and run `claude`. It reads `CLAUDE.md` automatically.
