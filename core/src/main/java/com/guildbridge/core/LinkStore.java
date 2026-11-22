package com.guildbridge.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Discord account to Minecraft account links (guildbridge-links.json). One Minecraft account per
 * Discord user and the other way around: linking an account that is already linked moves it.
 */
public final class LinkStore {
    /** @param name the Minecraft name when linked (may be out of date; the uuid is what counts) */
    public record Link(String uuid, String name, long linkedAt) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<String, Link> byDiscord = new LinkedHashMap<>();

    public LinkStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            Map<String, Link> m = GSON.fromJson(Files.readString(file), new TypeToken<Map<String, Link>>() {}.getType());
            if (m != null) byDiscord.putAll(m);
        }
    }

    public synchronized Optional<Link> get(String discordId) {
        return Optional.ofNullable(byDiscord.get(discordId));
    }

    /** Links and saves. Returns the Discord id that held this Minecraft account before, if any. */
    public synchronized Optional<String> link(String discordId, String uuid, String name, long now) throws IOException {
        String previous = null;
        for (var it = byDiscord.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().uuid().equalsIgnoreCase(uuid) && !e.getKey().equals(discordId)) {
                previous = e.getKey();
                it.remove();
            }
        }
        byDiscord.put(discordId, new Link(uuid.toLowerCase(java.util.Locale.ROOT), name, now));
        save();
        return Optional.ofNullable(previous);
    }

    /** Removes a link by Discord id or by Minecraft name. Returns the removed link. */
    public synchronized Optional<Link> unlink(String discordIdOrName) throws IOException {
        Link removed = byDiscord.remove(discordIdOrName);
        if (removed == null) {
            for (var it = byDiscord.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue().name() != null && e.getValue().name().equalsIgnoreCase(discordIdOrName)) {
                    removed = e.getValue();
                    it.remove();
                    break;
                }
            }
        }
        if (removed != null) save();
        return Optional.ofNullable(removed);
    }

    public synchronized Map<String, Link> all() {
        return new LinkedHashMap<>(byDiscord);
    }

    private void save() throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(byDiscord));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
