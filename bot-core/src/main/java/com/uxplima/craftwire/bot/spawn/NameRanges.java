package com.uxplima.craftwire.bot.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bot names written compactly for results: Load1, Load2 … Load150 become "Load1..Load150". 150 bots then cost the
 * model a few tokens instead of 150 names.
 */
public final class NameRanges {
    private static final Pattern NUMBERED = Pattern.compile("(.*?)(\\d+)");

    private NameRanges() {}

    /** Names sorted naturally and joined into ranges, e.g. "Load1..Load3, Load7, Tester". */
    public static String compact(List<String> names) {
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(NameRanges::natural);
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size() && follows(sorted.get(j), sorted.get(j + 1))) j++;
            parts.add(j == i ? sorted.get(i) : sorted.get(i) + ".." + sorted.get(j));
            i = j + 1;
        }
        return String.join(", ", parts);
    }

    /** Prefix order (case-insensitive), then the trailing number as a number: Bot2 before Bot10. */
    public static int natural(String a, String b) {
        Matcher ma = NUMBERED.matcher(a);
        Matcher mb = NUMBERED.matcher(b);
        if (ma.matches() && mb.matches()) {
            int c = ma.group(1).toLowerCase(Locale.ROOT).compareTo(mb.group(1).toLowerCase(Locale.ROOT));
            if (c != 0) return c;
            int n = Long.compare(Long.parseLong(ma.group(2)), Long.parseLong(mb.group(2)));
            if (n != 0) return n;
        }
        return a.compareToIgnoreCase(b);
    }

    /** Whether b is a's successor: same prefix, number one higher, no leading-zero difference. */
    private static boolean follows(String a, String b) {
        Matcher ma = NUMBERED.matcher(a);
        Matcher mb = NUMBERED.matcher(b);
        if (!ma.matches() || !mb.matches() || !ma.group(1).equals(mb.group(1))) return false;
        if (mb.group(2).startsWith("0") || ma.group(2).startsWith("0")) return false;
        return Long.parseLong(mb.group(2)) == Long.parseLong(ma.group(2)) + 1;
    }
}
