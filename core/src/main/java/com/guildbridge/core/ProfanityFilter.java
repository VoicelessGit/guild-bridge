package com.guildbridge.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stars out words at or above a severity threshold.
 *
 * <p>Word list format, one per line: {@code word severity}. Severity 1 = mild (damn, hell),
 * 2 = strong profanity, 3 = slurs and hate terms. Lines starting with # are comments.
 *
 * <p>Matching is case-insensitive, tolerates common leetspeak (sh1t, $hit), stretched letters
 * (shiiit) and a few suffixes (s, ed, ing, er, y). Whole words only, so "class" and "assassin"
 * never trip "ass".
 */
public final class ProfanityFilter {
    public static final String BUNDLED_LIST = "/guildbridge/filter-words.txt";

    private static final Map<Character, String> LEET = Map.ofEntries(
            Map.entry('a', "a4@"), Map.entry('b', "b8"), Map.entry('e', "e3"),
            Map.entry('g', "g9"), Map.entry('i', "i1!|"), Map.entry('l', "l1|"),
            Map.entry('o', "o0"), Map.entry('s', "s5$"), Map.entry('t', "t7"));
    private static final String SUFFIX = "(?:s|es|ed|er|ers|ing|y)?";

    private final List<Pattern> patterns = new ArrayList<>();

    /** @param threshold lowest severity that gets censored. 2 means "mid to high". */
    public ProfanityFilter(Reader wordList, int threshold) throws IOException {
        try (BufferedReader r = new BufferedReader(wordList)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                int severity = parts.length > 1 ? Integer.parseInt(parts[1]) : 3;
                if (severity >= threshold) patterns.add(compile(parts[0].toLowerCase()));
            }
        }
    }

    /** Loads the word list bundled in the jar. */
    public static ProfanityFilter bundled(int threshold) {
        try (InputStream in = ProfanityFilter.class.getResourceAsStream(BUNDLED_LIST)) {
            if (in == null) return new ProfanityFilter(new StringReader(""), threshold);
            return new ProfanityFilter(new InputStreamReader(in, StandardCharsets.UTF_8), threshold);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bundled filter list", e);
        }
    }

    public String censor(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Pattern p : patterns) {
            Matcher m = p.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, "*".repeat(m.group().length()));
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    private static Pattern compile(String word) {
        StringBuilder re = new StringBuilder("(?<![\\p{L}\\p{N}])");
        for (char c : word.toCharArray()) {
            re.append('[');
            for (char v : LEET.getOrDefault(c, String.valueOf(c)).toCharArray()) {
                if ("\\[]^-&".indexOf(v) >= 0) re.append('\\');
                re.append(v);
            }
            re.append("]+");
        }
        re.append(SUFFIX).append("(?![\\p{L}\\p{N}])");
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
