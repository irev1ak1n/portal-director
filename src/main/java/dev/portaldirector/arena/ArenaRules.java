package dev.portaldirector.arena;

import dev.portaldirector.db.Take;
import dev.portaldirector.model.PortalSpec;

import java.util.List;

/** The checks an arena must pass before it is saved or restored. No server needed, so they are easy to test. */
public final class ArenaRules {

    private ArenaRules() {
    }

    /** Turns two corners into a box with the smaller numbers first. */
    public static Take.Arena box(String world, int x1, int y1, int z1, int x2, int y2, int z2, String snapshot) {
        return new Take.Arena(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2), snapshot);
    }

    /** Why this box cannot be used, or null when it is fine. */
    public static String problem(Take.Arena a, int minWorldY, int maxWorldY, long maxBlocks) {
        if (a.minY() < minWorldY || a.maxY() >= maxWorldY) {
            return "the box reaches outside the world height (" + minWorldY + " to " + (maxWorldY - 1) + ")";
        }

        if (a.volume() > maxBlocks) {
            return "the box holds " + a.volume() + " blocks and the limit is " + maxBlocks + " (arena.max-blocks in config.yml). A whole world is never restored";
        }

        return null;
    }

    /** True when the box and the portal (with its corridor) share at least one block. */
    public static boolean overlaps(Take.Arena a, PortalSpec p) {
        int[] b = p.box();

        return a.minX() <= b[3] && a.maxX() >= b[0] && a.minY() <= b[4] && a.maxY() >= b[1] && a.minZ() <= b[5] && a.maxZ() >= b[2];
    }

    public static List<PortalSpec> overlapping(Take.Arena a, List<PortalSpec> portals) {
        return portals.stream().filter(p -> overlaps(a, p)).toList();
    }

    /** The file name used for a take's snapshot. Names are already limited to safe characters. */
    public static String snapshotName(String take) {
        return take.toLowerCase(java.util.Locale.ROOT) + ".schem";
    }
}
