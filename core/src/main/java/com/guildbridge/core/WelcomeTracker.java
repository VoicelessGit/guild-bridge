package com.guildbridge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Remembers who has already been welcomed, saved to a text file (one name per line),
 * so someone who leaves and rejoins is only welcomed on their first join.
 * Keyed by IGN. A name change would get a second welcome; switch to UUIDs later if that matters.
 */
public final class WelcomeTracker {
    private final Path file;
    private final Set<String> welcomed = new HashSet<>();

    public WelcomeTracker(Path file) throws IOException {
        this.file = file;
        if (file != null && Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) welcomed.add(line.strip().toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Returns true the first time a name is seen, and saves it. */
    public synchronized boolean firstTime(String name) throws IOException {
        if (!welcomed.add(name.toLowerCase(Locale.ROOT))) return false;
        if (file != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, String.join("\n", welcomed) + "\n");
        }
        return true;
    }
}
