package dev.portaldirector.gen;

import dev.portaldirector.model.PortalSpec;

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
    /** Floating portals start at least this many blocks above the terrain. */
    public int altitudeMin = 15;
    /** Floating portals start at most this many blocks above the terrain. */
    public int altitudeMax = 60;
    public Layout layout = Layout.RANDOM;
    public FacingMode facing = FacingMode.RANDOM;
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
    /** Lowest world y a portal frame may use. */
    public int worldYMin = -64;
    /** Highest world y a portal frame may use. */
    public int worldYMax = 320;

    /** The margin added to every radius, in percent. It is a fixed rule, not a setting. */
    public static final int RADIUS_MARGIN_PERCENT = 5;

    /** The radius portals are really held to: the radius plus the automatic margin, rounded down. */
    public int effectiveRadius() {
        return (int) Math.floor(radius * (100 + RADIUS_MARGIN_PERCENT) / 100.0 + 1e-9);
    }

    /** Why these settings cannot make portals, or null when they are fine. */
    public String problem() {
        if (altitudeMin > altitudeMax) {
            return "altitude-min (" + altitudeMin + ") is above altitude-max (" + altitudeMax + "). Lower altitude-min or raise altitude-max.";
        }
        if (worldYMin > worldYMax) {
            return "world-y-min (" + worldYMin + ") is above world-y-max (" + worldYMax + "). Lower world-y-min or raise world-y-max.";
        }
        if (worldYMax - worldYMin + 1 < PortalSpec.HEIGHT) {
            return "world-y-min " + worldYMin + " to world-y-max " + worldYMax + " is only " + (worldYMax - worldYMin + 1)
                    + " blocks tall, and a portal frame needs " + PortalSpec.HEIGHT + ". Widen the range.";
        }
        if (!FacingMode.allowed(facing, layout)) return facingProblem();
        return null;
    }

    /** Explains a facing the current layout does not take. */
    public String facingProblem() {
        String layoutName = layout.name().toLowerCase(Locale.ROOT);
        if (layout == Layout.SQUARE) {
            return "The square layout faces inward or outward, not " + facing.label() + ". Use /pd set facing inward or /pd set facing outward.";
        }
        return facing.label() + " only works with the square layout. The " + layoutName + " layout faces north, south, east, west or random: /pd set facing north.";
    }

    public GenSettings copy() {
        GenSettings c = new GenSettings();
        c.count = count;
        c.radius = radius;
        c.spacingH = spacingH;
        c.spacingV = spacingV;
        c.placement = placement;
        c.altitudeMin = altitudeMin;
        c.altitudeMax = altitudeMax;
        c.layout = layout;
        c.facing = facing;
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
        c.worldYMin = worldYMin;
        c.worldYMax = worldYMax;
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
