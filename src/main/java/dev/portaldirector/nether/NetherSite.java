package dev.portaldirector.nether;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a place in the Nether for the partner of an Overworld portal. The search starts at the spot vanilla would aim for
 * (the Overworld coordinates divided by 8) and spirals outward until a whole frame fits: open or natural space, solid
 * natural ground under every frame column, no lava next to it, nothing built by a player or a structure within two
 * blocks, no other portal frame within a few blocks. Nothing is chosen that would replace anything but natural blocks.
 */
public final class NetherSite {

    /** The portals of other pairs must stay at least this many empty blocks away, so frames never touch or merge. */
    public static final int GAP = 3;
    public static final int LOWEST_FLOOR = 34;
    public static final int HIGHEST_TOP = 118;

    private NetherSite() {
    }

    /**
     * @param centerX    x of the block column the portal should be centered on
     * @param centerZ    z of the block column the portal should be centered on
     * @param preferredY floor height to prefer when a column has several
     * @param taken      portals already placed in this Nether (their frames and walkways are avoided)
     * @param radius     how far from the aimed spot to look
     * @return the portal, or null when nothing safe exists within the radius
     */
    public static PortalSpec find(NetherGrid g, int id, int centerX, int centerZ, int preferredY, Axis axis, BlockFace facing,
                                  List<PortalSpec> taken, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int[] c : ring(r)) {
                PortalSpec p = at(g, id, centerX + c[0], centerZ + c[1], preferredY, axis, facing, taken);
                if (p != null) return p;
            }
        }
        return null;
    }

    /** Offsets on the square ring at distance r, in a fixed order. */
    static List<int[]> ring(int r) {
        List<int[]> out = new ArrayList<>();
        if (r == 0) {
            out.add(new int[]{0, 0});
            return out;
        }
        for (int i = -r; i <= r; i++) {
            out.add(new int[]{i, -r});
            out.add(new int[]{i, r});
        }
        for (int i = -r + 1; i <= r - 1; i++) {
            out.add(new int[]{-r, i});
            out.add(new int[]{r, i});
        }
        return out;
    }

    private static PortalSpec at(NetherGrid g, int id, int cx, int cz, int preferredY, Axis axis, BlockFace facing, List<PortalSpec> taken) {
        PortalSpec probe = PortalSpec.at(id, cx, 0, cz, axis, facing, true);
        int[] box = probe.box();
        for (int x = box[0] - 2; x <= box[3] + 2; x++) {
            for (int z = box[2] - 2; z <= box[5] + 2; z++) {
                if (!g.isReady(x, z)) return null;
            }
        }
        PortalSpec best = null;
        int bestDistance = Integer.MAX_VALUE;
        int lowest = Math.max(LOWEST_FLOOR, g.minY() + 4);
        int highest = Math.min(HIGHEST_TOP, g.maxY() - 1) - (PortalSpec.HEIGHT - 1);
        int sx = probe.cellX(1);
        int sz = probe.cellZ(1);
        for (int y = lowest; y <= highest; y++) {
            if (g.cell(sx, y - 1, sz) != NetherGrid.Cell.SOLID) continue;
            PortalSpec spec = new PortalSpec(id, probe.x(), y, probe.z(), axis, facing, true);
            if (!fits(g, spec, taken)) continue;
            int d = Math.abs(y - preferredY);
            if (d < bestDistance) {
                best = spec;
                bestDistance = d;
            }
        }
        return best;
    }

    private static boolean fits(NetherGrid g, PortalSpec p, List<PortalSpec> taken) {
        // Partners keep their distance across the ground too, so they are never stacked in one column.
        for (PortalSpec other : taken) {
            int[] a = p.box();
            int[] b = other.box();
            boolean apart = a[3] + GAP < b[0] || b[3] + GAP < a[0] || a[5] + GAP < b[2] || b[5] + GAP < a[2];
            if (!apart) return false;
        }
        for (int i = 0; i < PortalSpec.WIDTH; i++) {
            if (g.cell(p.cellX(i), p.y() - 1, p.cellZ(i)) != NetherGrid.Cell.SOLID) return false;
        }
        if (!openInFront(g, p)) return false;
        int[] box = p.box();
        for (int x = box[0] - 2; x <= box[3] + 2; x++) {
            for (int y = box[1] - 2; y <= box[4] + 2; y++) {
                for (int z = box[2] - 2; z <= box[5] + 2; z++) {
                    NetherGrid.Cell c = g.cell(x, y, z);
                    boolean inside = x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
                    if (c == NetherGrid.Cell.BLOCKED) return false;
                    if (c == NetherGrid.Cell.LIQUID && (inside || nearLiquidRule(x, y, z, box))) return false;
                }
            }
        }
        return true;
    }

    /**
     * The walkway in front of the portal has to be mostly open already: the first layer completely, the rest at least
     * 80%. A portal is never sunk into solid rock, where arriving players would find themselves walled in.
     */
    private static boolean openInFront(NetherGrid g, PortalSpec p) {
        int front = 0;
        int open = 0;
        for (int[] c : p.corridorBlocks()) {
            int ahead = (c[0] - p.cellX(1)) * p.stepX() + (c[2] - p.cellZ(1)) * p.stepZ();
            if (ahead <= 0) continue;
            NetherGrid.Cell cell = g.cell(c[0], c[1], c[2]);
            boolean isOpen = cell == NetherGrid.Cell.AIR || cell == NetherGrid.Cell.PLANT;
            if (ahead == 1 && !isOpen) return false;
            front++;
            if (isOpen) open++;
        }
        return open * 10 >= front * 8;
    }

    /** Lava anywhere within one block of the box, or in the shell below it, makes the spot unsafe. */
    private static boolean nearLiquidRule(int x, int y, int z, int[] box) {
        return x >= box[0] - 1 && x <= box[3] + 1 && y >= box[1] - 2 && y <= box[4] + 1 && z >= box[2] - 1 && z <= box[5] + 1;
    }
}
