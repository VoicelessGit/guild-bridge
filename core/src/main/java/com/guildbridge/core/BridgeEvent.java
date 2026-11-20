package com.guildbridge.core;

/** Something from guild chat worth relaying. */
public sealed interface BridgeEvent {

    enum Channel { GUILD, OFFICER }

    /** A chat message. rank and guildRank are null when absent (for example a non with no guild rank). */
    record Chat(Channel channel, String rank, String name, String guildRank, String message)
            implements BridgeEvent {}

    /** A member came online or went offline. */
    record Status(String name, boolean joined) implements BridgeEvent {}

    default String name() {
        return switch (this) {
            case Chat c -> c.name();
            case Status s -> s.name();
        };
    }
}
