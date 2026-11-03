package dev.portaldirector.model;

import org.bukkit.Axis;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/**
 * One portal: the minimum corner (x, y, z) of the 4x5 frame, the axis the frame runs along,
 * and the horizontal direction players and minecarts leave in. The portal is always vertical.
 * Floating portals hang in the air with nothing built under them.
 */
public record PortalSpec(int id, int x, int y, int z, Axis axis, BlockFace facing, boolean floating) {

    public static final int WIDTH = 4;
    public static final int HEIGHT = 5;
    /** Blocks cleared in front of / behind the portal so nothing is stuck inside terrain. */
    public static final int FRONT = 3;
    public static final int BACK = 1;

    public PortalSpec {
        if (axis != Axis.X && axis != Axis.Z) throw new IllegalArgumentException("axis must be X or Z");
        boolean ok = axis == Axis.X
                ? facing == BlockFace.NORTH || facing == BlockFace.SOUTH
                : facing == BlockFace.EAST || facing == BlockFace.WEST;
        if (!ok) throw new IllegalArgumentException("facing " + facing + " does not fit axis " + axis);
    }

    /**
     * Builds a portal from the coordinates admins type: the lower of the two bottom interior blocks
     * (smaller x for north/south portals, smaller z for east/west ones). The obsidian floor is one block below.
     */
    public static PortalSpec fromAnchor(int id, int ax, int ay, int az, BlockFace facing, boolean floating) {
        Axis axis = facing == BlockFace.NORTH || facing == BlockFace.SOUTH ? Axis.X : Axis.Z;
        return new PortalSpec(id, axis == Axis.X ? ax - 1 : ax, ay - 1, axis == Axis.Z ? az - 1 : az, axis, facing, floating);
    }

    /** A portal whose interior is centered on the block column (cx, cz), with the frame floor at {@code floorY}. */
    public static PortalSpec at(int id, int cx, int floorY, int cz, Axis axis, BlockFace facing, boolean floating) {
        return new PortalSpec(id, axis == Axis.X ? cx - 2 : cx, floorY, axis == Axis.Z ? cz - 2 : cz, axis, facing, floating);
    }

    /** The coordinates {@link #fromAnchor} takes: the lower bottom interior block. */
    public int anchorX() {
        return axis == Axis.X ? x + 1 : x;
    }

    public int anchorY() {
        return y + 1;
    }

    public int anchorZ() {
        return axis == Axis.Z ? z + 1 : z;
    }

    public PortalSpec withId(int newId) {
        return new PortalSpec(newId, x, y, z, axis, facing, floating);
    }

    public int cellX(int i) {
        return axis == Axis.X ? x + i : x;
    }

    public int cellZ(int i) {
        return axis == Axis.Z ? z + i : z;
    }

    public static boolean isFrameCell(int i, int j) {
        return i == 0 || i == WIDTH - 1 || j == 0 || j == HEIGHT - 1;
    }

    /** Frame cells as {x, y, z}. */
    public List<int[]> frameBlocks() {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < WIDTH; i++) {
            for (int j = 0; j < HEIGHT; j++) {
                if (isFrameCell(i, j)) out.add(new int[]{cellX(i), y + j, cellZ(i)});
            }
        }
        return out;
    }

    /** The 2x3 portal interior as {x, y, z}. */
    public List<int[]> interiorBlocks() {
        List<int[]> out = new ArrayList<>();
        for (int i = 1; i < WIDTH - 1; i++) {
            for (int j = 1; j < HEIGHT - 1; j++) out.add(new int[]{cellX(i), y + j, cellZ(i)});
        }
        return out;
    }

    public int stepX() {
        return facing.getModX();
    }

    public int stepZ() {
        return facing.getModZ();
    }

    /** Cells walked through when leaving the portal: the cleared corridor, front and back, as {x, y, z}. */
    public List<int[]> corridorBlocks() {
        List<int[]> out = new ArrayList<>();
        for (int d = -BACK; d <= FRONT; d++) {
            if (d == 0) continue;
            for (int i = 1; i < WIDTH - 1; i++) {
                for (int j = 1; j < HEIGHT - 1; j++) {
                    out.add(new int[]{cellX(i) + stepX() * d, y + j, cellZ(i) + stepZ() * d});
                }
            }
        }
        return out;
    }

    /** Horizontal centre of the portal plane, x. */
    public double centerX() {
        return axis == Axis.X ? x + 2.0 : x + 0.5;
    }

    public double centerZ() {
        return axis == Axis.Z ? z + 2.0 : z + 0.5;
    }

    /** Floor level inside the portal. */
    public double standY() {
        return y + 1;
    }

    public float yaw() {
        return switch (facing) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            default -> -90f;
        };
    }

    /** Bounding box including the walk-through corridor: {minX, minY, minZ, maxX, maxY, maxZ}, inclusive. */
    public int[] box() {
        int minX = axis == Axis.X ? x : x - (stepX() < 0 ? FRONT : BACK);
        int maxX = axis == Axis.X ? x + WIDTH - 1 : x + (stepX() > 0 ? FRONT : BACK);
        int minZ = axis == Axis.Z ? z : z - (stepZ() < 0 ? FRONT : BACK);
        int maxZ = axis == Axis.Z ? z + WIDTH - 1 : z + (stepZ() > 0 ? FRONT : BACK);
        return new int[]{minX, y, minZ, maxX, y + HEIGHT - 1, maxZ};
    }

    /** True when the gap between the two boxes is at least {@code gap} empty blocks on some axis. */
    public boolean separatedFrom(PortalSpec other, int gap) {
        int[] a = box();
        int[] b = other.box();
        for (int k = 0; k < 3; k++) {
            if (a[k + 3] + gap < b[k] || b[k + 3] + gap < a[k]) return true;
        }
        return false;
    }

    /**
     * True when the two portals, with their walkways, are far enough apart: at least {@code gapH} empty blocks
     * between them along x or along z, or at least {@code gapV} blocks between them vertically.
     */
    public boolean separatedFrom(PortalSpec other, int gapH, int gapV) {
        int[] a = box();
        int[] b = other.box();
        boolean horizontal = a[3] + gapH < b[0] || b[3] + gapH < a[0] || a[5] + gapH < b[2] || b[5] + gapH < a[2];
        boolean vertical = a[4] + gapV < b[1] || b[4] + gapV < a[1];
        return horizontal || vertical;
    }

    public boolean contains(int bx, int by, int bz) {
        int[] b = box();
        return bx >= b[0] && bx <= b[3] && by >= b[1] && by <= b[4] && bz >= b[2] && bz <= b[5];
    }
}
