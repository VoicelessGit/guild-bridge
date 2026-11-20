package com.guildbridge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
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
            # Admins can use !mc <command> and !status in the bridge channel.
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
