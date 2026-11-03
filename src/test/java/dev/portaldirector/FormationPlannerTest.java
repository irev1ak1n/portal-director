package dev.portaldirector;

import dev.portaldirector.entrance.ReleasePattern;
import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.FormationPlanner;
import dev.portaldirector.gen.SquareFacing;
import dev.portaldirector.gen.SquarePlanner;
import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class FormationPlannerTest {

    private static final int CX = 1000;
    private static final int CZ = -500;
    private static final int FLOOR = 99;

    private static void assertSeparated(List<PortalSpec> l, int gapH, int gapV) {
        for (int i = 0; i < l.size(); i++) {
            for (int j = i + 1; j < l.size(); j++) {
                assertTrue(l.get(i).separatedFrom(l.get(j), gapH, gapV), "portals " + l.get(i).id() + " and " + l.get(j).id() + " are too close");
            }
        }
    }

    private static PortalSpec grid(List<PortalSpec> l, int row, int col, int columns) {
        return l.get(row * columns + col);
    }

    // Grid

    @Test
    void gridMakesExactlyRowsTimesColumnsInAStraightWall() {
        List<PortalSpec> g = FormationPlanner.grid(1, CX, CZ, FLOOR, 4, 5, 8, 5, FacingMode.SOUTH, BlockFace.NORTH, new Random(1));
        assertEquals(20, g.size());
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 5; c++) {
                PortalSpec p = grid(g, r, c, 5);
                assertEquals(1 + r * 5 + c, p.id());
                assertEquals(grid(g, 0, c, 5).x(), p.x(), "columns line up");
                assertEquals(grid(g, 0, 0, 5).z(), p.z(), "one plane");
                assertEquals(grid(g, r, 0, 5).y(), p.y(), "rows are level");
                assertEquals(Axis.X, p.axis());
                assertEquals(BlockFace.SOUTH, p.facing());
            }
        }
    }

    @Test
    void gridRowsAreOneFramePlusTheVerticalGapApartAndColumnsOneFramePlusTheHorizontalGap() {
        for (int gapV : new int[]{1, 5, 9}) {
            for (int gapH : new int[]{1, 8}) {
                List<PortalSpec> g = FormationPlanner.grid(1, CX, CZ, FLOOR, 4, 5, gapH, gapV, FacingMode.NORTH, BlockFace.NORTH, new Random(1));
                for (int r = 1; r < 4; r++) assertEquals(5 + gapV, grid(g, r, 0, 5).y() - grid(g, r - 1, 0, 5).y());
                for (int c = 1; c < 5; c++) assertEquals(4 + gapH, grid(g, 0, c, 5).x() - grid(g, 0, c - 1, 5).x());
                assertSeparated(g, gapH, gapV);
            }
        }
    }

    @Test
    void gridIsCenteredOnTheCenterWithTheMiddleRowAtCenterHeight() {
        List<PortalSpec> odd = FormationPlanner.grid(1, CX, CZ, FLOOR, 3, 5, 8, 5, FacingMode.SOUTH, BlockFace.NORTH, new Random(1));
        assertEquals(FLOOR, grid(odd, 1, 0, 5).y(), "middle of three rows is at the center");
        assertEquals(CX, grid(odd, 0, 2, 5).x() + 2, "middle of five columns is on the center");
        assertEquals(CX, (grid(odd, 0, 0, 5).x() + grid(odd, 0, 4, 5).x() + 4) / 2);
        List<PortalSpec> even = FormationPlanner.grid(1, CX, CZ, FLOOR, 4, 4, 8, 5, FacingMode.SOUTH, BlockFace.NORTH, new Random(1));
        assertEquals(FLOOR, grid(even, 1, 0, 4).y(), "lower of the two middle rows is at the center");
        assertEquals(FLOOR - 10, grid(even, 0, 0, 4).y());
        assertEquals(FLOOR + 20, grid(even, 3, 0, 4).y());
        assertEquals(CX, (grid(even, 0, 0, 4).x() + grid(even, 0, 3, 4).x() + 4) / 2);
    }

    @Test
    void gridRotatesWithTheChosenDirectionAndKeepsItsShape() {
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace f : faces) {
            FacingMode mode = FacingMode.valueOf(f.name());
            List<PortalSpec> g = FormationPlanner.grid(1, CX, CZ, FLOOR, 4, 5, 8, 5, mode, BlockFace.SOUTH, new Random(1));
            assertEquals(20, g.size());
            boolean alongX = f == BlockFace.NORTH || f == BlockFace.SOUTH;
            Set<Integer> planes = new HashSet<>();
            Set<Integer> columns = new TreeSet<>();
            for (PortalSpec p : g) {
                assertEquals(f, p.facing());
                assertEquals(alongX ? Axis.X : Axis.Z, p.axis());
                planes.add(alongX ? p.z() : p.x());
                columns.add(alongX ? p.x() : p.z());
            }
            assertEquals(1, planes.size(), "all portals in one plane facing " + f);
            assertEquals(5, columns.size());
            assertSeparated(g, 8, 5);
        }
    }

    @Test
    void randomFacingKeepsEveryPortalOnTheExactGridPositions() {
        List<PortalSpec> g = FormationPlanner.grid(1, CX, CZ, FLOOR, 4, 5, 8, 5, FacingMode.RANDOM, BlockFace.SOUTH, new Random(7));
        List<PortalSpec> straight = FormationPlanner.grid(1, CX, CZ, FLOOR, 4, 5, 8, 5, FacingMode.RANDOM, BlockFace.SOUTH, new Random(99));
        assertEquals(20, g.size());
        Set<BlockFace> faces = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            PortalSpec p = g.get(i);
            faces.add(p.facing());
            assertEquals(straight.get(i).y(), p.y(), "heights do not depend on the random facing");
            assertEquals(CX + FormationPlanner.offsetOf(i % 5, 5, 7 + 8), centerOf(p)[0], 0.51, "column " + (i % 5) + " stays on its grid line");
            assertEquals(CZ, Math.round(centerOf(p)[1]), 1);
        }
        assertTrue(faces.size() > 1, "individual portals turn different ways");
        assertSeparated(g, 8, 5);
    }

    private static double[] centerOf(PortalSpec p) {
        return new double[]{p.centerX(), p.centerZ()};
    }

    @Test
    void gridHasNoRandomnessWithACardinalFacing() {
        List<PortalSpec> a = FormationPlanner.grid(1, CX, CZ, FLOOR, 4, 5, 8, 5, FacingMode.EAST, BlockFace.SOUTH, new Random(1));
        List<PortalSpec> b = FormationPlanner.grid(1, CX, CZ, FLOOR, 4, 5, 8, 5, FacingMode.EAST, BlockFace.SOUTH, new Random(2));
        assertEquals(a, b);
    }

    // Wave

    @Test
    void waveMakesRowsOfExactlyPerRowPortalsWithAWavePattern() {
        List<PortalSpec> w = FormationPlanner.wave(1, CX, CZ, FLOOR, 4, 5, 8, 5, FacingMode.SOUTH, BlockFace.NORTH, new Random(1));
        assertEquals(20, w.size());
        for (int r = 0; r < 4; r++) {
            Set<Integer> heights = new HashSet<>();
            for (int i = 0; i < 5; i++) heights.add(grid(w, r, i, 5).y());
            assertTrue(heights.size() >= 3, "row " + r + " rises and falls");
            for (int i = 1; i < 5; i++) assertEquals(4 + 8, grid(w, r, i, 5).x() - grid(w, r, i - 1, 5).x(), "constant horizontal pitch in a row");
        }
        assertNotEquals(grid(w, 0, 0, 5).x(), grid(w, 1, 0, 5).x(), "neighbouring rows are staggered sideways");
        assertNotEquals(grid(w, 0, 1, 5).y() - grid(w, 0, 0, 5).y(), 0, "not a flat row");
        assertSeparated(w, 8, 5);
    }

    @Test
    void waveDiffersFromAGridOfTheSameSize() {
        List<PortalSpec> w = FormationPlanner.wave(1, CX, CZ, FLOOR, 4, 5, 8, 5, FacingMode.SOUTH, BlockFace.NORTH, new Random(1));
        List<PortalSpec> g = FormationPlanner.grid(1, CX, CZ, FLOOR, 4, 5, 8, 5, FacingMode.SOUTH, BlockFace.NORTH, new Random(1));
        int same = 0;
        for (int i = 0; i < 20; i++) {
            if (w.get(i).x() == g.get(i).x() && w.get(i).y() == g.get(i).y()) same++;
        }
        assertTrue(same < 8, "most wave portals are not where the grid puts them");
    }

    @Test
    void waveKeepsItsShapeForEveryDirectionAndRotatesWithIt() {
        for (FacingMode mode : new FacingMode[]{FacingMode.NORTH, FacingMode.SOUTH, FacingMode.EAST, FacingMode.WEST}) {
            List<PortalSpec> w = FormationPlanner.wave(1, CX, CZ, FLOOR, 4, 5, 6, 3, mode, BlockFace.SOUTH, new Random(1));
            assertEquals(20, w.size());
            assertTrue(w.stream().allMatch(p -> p.facing().name().equals(mode.name())));
            assertSeparated(w, 6, 3);
        }
    }

    @Test
    void waveWithRandomFacingKeepsThePositions() {
        List<PortalSpec> a = FormationPlanner.wave(1, CX, CZ, FLOOR, 4, 5, 8, 5, FacingMode.RANDOM, BlockFace.SOUTH, new Random(3));
        List<PortalSpec> b = FormationPlanner.wave(1, CX, CZ, FLOOR, 4, 5, 8, 5, FacingMode.RANDOM, BlockFace.SOUTH, new Random(4));
        assertEquals(20, a.size());
        for (int i = 0; i < 20; i++) assertEquals(a.get(i).y(), b.get(i).y());
        assertSeparated(a, 8, 5);
    }

    // Square

    @Test
    void squareHasFourEqualSidesWithPerSidePortalsAndNoOverlaps() {
        List<PortalSpec> sq = FormationPlanner.square(1, CX, CZ, FLOOR, 5, 1, 8, 5, SquareFacing.INWARD);
        assertEquals(20, sq.size());
        for (int side = 0; side < 4; side++) {
            Axis axis = side % 2 == 0 ? Axis.X : Axis.Z;
            for (int k = 0; k < 5; k++) assertEquals(axis, sq.get(side * 5 + k).axis());
        }
        Set<String> spots = new HashSet<>();
        sq.forEach(p -> spots.add(p.x() + "," + p.z()));
        assertEquals(20, spots.size(), "no duplicated portals at the corners");
        assertSeparated(sq, 8, 5);
        int[] extent = new int[4];
        for (int side = 0; side < 4; side++) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int k = 0; k < 5; k++) {
                PortalSpec p = sq.get(side * 5 + k);
                int a = side % 2 == 0 ? p.x() : p.z();
                min = Math.min(min, a);
                max = Math.max(max, a + 3);
            }
            extent[side] = max - min + 1;
        }
        for (int e : extent) assertEquals(extent[0], e);
    }

    @Test
    void squareFacesTheMiddleOrAwayFromIt() {
        for (SquareFacing f : SquareFacing.values()) {
            List<PortalSpec> sq = FormationPlanner.square(1, CX, CZ, FLOOR, 4, 1, 6, 5, f);
            for (PortalSpec p : sq) {
                double toMiddle = p.stepX() * (CX - p.centerX()) + p.stepZ() * (CZ - p.centerZ());
                assertTrue(f == SquareFacing.INWARD ? toMiddle > 0 : toMiddle < 0);
            }
        }
    }

    @Test
    void squareLayersAreIdenticalAndStackedByTheVerticalSpacing() {
        for (int layers : new int[]{1, 2, 3, 4, 5}) {
            for (int gapV : new int[]{2, 5}) {
                List<PortalSpec> sq = FormationPlanner.square(1, CX, CZ, FLOOR, 5, layers, 8, gapV, SquareFacing.INWARD);
                assertEquals(20 * layers, sq.size());
                for (int k = 1; k < layers; k++) {
                    for (int i = 0; i < 20; i++) {
                        PortalSpec base = sq.get(i);
                        PortalSpec up = sq.get(k * 20 + i);
                        assertEquals(base.x(), up.x());
                        assertEquals(base.z(), up.z());
                        assertEquals(base.facing(), up.facing());
                        assertEquals(base.y() + k * (5 + gapV), up.y());
                    }
                }
                assertSeparated(sq, 8, gapV);
            }
        }
    }

    @Test
    void squareLayersAreBalancedAroundTheCenterHeight() {
        assertEquals(List.of(FLOOR), layerFloors(1));
        assertEquals(List.of(FLOOR, FLOOR + 10), layerFloors(2), "two layers: one at the center, one above");
        assertEquals(List.of(FLOOR - 10, FLOOR, FLOOR + 10), layerFloors(3), "three layers: below, at, above");
        assertEquals(List.of(FLOOR - 10, FLOOR, FLOOR + 10, FLOOR + 20), layerFloors(4));
        assertEquals(List.of(FLOOR - 20, FLOOR - 10, FLOOR, FLOOR + 10, FLOOR + 20), layerFloors(5));
    }

    private static List<Integer> layerFloors(int layers) {
        List<PortalSpec> sq = FormationPlanner.square(1, CX, CZ, FLOOR, 3, layers, 6, 5, SquareFacing.INWARD);
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < layers; k++) out.add(sq.get(k * 12).y());
        return out;
    }

    @Test
    void squareIdsGoLayerByLayerAroundTheRing() {
        List<PortalSpec> sq = FormationPlanner.square(5, CX, CZ, FLOOR, 3, 2, 6, 5, SquareFacing.INWARD);
        for (int i = 0; i < sq.size(); i++) assertEquals(5 + i, sq.get(i).id());
    }

    // Counts, placement, checks

    @Test
    void countsComeFromTheLayoutSettings() {
        assertEquals(20, FormationPlanner.gridCount(4, 5));
        assertEquals(60, FormationPlanner.squareCount(5, 3));
        assertEquals(20, FormationPlanner.waveCount(4, 5));
    }

    @Test
    void shiftMovesAFormationWithoutChangingItsShape() {
        List<PortalSpec> g = FormationPlanner.grid(1, CX, CZ, FLOOR, 2, 2, 8, 5, FacingMode.SOUTH, BlockFace.SOUTH, new Random(1));
        List<PortalSpec> moved = FormationPlanner.shift(g, 17);
        assertEquals(FormationPlanner.lowestFloor(g) + 17, FormationPlanner.lowestFloor(moved));
        for (int i = 0; i < g.size(); i++) assertEquals(g.get(i).y() + 17, moved.get(i).y());
    }

    @Test
    void checkRefusesBuildsAndExistingPortalsButNotNaturalGround() {
        FakeTerrain t = FakeTerrain.flat(64);
        List<PortalSpec> g = FormationPlanner.grid(1, 0, 0, 100, 2, 3, 8, 5, FacingMode.SOUTH, BlockFace.SOUTH, new Random(1));
        FormationPlanner.Fit ok = FormationPlanner.check(t, g, -64, 320, List.of(), false, 4, FormationPlanner.lowestFloor(g));
        assertEquals(6, ok.usable().size());
        assertTrue(ok.problems().isEmpty());
        PortalSpec first = g.get(0);
        t.structureAt(first.cellX(1), first.cellZ(1));
        FormationPlanner.Fit blocked = FormationPlanner.check(t, g, -64, 320, List.of(), false, 4, FormationPlanner.lowestFloor(g));
        assertEquals(4, blocked.usable().size(), "the column with a build in it blocks the portals stacked above each other");
        assertEquals(2, blocked.problems().size());
        FormationPlanner.Fit crowded = FormationPlanner.check(FakeTerrain.flat(64), g, -64, 320, List.of(g.get(2)), false, 4, FormationPlanner.lowestFloor(g));
        assertEquals(5, crowded.usable().size());
        FormationPlanner.Fit low = FormationPlanner.check(FakeTerrain.flat(64), g, 120, 320, List.of(), false, 4, FormationPlanner.lowestFloor(g));
        assertTrue(low.usable().isEmpty(), "min-y is respected");
    }

    @Test
    void groundModeMarksOnlyDistantGroundAsFloating() {
        FakeTerrain t = FakeTerrain.flat(64);
        List<PortalSpec> g = FormationPlanner.grid(1, 0, 0, 100, 2, 2, 8, 5, FacingMode.SOUTH, BlockFace.SOUTH, new Random(1));
        int low = FormationPlanner.lowestFloor(g);
        List<PortalSpec> sitting = FormationPlanner.shift(g, 64 - low);
        FormationPlanner.Fit fit = FormationPlanner.check(t, sitting, -64, 320, List.of(), true, 4, 64);
        assertEquals(4, fit.usable().size());
        assertTrue(fit.usable().stream().filter(p -> p.y() == 64).noneMatch(PortalSpec::floating));
        assertTrue(fit.usable().stream().filter(p -> p.y() > 64).allMatch(PortalSpec::floating));
    }

    // Release patterns with layers

    @Test
    void releasePatternsGroupBySideAcrossLayersAndGoAroundLayerByLayer() {
        List<List<Integer>> sides = List.of(List.of(1, 2, 9, 10), List.of(3, 4, 11, 12), List.of(5, 6, 13, 14), List.of(7, 8, 15, 16));
        List<Integer> ring = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        Set<Integer> all = new HashSet<>(ring);
        assertEquals(List.of(ring), ReleasePattern.ALL_AT_ONCE.waves(sides, ring, all, 1));
        assertEquals(sides, ReleasePattern.SIDE_BY_SIDE.waves(sides, ring, all, 1));
        assertEquals(List.of(List.of(1, 2, 9, 10, 5, 6, 13, 14), List.of(3, 4, 11, 12, 7, 8, 15, 16)), ReleasePattern.OPPOSITE_SIDES.waves(sides, ring, all, 1));
        assertEquals(16, ReleasePattern.CLOCKWISE.waves(sides, ring, all, 1).size());
        assertEquals(List.of(1, 2, 3, 4), ReleasePattern.CLOCKWISE.waves(sides, ring, all, 4).get(0));
        assertEquals(List.of(List.of(1), List.of(5), List.of(8)), ReleasePattern.SIDE_BY_SIDE.waves(sides, ring, Set.of(1, 5, 8), 1));
        assertEquals(ReleasePattern.OPPOSITE_SIDES, ReleasePattern.parse("opposite-sides"));
    }
}
