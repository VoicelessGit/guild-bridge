package com.guildbridge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.Set;

/**
 * Discord user IDs allowed to use admin commands, read from a hand-edited text file:
 * one ID per line, # starts a comment. The file is re-read whenever it changes on disk,
 * so edits take effect without restarting the game. Missing file means no admins.
 */
public final class AdminList {
    static final String TEMPLATE = """
            # Guild Bridge admins: Discord user IDs, one per line.
            # Get an ID: Discord Settings > Advanced > Developer Mode on, then right-click a user > Copy User ID.
            # Everyone in the bridge channel can use the member commands (!commands lists them).
            # Admins can also use the admin commands. The owner (set in guildbridge.json) is always
            # an admin and can manage this list from Discord with !admin add/remove/list.
            # Changes apply right away, no restart needed.
            """;

    private final Path file;
    private Set<String> ids = Set.of();
    private FileTime loadedAt;

    public AdminList(Path file) {
        this.file = file;
    }

    /** Creates the file with instructions if it does not exist yet. */
    public void createIfMissing() throws IOException {
        if (Files.exists(file)) return;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, TEMPLATE);
    }

    public synchronized boolean isAdmin(String userId) {
        reloadIfChanged();
        return userId != null && ids.contains(userId.strip());
    }

    /** All admin IDs in the file, sorted. */
    public synchronized List<String> list() {
        reloadIfChanged();
        return new ArrayList<>(new TreeSet<>(ids));
    }

    /**
     * Adds an ID as a new line, with an optional note as a comment. Returns false if it was
     * already listed. Hand-written lines and comments are kept.
     */
    public synchronized boolean add(String userId, String note) throws IOException {
        String id = userId.strip();
        if (!id.matches("\\d{15,25}")) throw new IllegalArgumentException("Not a Discord user ID: " + id);
        reloadIfChanged();
        if (ids.contains(id)) return false;
        createIfMissing();
        String existing = Files.readString(file);
        String clean = note == null ? "" : note.replaceAll("[\\r\\n#]", " ").strip();
        String line = clean.isEmpty() ? id : id + "  # " + clean;
        String sep = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
        Files.writeString(file, existing + sep + line + "\n");
        loadedAt = null;
        reloadIfChanged();
        return true;
    }

    /** Removes every line holding this ID. Returns false if it was not listed. */
    public synchronized boolean remove(String userId) throws IOException {
        String id = userId.strip();
        reloadIfChanged();
        if (!ids.contains(id) || !Files.exists(file)) return false;
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (!line.replaceAll("#.*", "").strip().equals(id)) kept.add(line);
        }
        Files.writeString(file, String.join("\n", kept) + "\n");
        loadedAt = null;
        reloadIfChanged();
        return true;
    }

    private void reloadIfChanged() {
        try {
            if (!Files.exists(file)) {
                ids = Set.of();
                loadedAt = null;
                return;
            }
            FileTime modified = Files.getLastModifiedTime(file);
            if (modified.equals(loadedAt)) return;
            Set<String> next = new HashSet<>();
            for (String line : Files.readAllLines(file)) {
                String s = line.replaceAll("#.*", "").strip();
                if (s.matches("\\d{15,25}")) next.add(s);
            }
            ids = Set.copyOf(next);
            loadedAt = modified;
        } catch (IOException e) {
            // Keep the last good list if the file is briefly unreadable mid-save.
        }
    }
}
