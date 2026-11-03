package dev.portaldirector.gen;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays portals out as the four sides of a square around an open middle. Every side is the north side turned
 * a quarter, so the sides are equal and the corners never touch: each row stops short of the next one.
 */
public final class SquarePlanner {

    /** Smallest outer size that still leaves an open middle. */
    public static final int MIN_SIZE = 18;
    public static final String[] SIDE_NAMES = {"north", "east", "south", "west"};

    private SquarePlanner() {
    }

    /** Outer size of a square with {@code perSide} portals per side and {@code gap} empty blocks between them. */
    public static int sizeFor(int perSide, int gap) {
        return perSide * (4 + gap) + 4;
    }

    /** Most portals per side that fit in an outer size. */
    public static int perSideFor(int size, int gap) {
        return Math.max(0, (size - 4) / (4 + gap));
    }

    /** Portal specs in clockwise order from the north-west corner; y is not set yet. */
    public static List<PortalSpec> layout(int firstId, int x0, int z0, int perSide, int gap, SquareFacing facing) {
        int size = sizeFor(perSide, gap);
        int pitch = 4 + gap;
        boolean in = facing == SquareFacing.INWARD;
        List<PortalSpec> out = new ArrayList<>();
        int id = firstId;
        for (int i = 0; i < perSide; i++) {
            out.add(new PortalSpec(id++, x0 + i * pitch, 0, z0, Axis.X, in ? BlockFace.SOUTH : BlockFace.NORTH, false));
        }
        for (int i = 0; i < perSide; i++) {
            out.add(new PortalSpec(id++, x0 + size - 1, 0, z0 + i * pitch, Axis.Z, in ? BlockFace.WEST : BlockFace.EAST, false));
        }
        for (int i = 0; i < perSide; i++) {
            out.add(new PortalSpec(id++, x0 + size - 4 - i * pitch, 0, z0 + size - 1, Axis.X, in ? BlockFace.NORTH : BlockFace.SOUTH, false));
        }
        for (int i = 0; i < perSide; i++) {
            out.add(new PortalSpec(id++, x0, 0, z0 + size - 4 - i * pitch, Axis.Z, in ? BlockFace.EAST : BlockFace.WEST, false));
        }
        return out;
    }

    /** Portals that can be built, and a reason for each position that cannot. */
    public record Fit(List<PortalSpec> usable, List<String> problems) {
    }

    /**
     * Sets heights and checks every position against the terrain and the portals already there.
     * With {@code elevation} (blocks above the highest ground under the square) all portals float at one level.
     */
    public static Fit fit(Terrain terrain, List<PortalSpec> layout, int perSide, int maxSlope, Integer elevation,
                          List<PortalSpec> existing) {
        List<PortalSpec> usable = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int level = Integer.MIN_VALUE;
        if (elevation != null) {
            int top = terrain.minY();
            for (PortalSpec p : layout) {
                int[] b = p.box();
                for (int x = b[0]; x <= b[3]; x++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        if (terrain.isReady(x, z)) top = Math.max(top, terrain.topY(x, z));
                    }
                }
            }
            level = top + elevation;
        }
        for (int i = 0; i < layout.size(); i++) {
            PortalSpec p = layout.get(i);
            String where = SIDE_NAMES[i / perSide] + " side, portal " + (i % perSide + 1);
            int[] b = p.box();
            boolean ready = true;
            for (int x = b[0]; x <= b[3] && ready; x++) {
                for (int z = b[2]; z <= b[5] && ready; z++) ready = terrain.isReady(x, z);
            }
            if (!ready) {
                problems.add(where + ": chunk not loaded");
                continue;
            }
            int y = elevation != null ? level : PortalPlanner.groundY(terrain, p, maxSlope);
            if (y == Integer.MIN_VALUE) {
                problems.add(where + ": water, steep ground or something built");
                continue;
            }
            PortalSpec placed = new PortalSpec(p.id(), p.x(), y, p.z(), p.axis(), p.facing(), elevation != null);
            if (placed.y() < terrain.minY() + 1 || placed.y() + PortalSpec.HEIGHT >= terrain.maxY() - 1) {
                problems.add(where + ": outside the build height");
                continue;
            }
            if (elevation == null && !PortalPlanner.openAbove(terrain, placed)) {
                problems.add(where + ": under a tree or a ledge");
                continue;
            }
            if (!PortalPlanner.volumeFree(terrain, placed, elevation != null)) {
                problems.add(where + ": " + (elevation != null ? "blocks in the way" : "would replace something built"));
                continue;
            }
            PortalSpec clash = null;
            for (PortalSpec other : existing) {
                if (!placed.separatedFrom(other, 1)) {
                    clash = other;
                    break;
                }
            }
            if (clash != null) {
                problems.add(where + ": too close to portal " + clash.id());
                continue;
            }
            usable.add(placed);
        }
        return new Fit(usable, problems);
    }
}
