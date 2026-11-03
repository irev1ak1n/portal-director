package dev.portaldirector;

import dev.portaldirector.gen.SquareFacing;
import dev.portaldirector.gen.SquarePlanner;
import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SquareLayoutTest {

    private static List<PortalSpec> layout(int perSide, int gap, SquareFacing facing) {
        return SquarePlanner.layout(1, 100, 200, perSide, gap, facing);
    }

    @Test
    void sidesRunAlongTheRightAxesAndFaceTheRightWay() {
        BlockFace[] inward = {BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST};
        BlockFace[] outward = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        List<PortalSpec> in = layout(4, 2, SquareFacing.INWARD);
        List<PortalSpec> out = layout(4, 2, SquareFacing.OUTWARD);
        for (int i = 0; i < 16; i++) {
            assertEquals(i / 4 % 2 == 0 ? Axis.X : Axis.Z, in.get(i).axis());
            assertEquals(inward[i / 4], in.get(i).facing());
            assertEquals(outward[i / 4], out.get(i).facing());
        }
    }

    @Test
    void sidesLieOnTheFourEdgesOfTheSquare() {
        int size = SquarePlanner.sizeFor(5, 2);
        List<PortalSpec> l = layout(5, 2, SquareFacing.INWARD);
        for (int k = 0; k < 5; k++) {
            assertEquals(200, l.get(k).z());
            assertEquals(100 + size - 1, l.get(5 + k).x());
            assertEquals(200 + size - 1, l.get(10 + k).z());
            assertEquals(100, l.get(15 + k).x());
        }
    }

    @Test
    void neighbouringPortalsAreExactlyTheGapApart() {
        for (int gap : new int[]{1, 3, 8}) {
            List<PortalSpec> l = layout(5, gap, SquareFacing.INWARD);
            for (int side = 0; side < 4; side++) {
                for (int k = 1; k < 5; k++) {
                    PortalSpec a = l.get(side * 5 + k - 1);
                    PortalSpec b = l.get(side * 5 + k);
                    int d = side % 2 == 0 ? Math.abs(b.x() - a.x()) : Math.abs(b.z() - a.z());
                    assertEquals(4 + gap, d);
                }
            }
        }
    }

    @Test
    void noFramesOrWalkwaysOverlapEvenAtTheCorners() {
        for (SquareFacing f : SquareFacing.values()) {
            for (int gap = 1; gap <= 8; gap++) {
                for (int n = 2; n <= 12; n++) {
                    List<PortalSpec> l = layout(n, gap, f);
                    for (int i = 0; i < l.size(); i++) {
                        for (int j = i + 1; j < l.size(); j++) {
                            assertTrue(l.get(i).separatedFrom(l.get(j), gap), "overlap " + f + " gap " + gap + " n " + n + ": " + i + " vs " + j);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theMiddleStaysOpen() {
        List<PortalSpec> l = layout(3, 6, SquareFacing.INWARD);
        int size = SquarePlanner.sizeFor(3, 6);
        for (PortalSpec p : l) assertFalse(p.contains(100 + size / 2, 0, 200 + size / 2) && p.y() == 0);
        assertTrue(size >= SquarePlanner.MIN_SIZE);
    }
}
