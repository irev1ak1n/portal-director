package dev.portaldirector.sequence;

import java.util.List;
import java.util.Locale;

/** One setting of a sequence: its name, kind, default, allowed values and what it does. */
public final class SeqOption {

    public enum Type { WHOLE, DECIMAL, TEXT, CHOICE }

    private final String key;
    private final Type type;
    private final String def;
    private final double min;
    private final double max;
    private final List<String> choices;
    private final String hint;

    private SeqOption(String key, Type type, String def, double min, double max, List<String> choices, String hint) {
        this.key = key;
        this.type = type;
        this.def = def;
        this.min = min;
        this.max = max;
        this.choices = choices;
        this.hint = hint;
    }

    public static SeqOption whole(String key, int def, int min, int max, String hint) {
        return new SeqOption(key, Type.WHOLE, String.valueOf(def), min, max, List.of(), hint);
    }

    public static SeqOption decimal(String key, double def, double min, double max, String hint) {
        return new SeqOption(key, Type.DECIMAL, trim(def), min, max, List.of(), hint);
    }

    /** Free text, such as a group name. An empty default means "not chosen". */
    public static SeqOption text(String key, String def, String hint) {
        return new SeqOption(key, Type.TEXT, def, 0, 0, List.of(), hint);
    }

    public static SeqOption choice(String key, String def, List<String> choices, String hint) {
        return new SeqOption(key, Type.CHOICE, def, 0, 0, choices, hint);
    }

    public String key() {
        return key;
    }

    public Type type() {
        return type;
    }

    public String def() {
        return def;
    }

    public String hint() {
        return hint;
    }

    public List<String> choices() {
        return choices;
    }

    /** Values worth suggesting while someone types the command. */
    public List<String> suggestions() {
        return switch (type) {
            case CHOICE -> choices;
            case WHOLE -> List.of(def, trim(min), trim(max)).stream().distinct().toList();
            case DECIMAL -> List.of(def, trim(min), trim(max)).stream().distinct().toList();
            case TEXT -> List.of();
        };
    }

    /** The value as it is stored, or an IllegalArgumentException that says what is allowed. */
    public String normalize(String raw) {
        String v = raw.trim();
        switch (type) {
            case WHOLE -> {
                int n;
                try {
                    n = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(key + " takes a whole number from " + trim(min) + " to " + trim(max) + ", not '" + raw + "'.");
                }
                if (n < min || n > max) throw new IllegalArgumentException(key + " must be between " + trim(min) + " and " + trim(max) + ".");
                return String.valueOf(n);
            }
            case DECIMAL -> {
                double d;
                try {
                    d = Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(key + " takes a number of seconds from " + trim(min) + " to " + trim(max) + ", not '" + raw + "'.");
                }
                if (Double.isNaN(d) || d < min || d > max) throw new IllegalArgumentException(key + " must be between " + trim(min) + " and " + trim(max) + ".");
                return trim(d);
            }
            case CHOICE -> {
                String c = v.toLowerCase(Locale.ROOT);
                if (!choices.contains(c)) throw new IllegalArgumentException(key + " is one of: " + String.join(", ", choices) + ".");
                return c;
            }
            default -> {
                return v;
            }
        }
    }

    static String trim(double v) {
        return v == Math.floor(v) && Math.abs(v) < 1e9 ? String.valueOf((long) v) : String.valueOf(Math.round(v * 1000.0) / 1000.0);
    }
}
