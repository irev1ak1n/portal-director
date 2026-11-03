package dev.portaldirector.scene;

import dev.portaldirector.gen.SquareFacing;

import java.util.ArrayList;
import java.util.List;

/**
 * The square formation of a scene. {@code ids} lists the portals layer by layer; inside a layer they run clockwise from
 * the north-west corner: the north side first, then east, south and west, {@code perSide} portals each.
 */
public record SquareInfo(int centerX, int centerZ, int size, int perSide, int layers, int spacing, SquareFacing facing, List<Integer> ids) {

    private List<Integer> sideOfLayer(int layer, int side) {
        int from = layer * 4 * perSide + side * perSide;
        return ids.subList(Math.min(from, ids.size()), Math.min(from + perSide, ids.size()));
    }

    /** Portal ids of one side (0 north, 1 east, 2 south, 3 west), all layers together. */
    public List<Integer> side(int index) {
        List<Integer> out = new ArrayList<>();
        for (int layer = 0; layer < layers; layer++) out.addAll(sideOfLayer(layer, index));
        return out;
    }

    public List<List<Integer>> sides() {
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) out.add(side(i));
        return out;
    }

    /** All portals in the order they go around, layer by layer. */
    public List<Integer> ring() {
        return ids;
    }
}
