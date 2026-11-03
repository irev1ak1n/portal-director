package dev.portaldirector.util;

/** Plain-language directions. North is toward negative z, east toward positive x. */
public final class Compass {

    private static final String[] NAMES = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};

    private Compass() {
    }

    public static String direction(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        int i = (int) Math.round(degrees / 45.0);
        return NAMES[Math.floorMod(i, 8)];
    }

    /** For example "18 blocks north-east". */
    public static String describe(double dx, double dz) {
        long blocks = Math.round(Math.hypot(dx, dz));
        if (blocks < 2) return "right here";
        return blocks + " blocks " + direction(dx, dz);
    }
}
