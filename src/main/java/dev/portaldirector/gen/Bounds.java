package dev.portaldirector.gen;

import dev.portaldirector.model.PortalSpec;

/** The square of blocks portals may use: the center's x and z, plus and minus the radius. Both ends are inside. */
public record Bounds(int minX, int maxX, int minZ, int maxZ) {

    public static Bounds around(int cx, int cz, int radius) {
        return new Bounds(cx - radius, cx + radius, cz - radius, cz + radius);
    }

    /** True when every block of the obsidian frame, outermost blocks included, is inside. */
    public boolean containsFrame(PortalSpec p) {
        int x0 = Math.min(p.cellX(0), p.cellX(PortalSpec.WIDTH - 1));
        int x1 = Math.max(p.cellX(0), p.cellX(PortalSpec.WIDTH - 1));
        int z0 = Math.min(p.cellZ(0), p.cellZ(PortalSpec.WIDTH - 1));
        int z1 = Math.max(p.cellZ(0), p.cellZ(PortalSpec.WIDTH - 1));
        return x0 >= minX && x1 <= maxX && z0 >= minZ && z1 <= maxZ;
    }

    /** The smallest radius around (cx, cz) that holds the whole frame of {@code p}. */
    public static int radiusNeeded(PortalSpec p, int cx, int cz) {
        int far = 0;
        for (int i : new int[]{0, PortalSpec.WIDTH - 1}) {
            far = Math.max(far, Math.max(Math.abs(p.cellX(i) - cx), Math.abs(p.cellZ(i) - cz)));
        }
        return far;
    }
}
