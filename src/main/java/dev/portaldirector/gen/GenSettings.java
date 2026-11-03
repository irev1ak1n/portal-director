package dev.portaldirector.gen;

import java.util.Locale;

/** Generator options; persisted through {@link dev.portaldirector.Settings}. */
public final class GenSettings {

    public int count = 30;
    public int radius = 100;
    /** Smallest gap between portal frames on the ground plane (x or z). */
    public int spacingH = 8;
    /** Smallest gap between portals that sit above each other. */
    public int spacingV = 5;
    public Placement placement = Placement.MIXED;
    public double airRatio = 0.5;
    public int heightMin = 15;
    public int heightMax = 60;
    public Orientation orientation = Orientation.RANDOM;
    public Layout layout = Layout.RANDOM;
    public FacingMode facing = FacingMode.AUTO;
    public int maxSlope = 3;
    public GenerationMode generation = GenerationMode.INSTANT;
    /** Seconds between portals when generation is delayed. */
    public double delay = 0.5;
    public RevealOrder order = RevealOrder.AUTO;
    public boolean effects = false;
    public int gridRows = 4;
    public int gridColumns = 5;
    public int squarePerSide = 5;
    public int squareLayers = 1;
    public int waveRows = 4;
    public int wavePerRow = 5;
    public int minY = -64;
    public int maxY = 320;

    public GenSettings copy() {
        GenSettings c = new GenSettings();
        c.count = count;
        c.radius = radius;
        c.spacingH = spacingH;
        c.spacingV = spacingV;
        c.placement = placement;
        c.airRatio = airRatio;
        c.heightMin = heightMin;
        c.heightMax = heightMax;
        c.orientation = orientation;
        c.layout = layout;
        c.facing = facing;
        c.maxSlope = maxSlope;
        c.generation = generation;
        c.delay = delay;
        c.order = order;
        c.effects = effects;
        c.gridRows = gridRows;
        c.gridColumns = gridColumns;
        c.squarePerSide = squarePerSide;
        c.squareLayers = squareLayers;
        c.waveRows = waveRows;
        c.wavePerRow = wavePerRow;
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
