package dev.portaldirector.gen;

import dev.portaldirector.model.PortalSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Portals in a grid of 64 by 64 block cells, so a question about one spot only looks at the few portals near it. The answers are
 * exactly the ones a loop over every portal would give.
 */
public final class PortalIndex {

    private static final int CELL_SHIFT = 6;

    private final Map<Long, List<PortalSpec>> cells = new HashMap<>();
    private int size;

    public PortalIndex(Collection<PortalSpec> portals) {
        for (PortalSpec p : portals) add(p);
    }

    private static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    public int size() {
        return size;
    }

    public void add(PortalSpec portal) {
        int[] box = portal.box();

        for (int cx = box[0] >> CELL_SHIFT; cx <= box[3] >> CELL_SHIFT; cx++) {
            for (int cz = box[2] >> CELL_SHIFT; cz <= box[5] >> CELL_SHIFT; cz++) {
                cells.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(portal);
            }
        }

        size++;
    }

    /** True when the candidate keeps the horizontal gap or the vertical gap from every portal in the index. */
    public boolean isClear(PortalSpec candidate, int gapH, int gapV) {
        return firstClash(candidate, gapH, gapV) == null;
    }

    public PortalSpec firstClash(PortalSpec candidate, int gap) {
        return firstClash(candidate, gap, gap);
    }

    public PortalSpec firstClash(PortalSpec candidate, int gapH, int gapV) {
        int[] box = candidate.box();
        int reach = Math.max(gapH, gapV) + 1;

        for (int cx = (box[0] - reach) >> CELL_SHIFT; cx <= (box[3] + reach) >> CELL_SHIFT; cx++) {
            for (int cz = (box[2] - reach) >> CELL_SHIFT; cz <= (box[5] + reach) >> CELL_SHIFT; cz++) {
                List<PortalSpec> here = cells.get(key(cx, cz));

                if (here == null) continue;

                for (PortalSpec other : here) {
                    if (!candidate.separatedFrom(other, gapH, gapV)) return other;
                }
            }
        }

        return null;
    }

    /** True when the block is inside the box of any indexed portal. */
    public boolean contains(int x, int y, int z) {
        List<PortalSpec> here = cells.get(key(x >> CELL_SHIFT, z >> CELL_SHIFT));

        if (here == null) return false;

        for (PortalSpec p : here) {
            if (p.contains(x, y, z)) return true;
        }

        return false;
    }
}
