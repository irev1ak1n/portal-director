package dev.portaldirector.gen;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/**
 * One square perimeter of portals around an open middle. Every side is the north side turned a quarter, so the sides
 * are equal and the corners never touch: each row stops short of the next one.
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
}
