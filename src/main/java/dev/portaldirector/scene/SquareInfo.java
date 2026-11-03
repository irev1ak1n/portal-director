package dev.portaldirector.scene;

import dev.portaldirector.gen.SquareFacing;

import java.util.ArrayList;
import java.util.List;

/**
 * The square formation of a scene. {@code ids} lists the portals in clockwise order starting at the north-west
 * corner: first the north side, then east, south and west, {@code perSide} portals each.
 */
public record SquareInfo(int centerX, int centerZ, int size, int perSide, int spacing, SquareFacing facing, List<Integer> ids) {

    /** Portal ids of one side (0 north, 1 east, 2 south, 3 west). */
    public List<Integer> side(int index) {
        int from = index * perSide;
        return ids.subList(Math.min(from, ids.size()), Math.min(from + perSide, ids.size()));
    }

    public List<List<Integer>> sides() {
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) out.add(side(i));
        return out;
    }

    public SquareInfo withFacing(SquareFacing f) {
        return new SquareInfo(centerX, centerZ, size, perSide, spacing, f, ids);
    }
}
