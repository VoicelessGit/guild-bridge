package com.guildbridge.core.sb;

import java.util.Locale;

/** Number and level formatting for one-line replies. */
public final class Fmt {
    private Fmt() {}

    /** 950, 12.4k, 1.1M, 1.25B. */
    public static String num(double v) {
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1e12) return sign + trim(a / 1e12) + "T";
        if (a >= 1e9) return sign + trim(a / 1e9) + "B";
        if (a >= 1e6) return sign + trim(a / 1e6) + "M";
        if (a >= 1e3) return sign + trim(a / 1e3) + "k";
        return sign + (a == Math.floor(a) ? String.valueOf((long) a) : String.format(Locale.ROOT, "%.1f", a));
    }

    /** Up to 3 significant digits, trailing zeros dropped: 1.10 -> 1.1, 12.40 -> 12.4, 812 -> 812. */
    private static String trim(double x) {
        String s;
        if (x >= 100) s = String.format(Locale.ROOT, "%.0f", Math.floor(x));
        else if (x >= 10) s = String.format(Locale.ROOT, "%.1f", Math.floor(x * 10) / 10);
        else s = String.format(Locale.ROOT, "%.2f", Math.floor(x * 100) / 100);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /** 1,204 */
    public static String comma(double v) {
        return String.format(Locale.ROOT, "%,d", (long) Math.floor(v));
    }

    /** Whole percent, never shows 100% unless actually done. */
    public static String pct(double progress) {
        int p = (int) Math.floor(progress * 100);
        return Math.min(p, 99) + "%";
    }

    /**
     * The house style: "Mining 58 812k/1.1M (73% to 59)" or "Farming 60 MAX +45.2M overflow".
     */
    public static String level(String name, Levels.Level l) {
        String head = name.isEmpty() ? String.valueOf(l.level()) : name + " " + l.level();
        if (l.maxed()) {
            return head + " MAX" + (l.overflow() >= 1 ? " +" + num(l.overflow()) + " overflow" : "");
        }
        return head + " " + num(l.into()) + "/" + num(l.needed()) + " (" + pct(l.progress()) + " to " + (l.level() + 1) + ")";
    }

    /** Short form for overview lines: "Mining 58 (73%)" or "Farming 60". */
    public static String levelShort(String name, Levels.Level l) {
        String head = name + " " + l.level();
        return l.maxed() ? head : head + " (" + pct(l.progress()) + ")";
    }

    /** Pet form: "[Lvl 87] Legendary Golden Dragon 3.2M/5.1M (63% to 88)". */
    public static String pet(String rarity, String name, Levels.Level l) {
        String head = "[Lvl " + l.level() + "] " + title(rarity) + " " + name;
        if (l.maxed()) return head + " MAX";
        return head + " " + num(l.into()) + "/" + num(l.needed()) + " (" + pct(l.progress()) + " to " + (l.level() + 1) + ")";
    }

    /** Level with fractional progress, for averages: 48.2 */
    public static String dec(double v) {
        return String.format(Locale.ROOT, "%.1f", Math.floor(v * 10) / 10);
    }

    /** milliseconds to m:ss */
    public static String time(double millis) {
        long s = Math.round(millis / 1000);
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /** SOME_ID_NAME or some_id -> Some Id Name */
    public static String title(String id) {
        if (id == null || id.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String w : id.toLowerCase(Locale.ROOT).split("[_ ]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Durations: "1d 4h", "2h 5m", "12m". */
    public static String duration(long millis) {
        long m = Math.max(0, millis) / 60_000;
        long d = m / 1440, h = (m % 1440) / 60, mm = m % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + mm + "m";
        return mm + "m";
    }
}
