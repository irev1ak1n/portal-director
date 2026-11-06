package dev.portaldirector.sequence;

import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.nether.NetherGrid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Waiting places beside a Nether portal for a group of players. A place is two open blocks above natural solid ground, with
 * no lava or fire around it, outside the portal and its frame, and a block of its own. They are taken closest to the
 * requested distance from the portal first, preferring the side the portal faces and keeping two blocks between players
 * while there is room.
 */
public final class ArrivalSpots {

    /** A block position players stand on (x, y, z is the block their feet are in). */
    public record Spot(int x, int y, int z) {
    }

    private ArrivalSpots() {
    }

    /** How far from the portal a place is looked for, beyond the requested distance. */
    public static final int REACH = 12;

    public static List<Spot> find(NetherGrid g, PortalSpec portal, int distance, int need, List<PortalSpec> others) {
        double cx = portal.centerX();
        double cz = portal.centerZ();
        int[] box = portal.box();
        List<Spot> all = new ArrayList<>();
        int reach = distance + REACH;
        int baseX = (int) Math.floor(cx);
        int baseZ = (int) Math.floor(cz);
        for (int x = baseX - reach; x <= baseX + reach; x++) {
            for (int z = baseZ - reach; z <= baseZ + reach; z++) {
                if (!g.isReady(x, z)) continue;
                if (x >= box[0] - 1 && x <= box[3] + 1 && z >= box[2] - 1 && z <= box[5] + 1) continue;
                if (nearOther(others, x, z)) continue;
                int y = floorFor(g, portal, x, z);
                if (y != Integer.MIN_VALUE) all.add(new Spot(x, y, z));
            }
        }
        all.sort(Comparator.comparingDouble((Spot s) -> rank(portal, cx, cz, distance, s)).thenComparingInt(Spot::x).thenComparingInt(Spot::z));
        List<Spot> out = new ArrayList<>();
        for (Spot s : all) {
            if (out.size() >= need) break;
            if (apart(out, s, 2)) out.add(s);
        }
        for (Spot s : all) {
            if (out.size() >= need) break;
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    private static double rank(PortalSpec p, double cx, double cz, int distance, Spot s) {
        double dx = s.x() + 0.5 - cx;
        double dz = s.z() + 0.5 - cz;
        double d = Math.hypot(dx, dz);
        double ahead = dx * p.stepX() + dz * p.stepZ();
        return Math.abs(d - distance) + (ahead < 0 ? 3.0 : 0.0);
    }

    private static boolean apart(List<Spot> taken, Spot s, int gap) {
        for (Spot t : taken) {
            if (Math.abs(t.x() - s.x()) < gap && Math.abs(t.z() - s.z()) < gap) return false;
        }
        return true;
    }

    private static boolean nearOther(List<PortalSpec> others, int x, int z) {
        for (PortalSpec o : others) {
            int[] b = o.box();
            if (x >= b[0] - 2 && x <= b[3] + 2 && z >= b[2] - 2 && z <= b[5] + 2) return true;
        }
        return false;
    }

    /** The y of the feet for this column, or MIN_VALUE when nobody can safely stand in it. */
    private static int floorFor(NetherGrid g, PortalSpec portal, int x, int z) {
        int best = Integer.MIN_VALUE;
        int bestD = Integer.MAX_VALUE;
        for (int y = portal.y() + 6; y >= portal.y() - 6; y--) {
            if (y - 1 < g.minY() + 1 || y + 3 >= g.maxY()) continue;
            if (g.cell(x, y - 1, z) != NetherGrid.Cell.SOLID) continue;
            if (!open(g, x, y, z) || !open(g, x, y + 1, z) || !open(g, x, y + 2, z)) continue;
            if (g.isHazard(x, y, z) || g.isHazard(x, y + 1, z)) continue;
            if (liquidNear(g, x, y, z)) continue;
            int d = Math.abs(y - portal.y());
            if (d < bestD) {
                best = y;
                bestD = d;
            }
        }
        return best;
    }

    private static boolean open(NetherGrid g, int x, int y, int z) {
        return g.cell(x, y, z) == NetherGrid.Cell.AIR;
    }

    private static boolean liquidNear(NetherGrid g, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    if (g.cell(x + dx, y + dy, z + dz) == NetherGrid.Cell.LIQUID) return true;
                }
            }
        }
        return false;
    }

    /** The yaw that makes someone standing at (x, z) look at (tx, tz). */
    public static float yawToward(double x, double z, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
    }
}
