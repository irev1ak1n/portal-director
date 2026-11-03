package dev.portaldirector;

import dev.portaldirector.entrance.ReleasePattern;
import dev.portaldirector.gen.SquareFacing;
import dev.portaldirector.gen.SquarePlanner;
import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SquarePlannerTest {

    private static List<PortalSpec> layout(int perSide, int gap, SquareFacing facing) {
        return SquarePlanner.layout(1, 100, 200, perSide, gap, facing);
    }

    @Test
    void sizeAndFitAreInverse() {
        for (int gap = 0; gap <= 6; gap++) {
            for (int n = 2; n <= 20; n++) {
                int size = SquarePlanner.sizeFor(n, gap);
                assertEquals(n, SquarePlanner.perSideFor(size, gap));
                assertEquals(n - 1, SquarePlanner.perSideFor(size - 1, gap));
            }
        }
        assertEquals(9, SquarePlanner.perSideFor(60, 2));
    }

    @Test
    void fourEqualSidesWithIdsInClockwiseOrder() {
        List<PortalSpec> l = layout(6, 2, SquareFacing.INWARD);
        assertEquals(24, l.size());
        for (int i = 0; i < 24; i++) assertEquals(i + 1, l.get(i).id());
        for (int side = 0; side < 4; side++) {
            Axis axis = side % 2 == 0 ? Axis.X : Axis.Z;
            for (int k = 0; k < 6; k++) assertEquals(axis, l.get(side * 6 + k).axis());
        }
    }

    @Test
    void sideLengthsAreEqualAndGapsAreExact() {
        for (int gap : new int[]{0, 1, 3}) {
            List<PortalSpec> l = layout(5, gap, SquareFacing.INWARD);
            int span = 5 * 4 + 4 * gap;
            int[] ext = new int[4];
            for (int side = 0; side < 4; side++) {
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (int k = 0; k < 5; k++) {
                    PortalSpec p = l.get(side * 5 + k);
                    int a = side % 2 == 0 ? p.x() : p.z();
                    min = Math.min(min, a);
                    max = Math.max(max, a + 3);
                    if (k > 0) {
                        PortalSpec q = l.get(side * 5 + k - 1);
                        int d = side % 2 == 0 ? Math.abs(p.x() - q.x()) : Math.abs(p.z() - q.z());
                        assertEquals(4 + gap, d);
                    }
                }
                ext[side] = max - min + 1;
            }
            for (int e : ext) assertEquals(span, e);
        }
    }

    @Test
    void sidesLieOnTheFourEdgesOfTheSquare() {
        int perSide = 5;
        int gap = 2;
        int size = SquarePlanner.sizeFor(perSide, gap);
        List<PortalSpec> l = layout(perSide, gap, SquareFacing.INWARD);
        for (int k = 0; k < perSide; k++) {
            assertEquals(200, l.get(k).z());
            assertEquals(100 + size - 1, l.get(perSide + k).x());
            assertEquals(200 + size - 1, l.get(2 * perSide + k).z());
            assertEquals(100, l.get(3 * perSide + k).x());
        }
    }

    @Test
    void inwardPortalsFaceTheMiddleAndOutwardAwayFromIt() {
        BlockFace[] inward = {BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST};
        BlockFace[] outward = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        List<PortalSpec> in = layout(4, 2, SquareFacing.INWARD);
        List<PortalSpec> out = layout(4, 2, SquareFacing.OUTWARD);
        for (int i = 0; i < 16; i++) {
            assertEquals(inward[i / 4], in.get(i).facing());
            assertEquals(outward[i / 4], out.get(i).facing());
        }
        double mid = 100 + SquarePlanner.sizeFor(4, 2) / 2.0;
        double midZ = 200 + SquarePlanner.sizeFor(4, 2) / 2.0;
        for (PortalSpec p : in) {
            double toMiddle = p.stepX() * (mid - p.centerX()) + p.stepZ() * (midZ - p.centerZ());
            assertTrue(toMiddle > 0);
        }
        for (PortalSpec p : out) {
            double toMiddle = p.stepX() * (mid - p.centerX()) + p.stepZ() * (midZ - p.centerZ());
            assertTrue(toMiddle < 0);
        }
    }

    @Test
    void noFramesOrWalkwaysOverlapIncludingTheCorners() {
        for (SquareFacing f : SquareFacing.values()) {
            for (int gap = 0; gap <= 5; gap++) {
                for (int n = 2; n <= 12; n++) {
                    List<PortalSpec> l = layout(n, gap, f);
                    for (int i = 0; i < l.size(); i++) {
                        for (int j = i + 1; j < l.size(); j++) {
                            assertTrue(l.get(i).separatedFrom(l.get(j), 0),
                                    "overlap " + f + " gap " + gap + " n " + n + ": " + i + " vs " + j);
                        }
                    }
                }
            }
        }
    }

    @Test
    void cornersLeaveAGapOfAtLeastTheSpacing() {
        for (int gap = 1; gap <= 5; gap++) {
            List<PortalSpec> l = layout(4, gap, SquareFacing.INWARD);
            PortalSpec lastNorth = l.get(3);
            PortalSpec firstEast = l.get(4);
            PortalSpec lastWest = l.get(15);
            PortalSpec firstNorth = l.get(0);
            assertTrue(lastNorth.separatedFrom(firstEast, gap));
            assertTrue(lastWest.separatedFrom(firstNorth, gap));
        }
    }

    @Test
    void middleStaysOpen() {
        List<PortalSpec> l = layout(3, 2, SquareFacing.INWARD);
        int size = SquarePlanner.sizeFor(3, 2);
        int midX = 100 + size / 2;
        int midZ = 200 + size / 2;
        for (PortalSpec p : l) assertFalse(p.contains(midX, p.y(), midZ));
        assertTrue(size >= SquarePlanner.MIN_SIZE);
    }

    @Test
    void groundFitReportsBlockedPositions() {
        FakeTerrain t = FakeTerrain.flat(64);
        t.liquidAt(100, 200);
        List<PortalSpec> l = layout(4, 2, SquareFacing.INWARD);
        SquarePlanner.Fit fit = SquarePlanner.fit(t, l, 4, 3, null, List.of());
        assertEquals(15, fit.usable().size());
        assertEquals(1, fit.problems().size());
        assertTrue(fit.problems().get(0).startsWith("north side, portal 1"));
        assertTrue(fit.usable().stream().allMatch(p -> p.y() == 64 && !p.floating()));
    }

    @Test
    void elevatedFitPutsEveryPortalOnOneLevel() {
        FakeTerrain t = new FakeTerrain((x, z) -> 60 + Math.floorMod(x, 7));
        List<PortalSpec> l = layout(4, 2, SquareFacing.INWARD);
        SquarePlanner.Fit fit = SquarePlanner.fit(t, l, 4, 3, 25, List.of());
        assertEquals(16, fit.usable().size());
        assertTrue(fit.usable().stream().allMatch(PortalSpec::floating));
        assertEquals(1, fit.usable().stream().mapToInt(PortalSpec::y).distinct().count());
        assertEquals(66 + 25, fit.usable().get(0).y());
    }

    @Test
    void fitRefusesPositionsCloseToExistingPortals() {
        PortalSpec blocker = new PortalSpec(99, 100, 65, 200, Axis.X, BlockFace.SOUTH, false);
        SquarePlanner.Fit fit = SquarePlanner.fit(FakeTerrain.flat(64), layout(4, 2, SquareFacing.INWARD), 4, 3, null, List.of(blocker));
        assertEquals(15, fit.usable().size());
        assertTrue(fit.problems().get(0).contains("too close to portal 99"));
    }

    @Test
    void releasePatternsGroupThePortalsBySide() {
        List<List<Integer>> sides = List.of(List.of(1, 2), List.of(3, 4), List.of(5, 6), List.of(7, 8));
        Set<Integer> all = Set.of(1, 2, 3, 4, 5, 6, 7, 8);
        assertEquals(List.of(List.of(1, 2, 3, 4, 5, 6, 7, 8)), ReleasePattern.ALL_AT_ONCE.waves(sides, all, 1));
        assertEquals(sides, ReleasePattern.SIDE_BY_SIDE.waves(sides, all, 1));
        assertEquals(List.of(List.of(1, 2, 5, 6), List.of(3, 4, 7, 8)), ReleasePattern.OPPOSITE_SIDES.waves(sides, all, 1));
        assertEquals(8, ReleasePattern.CLOCKWISE.waves(sides, all, 1).size());
        assertEquals(List.of(List.of(1, 2, 3), List.of(4, 5, 6), List.of(7, 8)), ReleasePattern.CLOCKWISE.waves(sides, all, 3));
        assertEquals(List.of(1), ReleasePattern.CLOCKWISE.waves(sides, all, 1).get(0));
        assertEquals(List.of(2), ReleasePattern.CLOCKWISE.waves(sides, all, 1).get(1));
    }

    @Test
    void releasePatternsSkipUnassignedPortals() {
        List<List<Integer>> sides = List.of(List.of(1, 2), List.of(3, 4), List.of(5, 6), List.of(7, 8));
        Set<Integer> some = Set.of(1, 5, 8);
        assertEquals(List.of(List.of(1), List.of(5), List.of(8)), ReleasePattern.SIDE_BY_SIDE.waves(sides, some, 1));
        assertEquals(List.of(List.of(1, 5), List.of(8)), ReleasePattern.OPPOSITE_SIDES.waves(sides, some, 1));
        assertEquals(List.of(), ReleasePattern.CLOCKWISE.waves(sides, Set.of(), 1));
        assertEquals(ReleasePattern.OPPOSITE_SIDES, ReleasePattern.parse("opposite-sides"));
        assertNull(ReleasePattern.parse("staggered"));
    }
}
