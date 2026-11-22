package com.guildbridge.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Makes Discord emoji readable in Minecraft chat. An emoji that is a single old Unicode symbol
 * (like ❤ ☀ ✔ ★) stays as that symbol, which the game can draw; everything else becomes its
 * Discord-style name, so 💀 reads :skull:. Names come from emoji-datasource (MIT), bundled as
 * guildbridge/emoji.tsv.
 */
public final class EmojiText {
    private static final class Node {
        final Map<Integer, Node> next = new HashMap<>();
        String name;
    }

    private static final Node ROOT = load();
    private static final int VS16 = 0xFE0F;

    private EmojiText() {}

    public static String toGame(String text) {
        if (text == null || text.isEmpty()) return text;
        int[] cps = text.codePoints().filter(c -> c != VS16 && !isSkinTone(c)).toArray();
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < cps.length) {
            // Longest emoji sequence starting here.
            Node n = ROOT;
            String name = null;
            int end = i;
            for (int j = i; j < cps.length; j++) {
                n = n.next.get(cps[j]);
                if (n == null) break;
                if (n.name != null) {
                    name = n.name;
                    end = j + 1;
                }
            }
            if (name == null) {
                out.appendCodePoint(cps[i]);
                i++;
                continue;
            }
            if (end - i == 1 && cps[i] <= 0xFFFF) {
                out.appendCodePoint(cps[i]); // an old Unicode symbol the game can show as is
            } else {
                boolean flag = end - i == 2 && isRegional(cps[i]) && isRegional(cps[i + 1]);
                out.append(':').append(flag ? "flag_" + name : name).append(':');
            }
            i = end;
        }
        return out.toString();
    }

    private static boolean isSkinTone(int c) {
        return c >= 0x1F3FB && c <= 0x1F3FF;
    }

    private static boolean isRegional(int c) {
        return c >= 0x1F1E6 && c <= 0x1F1FF;
    }

    private static Node load() {
        Node root = new Node();
        try (InputStream in = EmojiText.class.getResourceAsStream("/guildbridge/emoji.tsv")) {
            if (in == null) return root;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab < 0) continue;
                Node n = root;
                for (String hex : line.substring(0, tab).split("-")) {
                    int cp = Integer.parseInt(hex, 16);
                    if (isSkinTone(cp)) continue;
                    n = n.next.computeIfAbsent(cp, k -> new Node());
                }
                if (n.name == null) n.name = line.substring(tab + 1).strip();
            }
        } catch (Exception e) {
            // without the table, emoji pass through unchanged
        }
        return root;
    }
}
