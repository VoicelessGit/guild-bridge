#!/bin/sh
# Guild Bridge updater. Upload the new jar(s) to your home folder with the SSH window's
# "Upload file" button, then run:  ~/bridge/update.sh
#
# - guildbridge-supervisor.jar  -> ~/bridge (the game stays online during the restart)
# - guildbridge-<version>.jar   -> the mods folder, replacing the old mod (the game restarts)
set -e
BRIDGE="$HOME/bridge"
MODS="$HOME/.minecraft/fabric-26.2/mods"
changed=""
mod_changed=0

for jar in "$HOME"/guildbridge-[0-9]*.jar; do
    [ -e "$jar" ] || continue
    rm -f "$MODS"/guildbridge-[0-9]*.jar
    mv "$jar" "$MODS/"
    changed="$changed $(basename "$jar")"
    mod_changed=1
done

if [ -e "$HOME/guildbridge-supervisor.jar" ]; then
    mv "$HOME/guildbridge-supervisor.jar" "$BRIDGE/"
    changed="$changed guildbridge-supervisor.jar"
fi

if [ -z "$changed" ]; then
    echo "No new jars found in $HOME. Upload them first, then run this again."
    exit 1
fi

if [ "$mod_changed" = 0 ]; then
    # Only the supervisor changed: tell it to leave the game running.
    touch "$BRIDGE/.keep-game"
    echo "Updated:$changed (the game stays online)"
else
    echo "Updated:$changed (the game restarts to load the new mod)"
fi
sudo systemctl restart guildbridge
sleep 3
systemctl --no-pager --lines=0 status guildbridge | head -3
