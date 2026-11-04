package dev.portaldirector;

import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.GenSettings;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.Bounds;
import dev.portaldirector.gen.Placement;
import dev.portaldirector.gen.PortalPlanner;
import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PortalPlannerTest {

    private static List<PortalSpec> run(FakeTerrain t, GenSettings s, List<PortalSpec> existing, int firstId, long seed) {
        PortalPlanner.Session session = new PortalPlanner.Session(t, s, 0, 0, existing, firstId, new Random(seed));
        while (!session.done()) session.step(50);
        return session.placed();
    }

    private static GenSettings settings(int count, Placement placement, Layout layout) {
        GenSettings s = new GenSettings();
        s.count = count;
        s.placement = placement;
        s.layout = layout;
        s.radius = 100;
        s.spacingH = 8;
        s.spacingV = 5;
        return s;
    }

    private static void assertNoOverlapAndGap(List<PortalSpec> portals, int gap) {
        assertNoOverlapAndGap(portals, gap, gap);
    }

    private static void assertNoOverlapAndGap(List<PortalSpec> portals, int gapH, int gapV) {
        for (int i = 0; i < portals.size(); i++) {
            for (int j = i + 1; j < portals.size(); j++) {
                assertTrue(portals.get(i).separatedFrom(portals.get(j), gapH, gapV),
                        "portals " + portals.get(i).id() + " and " + portals.get(j).id() + " are too close");
            }
        }
    }

    @Test
    void placesTheRequestedCountWithUniqueIdsFromOne() {
        List<PortalSpec> out = run(FakeTerrain.flat(64), settings(50, Placement.MIXED, Layout.RANDOM), List.of(), 1, 1);
        assertEquals(50, out.size());
        Set<Integer> ids = new HashSet<>();
        out.forEach(p -> ids.add(p.id()));
        assertEquals(50, ids.size());
        assertEquals(1, ids.stream().mapToInt(i -> i).min().getAsInt());
        assertEquals(50, ids.stream().mapToInt(i -> i).max().getAsInt());
    }

    @Test
    void neverOverlapsAndKeepsTheSpacing() {
        for (Layout layout : new Layout[]{Layout.RANDOM}) {
            GenSettings s = settings(40, Placement.MIXED, layout);
            s.spacingH = 10;
            s.spacingV = 6;
            List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, 7);
            assertFalse(out.isEmpty(), layout.name());
            assertNoOverlapAndGap(out, 10, 6);
        }
    }

    @Test
    void zeroSpacingStillLeavesOneBlockBetweenPortals() {
        GenSettings s = settings(60, Placement.GROUND, Layout.RANDOM);
        s.spacingH = 0;
        s.spacingV = 0;
        s.radius = 40;
        assertNoOverlapAndGap(run(FakeTerrain.flat(64), s, List.of(), 1, 3), 1);
    }

    @Test
    void respectsExistingPortalsFromOtherScenes() {
        PortalSpec blocker = new PortalSpec(1, -2, 65, 0, Axis.X, BlockFace.SOUTH, false);
        GenSettings s = settings(30, Placement.GROUND, Layout.RANDOM);
        s.radius = 15;
        List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(blocker), 2, 5);
        assertFalse(out.isEmpty());
        for (PortalSpec p : out) assertTrue(p.separatedFrom(blocker, 8));
        assertEquals(2, out.get(0).id());
    }

    @Test
    void groundPortalsSitOnTheSurface() {
        List<PortalSpec> out = run(FakeTerrain.flat(70), settings(20, Placement.GROUND, Layout.RANDOM), List.of(), 1, 2);
        assertEquals(20, out.size());
        for (PortalSpec p : out) {
            assertEquals(70, p.y());
            assertFalse(p.floating());
        }
    }

    @Test
    void unevenTerrainUsesTheHighestColumn() {
        FakeTerrain hills = new FakeTerrain((x, z) -> 60 + Math.floorMod(x / 3, 3));
        List<PortalSpec> out = run(hills, settings(20, Placement.GROUND, Layout.RANDOM), List.of(), 1, 4);
        assertFalse(out.isEmpty());
        for (PortalSpec p : out) {
            int top = 0;
            for (int i = 0; i < 4; i++) top = Math.max(top, hills.topY(p.cellX(i), p.cellZ(i)));
            assertEquals(top, p.y());
        }
    }

    @Test
    void steepGroundIsRejected() {
        FakeTerrain cliffs = new FakeTerrain((x, z) -> Math.floorMod(x, 2) == 0 ? 60 : 80);
        GenSettings s = settings(5, Placement.GROUND, Layout.RANDOM);
        s.facing = FacingMode.NORTH;
        assertTrue(run(cliffs, s, List.of(), 1, 4).isEmpty());
    }

    @Test
    void portalsAtDifferentHeightsMayShareTheSameColumn() {
        GenSettings s = settings(40, Placement.AIR, Layout.RANDOM);
        s.radius = 10;
        s.spacingH = 8;
        s.spacingV = 5;
        s.altitudeMin = 2;
        s.altitudeMax = 90;
        List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, 21);
        assertNoOverlapAndGap(out, 8, 5);
        int stackedPairs = 0;
        for (int i = 0; i < out.size(); i++) {
            for (int j = i + 1; j < out.size(); j++) {
                if (!out.get(i).separatedFrom(out.get(j), 8)) stackedPairs++;
            }
        }
        assertTrue(stackedPairs > 0, "some portals should hang above others instead of being rejected");
        assertTrue(out.size() > 12, "a tiny radius should still hold many portals when they can stack: " + out.size());
    }

    @Test
    void portalsAtTheSameHeightKeepTheHorizontalGapWhateverTheirRotation() {
        for (FacingMode f : new FacingMode[]{FacingMode.RANDOM, FacingMode.NORTH, FacingMode.EAST}) {
            GenSettings s = settings(30, Placement.AIR, Layout.RANDOM);
            s.facing = f;
            s.radius = 40;
            s.altitudeMin = 20;
            s.altitudeMax = 20;
            s.spacingH = 9;
            s.spacingV = 3;
            List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, 5);
            for (int i = 0; i < out.size(); i++) {
                for (int j = i + 1; j < out.size(); j++) {
                    PortalSpec a = out.get(i);
                    PortalSpec b = out.get(j);
                    assertEquals(a.y(), b.y());
                    assertTrue(a.separatedFrom(b, 9), "same-height portals " + a.id() + " and " + b.id() + " closer than the horizontal gap");
                }
            }
        }
    }

    @Test
    void groundPortalsDoNotRunIntoAHillside() {
        FakeTerrain hill = new FakeTerrain((x, z) -> z >= 10 ? 90 : 64);
        GenSettings s = settings(30, Placement.GROUND, Layout.RANDOM);
        s.radius = 40;
        s.facing = FacingMode.NORTH;
        for (PortalSpec p : run(hill, s, List.of(), 1, 12)) {
            for (int[] c : p.corridorBlocks()) {
                if (c[1] == p.y() + 1) assertTrue(hill.topY(c[0], c[2]) <= p.y() + 1, "walkway of portal " + p.id() + " is inside the hill");
            }
        }
    }

    @Test
    void floatingPortalsHangInTheHeightRangeAboveTheGround() {
        GenSettings s = settings(30, Placement.AIR, Layout.RANDOM);
        s.altitudeMin = 25;
        s.altitudeMax = 80;
        List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, 9);
        assertEquals(30, out.size());
        Set<Integer> heights = new HashSet<>();
        for (PortalSpec p : out) {
            assertTrue(p.floating());
            assertTrue(p.y() >= 64 + 25 && p.y() <= 64 + 80, "height " + p.y());
            heights.add(p.y());
        }
        assertTrue(heights.size() > 5, "heights should vary per portal");
    }

    @Test
    void mixedProducesBothKinds() {
        List<PortalSpec> out = run(FakeTerrain.flat(64), settings(40, Placement.MIXED, Layout.RANDOM), List.of(), 1, 11);
        assertTrue(out.stream().anyMatch(PortalSpec::floating));
        assertTrue(out.stream().anyMatch(p -> !p.floating()));
    }

    @Test
    void randomFacingIsSpreadEvenlyAndScatteredForTenFortyAndAHundredPortals() {
        for (int n : new int[]{10, 40, 100}) {
            for (long seed : new long[]{1, 2, 3}) {
                GenSettings s = settings(n, Placement.GROUND, Layout.RANDOM);
                s.facing = FacingMode.RANDOM;
                s.radius = 150;
                s.spacingH = 2;
                List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, seed);
                assertEquals(n, out.size(), n + " portals, seed " + seed);
                java.util.Map<BlockFace, Integer> counts = new java.util.EnumMap<>(BlockFace.class);
                for (PortalSpec p : out) {
                    counts.merge(p.facing(), 1, Integer::sum);
                    assertEquals(p.facing() == BlockFace.NORTH || p.facing() == BlockFace.SOUTH ? Axis.X : Axis.Z, p.axis(), "the frame follows the facing");
                }
                assertEquals(4, counts.size(), n + " portals use all four directions");
                int lo = counts.values().stream().min(Integer::compare).get();
                int hi = counts.values().stream().max(Integer::compare).get();
                assertTrue(hi - lo <= 1, n + " portals, seed " + seed + ": " + counts);
                // not grouped: neighbours in the order they were placed rarely share a direction, and neither do neighbours in space
                int sameInOrder = 0;
                for (int i = 1; i < out.size(); i++) if (out.get(i).facing() == out.get(i - 1).facing()) sameInOrder++;
                assertTrue(sameInOrder <= out.size() / 2, "runs of one direction: " + sameInOrder + " of " + out.size());
                if (n >= 40) {
                    int sameNearest = 0;
                    for (PortalSpec p : out) {
                        PortalSpec nearest = null;
                        double best = Double.MAX_VALUE;
                        for (PortalSpec q : out) {
                            if (q == p) continue;
                            double d = Math.hypot(p.centerX() - q.centerX(), p.centerZ() - q.centerZ());
                            if (d < best) {
                                best = d;
                                nearest = q;
                            }
                        }
                        if (nearest.facing() == p.facing()) sameNearest++;
                    }
                    assertTrue(sameNearest < out.size() * 0.5, "neighbours in space sharing a direction: " + sameNearest + " of " + out.size());
                }
            }
        }
    }

    @Test
    void aFixedFacingIsNeverRandomisedAndOnlyThePlacedPortalsCount() {
        GenSettings s = settings(40, Placement.MIXED, Layout.RANDOM);
        s.facing = FacingMode.NORTH;
        assertTrue(run(FakeTerrain.flat(64), s, List.of(), 1, 4).stream().allMatch(p -> p.facing() == BlockFace.NORTH));
    }

    @Test
    void randomFacingKeepsSpacingAndTheTerrainChecks() {
        FakeTerrain hills = new FakeTerrain((x, z) -> 60 + Math.floorMod(x / 3, 3));
        GenSettings s = settings(40, Placement.MIXED, Layout.RANDOM);
        s.facing = FacingMode.RANDOM;
        List<PortalSpec> out = run(hills, s, List.of(), 1, 8);
        assertEquals(40, out.size());
        assertNoOverlapAndGap(out, 8, 5);
        for (PortalSpec p : out) {
            assertTrue(Bounds.around(0, 0, s.effectiveRadius()).containsFrame(p));
            for (int[] c : p.corridorBlocks()) assertNotEquals(p.facing().getOppositeFace(), null);
        }
    }

    @Test
    void facingDecidesWhichWayTheFrameRuns() {
        for (FacingMode f : new FacingMode[]{FacingMode.NORTH, FacingMode.SOUTH, FacingMode.EAST, FacingMode.WEST}) {
            GenSettings s = settings(20, Placement.GROUND, Layout.RANDOM);
            s.facing = f;
            Axis axis = f == FacingMode.NORTH || f == FacingMode.SOUTH ? Axis.X : Axis.Z;
            List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, 1);
            assertFalse(out.isEmpty(), f.name());
            for (PortalSpec p : out) {
                assertEquals(BlockFace.valueOf(f.name()), p.facing(), f.name());
                assertEquals(axis, p.axis(), f.name());
            }
        }
        GenSettings s = settings(20, Placement.GROUND, Layout.RANDOM);
        s.facing = FacingMode.RANDOM;
        List<PortalSpec> random = run(FakeTerrain.flat(64), s, List.of(), 1, 1);
        assertTrue(random.stream().anyMatch(p -> p.axis() == Axis.X));
        assertTrue(random.stream().anyMatch(p -> p.axis() == Axis.Z));
        assertEquals(java.util.Set.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST), random.stream().map(PortalSpec::facing).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void everyFrameBlockStaysInsideTheRadiusSquareForEveryFacing() {
        int cx = 100;
        int cz = -50;
        for (FacingMode f : new FacingMode[]{FacingMode.NORTH, FacingMode.SOUTH, FacingMode.EAST, FacingMode.WEST, FacingMode.RANDOM}) {
            for (Placement place : Placement.values()) {
                GenSettings s = settings(80, place, Layout.RANDOM);
                s.facing = f;
                s.radius = 30;
                s.spacingH = 1;
                s.spacingV = 1;
                PortalPlanner.Session session = new PortalPlanner.Session(FakeTerrain.flat(64), s, cx, cz, List.of(), 1, new Random(5));
                while (!session.done()) session.step(100);
                assertFalse(session.placed().isEmpty(), f + " " + place);
                for (PortalSpec p : session.placed()) {
                    for (int[] b : p.frameBlocks()) {
                        assertTrue(Math.abs(b[0] - cx) <= 31 && Math.abs(b[2] - cz) <= 31, f + " " + place + ": frame block " + b[0] + "," + b[2] + " is beyond the radius");
                    }
                }
            }
        }
    }

    @Test
    void theRadiusIsReallyUsedUpToItsEdge() {
        GenSettings s = settings(300, Placement.GROUND, Layout.RANDOM);
        s.radius = 20;
        s.spacingH = 1;
        PortalPlanner.Session session = new PortalPlanner.Session(FakeTerrain.flat(64), s, 0, 0, List.of(), 1, new Random(3));
        while (!session.done()) session.step(200);
        int reach = 0;
        for (PortalSpec p : session.placed()) reach = Math.max(reach, Bounds.radiusNeeded(p, 0, 0));
        assertEquals(21, reach, "some frame should touch the effective boundary");
    }

    @Test
    void anAutomaticFivePercentMarginIsAddedOnEachSide() {
        GenSettings s = new GenSettings();
        s.radius = 100;
        assertEquals(105, s.effectiveRadius());
        s.radius = 50;
        assertEquals(52, s.effectiveRadius());
        s.radius = 20;
        assertEquals(21, s.effectiveRadius());
        s.radius = 8;
        assertEquals(8, s.effectiveRadius());
        GenSettings m = settings(300, Placement.GROUND, Layout.RANDOM);
        m.radius = 40;
        m.spacingH = 1;
        PortalPlanner.Session session = new PortalPlanner.Session(FakeTerrain.flat(64), m, 0, 0, List.of(), 1, new Random(3));
        while (!session.done()) session.step(200);
        int reach = 0;
        for (PortalSpec p : session.placed()) reach = Math.max(reach, Bounds.radiusNeeded(p, 0, 0));
        assertTrue(reach <= 42, "reach " + reach);
        assertTrue(reach >= 41, "the margin should be used: " + reach);
    }

    @Test
    void aRadiusTooSmallForAFrameBuildsNothingAndSaysWhy() {
        GenSettings s = settings(5, Placement.GROUND, Layout.RANDOM);
        s.radius = 1;
        PortalPlanner.Session session = new PortalPlanner.Session(FakeTerrain.flat(64), s, 0, 0, List.of(), 1, new Random(1));
        while (!session.done()) session.step(100);
        assertTrue(session.placed().isEmpty());
        assertTrue(session.reasons().containsKey("the radius is too small for a portal"), session.reasons().toString());
    }

    @Test
    void mixedModeIsAboutHalfFloatingAndHalfOnTheGround() {
        for (long seed : new long[]{1, 2, 3}) {
            List<PortalSpec> out = run(FakeTerrain.flat(64), settings(60, Placement.MIXED, Layout.RANDOM), List.of(), 1, seed);
            long floating = out.stream().filter(PortalSpec::floating).count();
            assertEquals(60, out.size());
            assertTrue(floating >= 27 && floating <= 33, "floating " + floating + " of " + out.size());
        }
    }

    @Test
    void floatingHeightIsRelativeToTheTerrainUnderEachPortal() {
        FakeTerrain steps = new FakeTerrain((x, z) -> x < 0 ? 64 : 120);
        GenSettings s = settings(30, Placement.AIR, Layout.RANDOM);
        s.altitudeMin = 15;
        s.altitudeMax = 40;
        s.radius = 60;
        List<PortalSpec> out = run(steps, s, List.of(), 1, 4);
        assertFalse(out.isEmpty());
        boolean low = false;
        boolean high = false;
        for (PortalSpec p : out) {
            int ground = 0;
            int[] box = p.box();
            for (int bx : new int[]{box[0], box[3]}) {
                for (int bz : new int[]{box[2], box[5]}) ground = Math.max(ground, steps.highestY(bx, bz));
            }
            assertTrue(p.y() - ground >= 15 && p.y() - ground <= 40, "portal " + p.id() + " is " + (p.y() - ground) + " above the ground");
            if (ground == 64) low = true;
            if (ground == 120) high = true;
        }
        assertTrue(low && high, "both elevations should be used");
    }

    @Test
    void worldYLimitsKeepWholeFramesInsideAndFloatingPortalsStillFindARoom() {
        GenSettings s = settings(30, Placement.AIR, Layout.RANDOM);
        s.altitudeMin = 15;
        s.altitudeMax = 90;
        s.worldYMin = 100;
        s.worldYMax = 120;
        List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, 6);
        assertFalse(out.isEmpty());
        for (PortalSpec p : out) {
            assertTrue(p.y() >= 100, "frame floor " + p.y());
            assertTrue(p.y() + PortalSpec.HEIGHT - 1 <= 120, "frame top " + (p.y() + PortalSpec.HEIGHT - 1));
        }
    }

    @Test
    void worldYMaxIsInclusiveForTheTopOfTheFrame() {
        GenSettings s = settings(10, Placement.AIR, Layout.RANDOM);
        s.altitudeMin = 15;
        s.altitudeMax = 15;
        s.worldYMax = 64 + 15 + PortalSpec.HEIGHT - 1;
        assertEquals(10, run(FakeTerrain.flat(64), s, List.of(), 1, 2).size());
        s.worldYMax -= 1;
        assertTrue(run(FakeTerrain.flat(64), s, List.of(), 1, 2).isEmpty());
    }

    @Test
    void impossibleHeightLimitsAreReportedWithTheReason() {
        GenSettings s = settings(5, Placement.AIR, Layout.RANDOM);
        s.altitudeMin = 15;
        s.altitudeMax = 60;
        s.worldYMax = 70;
        PortalPlanner.Session session = new PortalPlanner.Session(FakeTerrain.flat(64), s, 0, 0, List.of(), 1, new Random(1));
        while (!session.done()) session.step(100);
        assertTrue(session.placed().isEmpty());
        assertTrue(session.reasons().containsKey("above world-y-max"), session.reasons().toString());
        s.worldYMax = 320;
        s.worldYMin = 200;
        PortalPlanner.Session low = new PortalPlanner.Session(FakeTerrain.flat(64), s, 0, 0, List.of(), 1, new Random(1));
        while (!low.done()) low.step(100);
        assertTrue(low.placed().isEmpty() || low.placed().stream().allMatch(p -> p.y() >= 200));
    }

    @Test
    void groundPortalsStayOnTheSurfaceAndMoveToNearbyGoodGroundInsteadOfGivingUp() {
        // A pond in the middle of every 10 blocks: the first guess often lands in it, a nearby dry spot is used instead.
        FakeTerrain pond = new FakeTerrain((x, z) -> 64);
        for (int x = -60; x <= 60; x++) {
            for (int z = -60; z <= 60; z++) {
                if (Math.floorMod(x, 10) >= 3 && Math.floorMod(x, 10) <= 6) pond.liquidAt(x, z);
            }
        }
        GenSettings s = settings(25, Placement.GROUND, Layout.RANDOM);
        s.radius = 50;
        s.spacingH = 2;
        List<PortalSpec> out = run(pond, s, List.of(), 1, 9);
        assertTrue(out.size() >= 20, "placed " + out.size());
        for (PortalSpec p : out) {
            assertEquals(64, p.y());
            assertFalse(p.floating());
            for (int i = 0; i < 4; i++) assertFalse(pond.isLiquid(p.cellX(i), 64, p.cellZ(i)), "portal " + p.id() + " is on water");
        }
    }

    @Test
    void fixedFacingKeepsOneDirection() {
        GenSettings s = settings(16, Placement.GROUND, Layout.RANDOM);
        s.facing = FacingMode.EAST;
        s.radius = 60;
        List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, 1);
        assertFalse(out.isEmpty());
        assertTrue(out.stream().allMatch(p -> p.facing() == BlockFace.EAST && p.axis() == Axis.Z));
    }

    @Test
    void doesNotBuildOverStructuresOrWater() {
        FakeTerrain t = FakeTerrain.flat(64);
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                if (x < 0) t.structureAt(x, z);
                else if (z < 0) t.liquidAt(x, z);
            }
        }
        GenSettings s = settings(20, Placement.GROUND, Layout.RANDOM);
        s.radius = 30;
        List<PortalSpec> out = run(t, s, List.of(), 1, 8);
        assertFalse(out.isEmpty());
        for (PortalSpec p : out) {
            assertTrue(p.box()[0] >= 0, "inside a structure: " + p);
            for (int i = 0; i < 4; i++) assertTrue(p.cellZ(i) >= 0, "on water: " + p);
        }
    }

    @Test
    void reportsRejectionsWhenThereIsNoRoom() {
        GenSettings s = settings(100, Placement.GROUND, Layout.RANDOM);
        s.radius = 12;
        PortalPlanner.Session session = new PortalPlanner.Session(FakeTerrain.flat(64), s, 0, 0, List.of(), 1, new Random(1));
        while (!session.done()) session.step(100);
        assertTrue(session.placed().size() < 100);
        assertTrue(session.rejected() > 0);
    }
}
