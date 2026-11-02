package dev.portaldirector.util;

import java.util.Locale;

/** Parses times typed by admins: plain numbers are seconds, "20t" is ticks, "1.5s" is seconds. */
public final class Durations {

    private Durations() {
    }

    public static int parseTicks(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.endsWith("t")) return nonNegative(Math.round(Double.parseDouble(t.substring(0, t.length() - 1))), text);
            if (t.endsWith("s")) t = t.substring(0, t.length() - 1);
            else if (t.endsWith("m")) return nonNegative(Math.round(Double.parseDouble(t.substring(0, t.length() - 1)) * 1200), text);
            return nonNegative(Math.round(Double.parseDouble(t) * 20), text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a time. Use seconds (10), ticks (20t) or minutes (2m).");
        }
    }

    private static int nonNegative(long v, String text) {
        if (v < 0 || v > 72000 * 24) throw new IllegalArgumentException("'" + text + "' is out of range.");
        return (int) v;
    }

    public static String format(int ticks) {
        if (ticks % 20 == 0) return (ticks / 20) + "s";
        return String.format(Locale.ROOT, "%.2fs", ticks / 20.0);
    }
}
