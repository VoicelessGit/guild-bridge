package com.guildbridge.core;

/** Drops events written by the bridge's own Minecraft account so messages never echo back and forth. */
public final class LoopGuard {
    private final String ownIgn;

    public LoopGuard(String ownIgn) {
        this.ownIgn = ownIgn;
    }

    public boolean shouldRelay(BridgeEvent event) {
        String name = event.name();
        return ownIgn == null || name == null || !name.equalsIgnoreCase(ownIgn);
    }
}
