package dev.portaldirector;

import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.GenSettings;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.Orientation;
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
        PortalPlanner.Session session = new PortalPlanner.Session(t, s, 0, 0, 0, 1, existing, firstId, new Random(seed));
        while (!session.done()) session.step(50);
        return session.placed();
    }

    private static GenSettings settings(int count, Placement placement, Layout layout) {
        GenSettings s = new GenSettings();
        s.count = count;
        s.placement = placement;
        s.layout = layout;
        s.radius = 100;
        s.spacing = 8;
        return s;
    }

    private static void assertNoOverlapAndGap(List<PortalSpec> portals, int gap) {
        for (int i = 0; i < portals.size(); i++) {
            for (int j = i + 1; j < portals.size(); j++) {
                assertTrue(portals.get(i).separatedFrom(portals.get(j), gap),
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
        for (Layout layout : Layout.values()) {
            GenSettings s = settings(40, Placement.MIXED, layout);
            s.spacing = 10;
            List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, 7);
            assertFalse(out.isEmpty(), layout.name());
            assertNoOverlapAndGap(out, 10);
        }
    }

    @Test
    void zeroSpacingStillLeavesOneBlockBetweenPortals() {
        GenSettings s = settings(60, Placement.GROUND, Layout.RANDOM);
        s.spacing = 0;
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
        s.orientation = Orientation.X;
        assertTrue(run(cliffs, s, List.of(), 1, 4).isEmpty());
    }

    @Test
    void groundPortalsDoNotRunIntoAHillside() {
        FakeTerrain hill = new FakeTerrain((x, z) -> z >= 10 ? 90 : 64);
        GenSettings s = settings(30, Placement.GROUND, Layout.RANDOM);
        s.radius = 40;
        s.orientation = Orientation.X;
        for (PortalSpec p : run(hill, s, List.of(), 1, 12)) {
            for (int[] c : p.corridorBlocks()) {
                if (c[1] == p.y() + 1) assertTrue(hill.topY(c[0], c[2]) <= p.y() + 1, "walkway of portal " + p.id() + " is inside the hill");
            }
        }
    }

    @Test
    void floatingPortalsHangInTheHeightRangeAboveTheGround() {
        GenSettings s = settings(30, Placement.AIR, Layout.RANDOM);
        s.heightMin = 25;
        s.heightMax = 80;
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
    void orientationIsHonoured() {
        GenSettings s = settings(20, Placement.GROUND, Layout.RANDOM);
        s.orientation = Orientation.X;
        assertTrue(run(FakeTerrain.flat(64), s, List.of(), 1, 1).stream().allMatch(p -> p.axis() == Axis.X));
        s.orientation = Orientation.Z;
        assertTrue(run(FakeTerrain.flat(64), s, List.of(), 1, 1).stream().allMatch(p -> p.axis() == Axis.Z));
        s.orientation = Orientation.RANDOM;
        List<PortalSpec> random = run(FakeTerrain.flat(64), s, List.of(), 1, 1);
        assertTrue(random.stream().anyMatch(p -> p.axis() == Axis.X));
        assertTrue(random.stream().anyMatch(p -> p.axis() == Axis.Z));
    }

    @Test
    void autoFacingPointsAtTheCenterForRandomAndCircle() {
        for (Layout layout : new Layout[]{Layout.RANDOM, Layout.CIRCLE}) {
            GenSettings s = settings(24, Placement.GROUND, layout);
            s.radius = 60;
            for (PortalSpec p : run(FakeTerrain.flat(64), s, List.of(), 1, 6)) {
                double along = p.stepX() * -p.centerX() + p.stepZ() * -p.centerZ();
                assertTrue(along >= -2, "portal " + p.id() + " should face the middle");
            }
        }
    }

    @Test
    void fixedFacingKeepsOneDirection() {
        GenSettings s = settings(16, Placement.GROUND, Layout.GRID);
        s.facing = FacingMode.EAST;
        s.radius = 60;
        List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, 1);
        assertFalse(out.isEmpty());
        assertTrue(out.stream().allMatch(p -> p.facing() == BlockFace.EAST && p.axis() == Axis.Z));
    }

    @Test
    void gridPortalsLineUp() {
        GenSettings s = settings(9, Placement.GROUND, Layout.GRID);
        s.orientation = Orientation.X;
        s.radius = 60;
        List<PortalSpec> out = run(FakeTerrain.flat(64), s, List.of(), 1, 1);
        assertEquals(9, out.size());
        Set<Integer> rows = new HashSet<>();
        out.forEach(p -> rows.add(p.z()));
        assertEquals(3, rows.size());
    }

    @Test
    void circleKeepsTheRadiusOfTheOuterRing() {
        GenSettings s = settings(12, Placement.GROUND, Layout.CIRCLE);
        s.radius = 50;
        for (PortalSpec p : run(FakeTerrain.flat(64), s, List.of(), 1, 1)) {
            double r = Math.hypot(p.centerX(), p.centerZ());
            assertTrue(Math.abs(r - 50) < 4, "radius " + r);
        }
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
        PortalPlanner.Session session = new PortalPlanner.Session(FakeTerrain.flat(64), s, 0, 0, 0, 1, List.of(), 1, new Random(1));
        while (!session.done()) session.step(100);
        assertTrue(session.placed().size() < 100);
        assertTrue(session.rejected() > 0);
    }
}
