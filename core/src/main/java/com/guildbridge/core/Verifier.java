package com.guildbridge.core;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Codes for /verify. The Discord user gets a code privately and whispers it to the bridge from
 * the Minecraft account; a whisper of the right code from the right player completes the link.
 *
 * @param <H> whatever the caller needs to update the Discord reply later
 */
public final class Verifier<H> {
    public static final long CODE_MILLIS = 5 * 60_000;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I
    private static final int LENGTH = 6;

    public record Pending<H>(String discordId, String name, String uuid, String code, long expires, H handle) {}

    private final SecureRandom random = new SecureRandom();
    /** By Discord id: a new /verify replaces the old code. */
    private final Map<String, Pending<H>> byDiscord = new HashMap<>();

    public synchronized Pending<H> issue(String discordId, String name, String uuid, H handle, long now) {
        prune(now);
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            code = sb.toString();
        } while (findCode(code) != null);
        Pending<H> p = new Pending<>(discordId, name, uuid, code, now + CODE_MILLIS, handle);
        byDiscord.put(discordId, p);
        return p;
    }

    /**
     * A whisper reached the bridge. Returns the completed request when the code matches one
     * issued for this player and hasn't expired. Anything else is ignored quietly.
     */
    public synchronized Optional<Pending<H>> onWhisper(String fromName, String message, long now) {
        prune(now);
        String code = message == null ? "" : message.strip().toUpperCase(Locale.ROOT);
        Pending<H> p = findCode(code);
        if (p == null || !p.name().equalsIgnoreCase(fromName)) return Optional.empty();
        byDiscord.remove(p.discordId());
        return Optional.of(p);
    }

    private Pending<H> findCode(String code) {
        for (Pending<H> p : byDiscord.values()) {
            if (p.code().equals(code)) return p;
        }
        return null;
    }

    private void prune(long now) {
        byDiscord.values().removeIf(p -> p.expires() <= now);
    }
}
