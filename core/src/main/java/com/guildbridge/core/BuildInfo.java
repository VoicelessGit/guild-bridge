package com.guildbridge.core;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;

/** Version and build time, stamped into each jar by Gradle (guildbridge/version-*.properties). */
public record BuildInfo(String version, long built) {
    public static final BuildInfo UNKNOWN = new BuildInfo("?", 0);

    /** @param which "supervisor" or "mod" */
    public static BuildInfo read(String which) {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/guildbridge/version-" + which + ".properties")) {
            if (in == null) return UNKNOWN;
            Properties p = new Properties();
            p.load(in);
            return new BuildInfo(p.getProperty("version", "?"), Long.parseLong(p.getProperty("built", "0")));
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /** "Nov 22, 2026" in the given zone, or "unknown date". */
    public String date(ZoneId zone) {
        if (built <= 0) return "unknown date";
        return DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH).withZone(zone).format(Instant.ofEpochMilli(built));
    }

    /** "Guild Bridge 0.2.1 (updated Nov 24, 2026) | mod 0.2.0 (Nov 22, 2026)" */
    public static String versionLine(BuildInfo supervisor, BuildInfo mod, ZoneId zone) {
        String line = "Guild Bridge " + supervisor.version() + " (updated " + supervisor.date(zone) + ")";
        if (mod == null) return line + " | mod not connected";
        return line + " | mod " + mod.version() + " (" + mod.date(zone) + ")";
    }
}
