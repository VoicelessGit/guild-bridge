package com.guildbridge.core;

import java.util.regex.Pattern;

final class TextUtil {
    private static final Pattern FORMAT_CODE = Pattern.compile("§.?");

    private TextUtil() {}

    /** Removes Minecraft section-sign formatting codes like §a or §l. */
    static String stripFormatting(String s) {
        return FORMAT_CODE.matcher(s).replaceAll("");
    }
}
