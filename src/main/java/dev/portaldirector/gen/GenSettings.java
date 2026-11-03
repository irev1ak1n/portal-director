package dev.portaldirector.gen;

import java.util.Locale;

/** Generator options; persisted through {@link dev.portaldirector.Settings}. */
public final class GenSettings {

    public int count = 30;
    public int radius = 100;
    public int spacing = 8;
    public Placement placement = Placement.MIXED;
    public double airRatio = 0.5;
    public int heightMin = 15;
    public int heightMax = 60;
    public Orientation orientation = Orientation.RANDOM;
    public Layout layout = Layout.RANDOM;
    public FacingMode facing = FacingMode.AUTO;
    public int maxSlope = 3;
    public int minY = -64;
    public int maxY = 320;

    public GenSettings copy() {
        GenSettings c = new GenSettings();
        c.count = count;
        c.radius = radius;
        c.spacing = spacing;
        c.placement = placement;
        c.airRatio = airRatio;
        c.heightMin = heightMin;
        c.heightMax = heightMax;
        c.orientation = orientation;
        c.layout = layout;
        c.facing = facing;
        c.maxSlope = maxSlope;
        c.minY = minY;
        c.maxY = maxY;
        return c;
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
