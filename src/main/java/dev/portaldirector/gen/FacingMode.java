package dev.portaldirector.gen;

import java.util.List;

/**
 * Which way portals face, meaning the direction players and minecarts leave in. Inward and outward belong to the
 * square layout; every other layout takes a compass direction or random.
 */
public enum FacingMode {
    EAST, INWARD, NORTH, OUTWARD, RANDOM, SOUTH, WEST;

    public static final List<FacingMode> COMPASS = List.of(NORTH, SOUTH, EAST, WEST);

    /** The values {@code layout} understands, in the order they are suggested. */
    public static List<FacingMode> allowedFor(Layout layout) {
        return layout == Layout.SQUARE ? List.of(INWARD, OUTWARD) : List.of(NORTH, SOUTH, EAST, WEST, RANDOM);
    }

    public static boolean allowed(FacingMode mode, Layout layout) {
        return allowedFor(layout).contains(mode);
    }

    /** What a layout falls back to when the current facing does not suit it. */
    public static FacingMode defaultFor(Layout layout) {
        return layout == Layout.SQUARE ? INWARD : RANDOM;
    }

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
