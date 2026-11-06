package dev.portaldirector;

import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.nether.NetherGrid;
import dev.portaldirector.nether.NetherSite;
import dev.portaldirector.sequence.ArrivalSpots;
import dev.portaldirector.sequence.FloatingRun;
import dev.portaldirector.sequence.SeqOption;
import dev.portaldirector.sequence.SeqSettings;
import dev.portaldirector.sequence.SeqTimeline;
import dev.portaldirector.sequence.SkyPlanner;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SequenceTest {

    @TempDir
    File dir;

    // Settings

    @Test
    void optionsCheckTheirValuesAndSayWhatIsAllowed() {
        SeqOption radius = SeqOption.whole("radius", 100, 10, 400, "x");
        assertEquals("120", radius.normalize(" 120 "));
        assertThrows(IllegalArgumentException.class, () -> radius.normalize("5"));
        assertThrows(IllegalArgumentException.class, () -> radius.normalize("500"));
        assertThrows(IllegalArgumentException.class, () -> radius.normalize("wide"));
        SeqOption delay = SeqOption.decimal("portal-delay-min", 0.15, 0.05, 30, "x");
        assertEquals("0.3", delay.normalize("0.3"));
        assertThrows(IllegalArgumentException.class, () -> delay.normalize("0"));
        SeqOption mode = SeqOption.choice("tnt-mode", "safe", List.of("real", "safe"), "x");
        assertEquals("real", mode.normalize("REAL"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> mode.normalize("loud")).getMessage().contains("real, safe"));
    }

    @Test
    void sequenceSettingsAreSavedPerSequenceAndOnlyWhenChanged() {
        File f = new File(dir, "sequences.yml");
        SeqSettings s = new SeqSettings(f, Logger.getLogger("test"));
        SeqOption radius = SeqOption.whole("radius", 100, 10, 400, "x");
        SeqOption group = SeqOption.text("group", "", "x");
        assertEquals("100", s.get("sky-army", radius));
        s.set("sky-army", radius, "150");
        s.set("tnt-strike", group, "NULL");
        assertEquals("150", s.get("sky-army", radius));
        assertEquals("100", s.get("tnt-strike", radius), "another sequence keeps its own value");
        SeqSettings back = new SeqSettings(f, Logger.getLogger("test"));
        back.load();
        assertEquals("150", back.get("sky-army", radius));
        assertEquals("NULL", back.get("tnt-strike", group));
        back.set("sky-army", radius, "100");
        assertFalse(back.isChanged("sky-army", radius), "a value equal to the default is not stored");
        back.reset("tnt-strike");
        assertEquals("", back.get("tnt-strike", group));
        assertEquals("100", back.get("sky-army", radius));
    }

    // Sky planning

    private static SkyPlanner.Run plan(FakeTerrain t, int cy, int need, int radius, int halfHeight, int maxRadius, long seed) {
        SkyPlanner.Run run = new SkyPlanner.Run(t, 0, cy, 0, need, List.of(), 1, radius, halfHeight, maxRadius, new Random(seed));
        int guard = 0;
        while (!run.done() && guard++ < 100000) run.step(80);
        assertTrue(run.done());
        return run;
    }

    private static void assertValid(List<PortalSpec> placed, FakeTerrain t, int cy, int halfHeight) {
        assertEquals(placed.size(), placed.stream().map(PortalSpec::id).distinct().count(), "unique ids");
        for (int i = 0; i < placed.size(); i++) {
            PortalSpec p = placed.get(i);
            assertTrue(p.floating());
            assertTrue(p.y() >= cy - halfHeight && p.y() <= cy + halfHeight, "y " + p.y() + " is outside the band around " + cy);
            assertTrue(p.y() >= t.minY() + 1 && p.y() + PortalSpec.HEIGHT <= t.maxY() - 1, "inside the world's build limits");
            for (int j = i + 1; j < placed.size(); j++) {
                assertTrue(p.separatedFrom(placed.get(j), 1, 1), "portals " + p.id() + " and " + placed.get(j).id() + " touch or overlap");
            }
        }
    }

    @Test
    void tenTwentyAndFortyPlayersEachGetAPortal() {
        for (int n : new int[]{10, 20, 40}) {
            FakeTerrain t = FakeTerrain.flat(64);
            SkyPlanner.Run run = plan(t, 120, n, 100, 30, 300, n);
            assertEquals(n, run.placed().size(), n + " players need " + n + " portals");
            assertValid(run.placed(), t, 120, 30);
            assertTrue(run.notes().isEmpty(), "nothing needs widening for " + n + " in radius 100");
            Set<String> faces = new HashSet<>();
            run.placed().forEach(p -> faces.add(p.facing().name()));
            if (n >= 20) assertEquals(4, faces.size(), "random facing uses all four directions");
        }
    }

    @Test
    void whenTheAreaIsTooSmallItWidensInsteadOfLeavingPlayersOut() {
        FakeTerrain t = FakeTerrain.flat(64);
        SkyPlanner.Run run = plan(t, 120, 40, 14, 10, 300, 7);
        assertEquals(40, run.placed().size());
        assertValid(run.placed(), t, 120, 20);
        assertFalse(run.notes().isEmpty(), "it says what it widened");
        assertTrue(run.stage().radius() > 14 || run.stage().halfHeight() > 10 || run.stage().spacingH() < 8);
    }

    @Test
    void eachWideningStepKeepsWhatWasAlreadyPlaced() {
        FakeTerrain t = FakeTerrain.flat(64);
        SkyPlanner.Run run = plan(t, 120, 40, 14, 10, 300, 11);
        List<PortalSpec> p = run.placed();
        for (int i = 0; i < p.size(); i++) {
            for (int j = i + 1; j < p.size(); j++) assertTrue(p.get(i).separatedFrom(p.get(j), 1, 1));
        }
        assertEquals(40, p.size());
    }

    @Test
    void anAreaThatNeverFitsEveryoneSharesPortalsEvenly() {
        FakeTerrain t = FakeTerrain.flat(64);
        SkyPlanner.Run run = plan(t, 120, 40, 10, 5, 10, 3);
        int fit = run.placed().size();
        assertTrue(fit >= 1 && fit < 40, "only " + fit + " fit in a radius of 10");
        int[] a = SkyPlanner.assign(40, fit, new Random(5));
        int[] load = new int[fit];
        for (int portal : a) {
            assertTrue(portal >= 0 && portal < fit);
            load[portal]++;
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int l : load) {
            min = Math.min(min, l);
            max = Math.max(max, l);
        }
        assertTrue(max - min <= 1, "players are shared evenly");
        assertEquals(40, java.util.Arrays.stream(load).sum(), "no player is missing or counted twice");
    }

    @Test
    void withEnoughPortalsEveryPlayerHasADifferentOne() {
        int[] a = SkyPlanner.assign(40, 40, new Random(1));
        assertEquals(40, java.util.Arrays.stream(a).distinct().count());
    }

    @Test
    void structuresAndTheWorldTopAreRespected() {
        FakeTerrain t = FakeTerrain.flat(64);
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) t.structureAt(x, z);
        }
        SkyPlanner.Run run = plan(t, 300, 25, 60, 40, 200, 9);
        // the band reaches past the world top: floors stop where a whole frame still fits under it
        for (PortalSpec p : run.placed()) {
            assertTrue(p.y() + PortalSpec.HEIGHT <= t.maxY() - 1, "frame top " + (p.y() + PortalSpec.HEIGHT));
            int[] b = p.box();
            for (int x = b[0]; x <= b[3]; x++) {
                for (int z = b[2]; z <= b[5]; z++) assertFalse(x >= -20 && x <= 20 && z >= -20 && z <= 20, "inside the structure columns");
            }
        }
        assertFalse(run.placed().isEmpty());
    }

    @Test
    void standingPlacesAreInsideThePortalAndDifferent() {
        for (Axis axis : new Axis[]{Axis.X, Axis.Z}) {
            BlockFace f = axis == Axis.X ? BlockFace.SOUTH : BlockFace.EAST;
            PortalSpec p = new PortalSpec(1, 10, 100, 10, axis, f, true);
            double[] solo = SkyPlanner.stand(p, 0, 1);
            assertEquals(p.centerX(), solo[0], 1e-9);
            assertEquals(p.centerZ(), solo[1], 1e-9);
            var box = p.interiorBox();
            Set<String> seen = new HashSet<>();
            for (int k = 0; k < 6; k++) {
                double[] at = SkyPlanner.stand(p, k, 6);
                assertTrue(at[0] > box.getMinX() && at[0] < box.getMaxX() + 1e-9 && at[1] > box.getMinZ() && at[1] < box.getMaxZ() + 1e-9,
                        "slot " + k + " is inside the purple interior: " + at[0] + " " + at[1]);
                assertTrue(seen.add(at[0] + "," + at[1]), "slot " + k + " is its own place");
            }
        }
    }

    // Timeline

    @Test
    void portalsAppearAtTheStartThenAtRandomGapsInsideTheRange() {
        SeqTimeline.Range gap = SeqTimeline.Range.seconds(0.15, 0.55);
        int[] ticks = SeqTimeline.portalTicks(40, gap, new Random(2));
        assertEquals(0, ticks[0]);
        Set<Integer> gaps = new HashSet<>();
        for (int i = 1; i < ticks.length; i++) {
            int g = ticks[i] - ticks[i - 1];
            assertTrue(g >= gap.min() && g <= gap.max(), "gap " + g);
            gaps.add(g);
        }
        assertTrue(gaps.size() >= 4, "gaps vary: " + gaps);
    }

    @Test
    void aPlayerNeverAppearsBeforeTheirPortalAndTimesVary() {
        int portals = 40;
        int[] portalTick = SeqTimeline.portalTicks(portals, SeqTimeline.Range.seconds(0.15, 0.55), new Random(4));
        int[] playerPortal = SkyPlanner.assign(40, portals, new Random(4));
        SeqTimeline.Range delay = SeqTimeline.Range.seconds(0.1, 0.5);
        List<SeqTimeline.Event> events = SeqTimeline.sky(portalTick, playerPortal, delay, new Random(4));
        assertEquals(portals + 40, events.size());
        Map<Integer, Integer> portalAt = new HashMap<>();
        Set<Integer> offsets = new HashSet<>();
        Set<Integer> seenPlayers = new HashSet<>();
        int last = -1;
        for (SeqTimeline.Event e : events) {
            assertTrue(e.tick() >= last, "events are in order");
            last = e.tick();
            if (e.kind() == SeqTimeline.Kind.PORTAL) {
                portalAt.put(e.index(), e.tick());
            } else {
                assertTrue(seenPlayers.add(e.index()), "player " + e.index() + " appears once");
                Integer builtAt = portalAt.get(playerPortal[e.index()]);
                assertNotNull(builtAt, "the portal exists before player " + e.index() + " appears");
                int offset = e.tick() - builtAt;
                assertTrue(offset >= delay.min() && offset <= delay.max(), "offset " + offset);
                offsets.add(offset);
            }
        }
        assertEquals(40, seenPlayers.size());
        assertTrue(offsets.size() >= 4, "player delays vary: " + offsets);
    }

    @Test
    void everyPortalFiresItsOwnNumberOfMinecartsStaggeredAfterItAppears() {
        int portals = 30;
        Random rnd = new Random(8);
        int[] counts = new int[portals];
        Set<Integer> distinct = new HashSet<>();
        for (int i = 0; i < portals; i++) {
            counts[i] = SeqTimeline.between(rnd, 5, 10);
            assertTrue(counts[i] >= 5 && counts[i] <= 10);
            distinct.add(counts[i]);
        }
        assertTrue(distinct.size() >= 4, "counts differ from portal to portal: " + distinct);
        int[] portalTick = SeqTimeline.portalTicks(portals, SeqTimeline.Range.seconds(0.15, 0.55), rnd);
        SeqTimeline.Range launch = SeqTimeline.Range.seconds(0.2, 0.7);
        List<SeqTimeline.Event> events = SeqTimeline.tnt(portalTick, counts, launch, rnd);
        int[] fired = new int[portals];
        int[] lastAt = new int[portals];
        for (int i = 0; i < portals; i++) lastAt[i] = portalTick[i];
        Set<Integer> gaps = new HashSet<>();
        int last = -1;
        for (SeqTimeline.Event e : events) {
            assertTrue(e.tick() >= last);
            last = e.tick();
            if (e.kind() == SeqTimeline.Kind.LAUNCH) {
                int gap = e.tick() - lastAt[e.index()];
                assertTrue(gap >= launch.min() && gap <= launch.max(), "gap " + gap);
                gaps.add(gap);
                lastAt[e.index()] = e.tick();
                fired[e.index()]++;
            }
        }
        assertArrayEquals(counts, fired);
        assertTrue(gaps.size() >= 5, "launch gaps vary: " + gaps);
    }

    @Test
    void theChunkMathStaysWithinTheLimit() {
        assertTrue(FloatingRun.chunksFor(100) < FloatingRun.chunksFor(200));
        int r = FloatingRun.maxRadiusFor(900);
        assertTrue(FloatingRun.chunksFor(r) <= 900);
        assertTrue(r >= 100 && r <= 400);
    }

    // Waiting places in the Nether

    private static final class Cave implements NetherGrid {
        final Set<Long> lava = new HashSet<>();
        final Set<Long> hazard = new HashSet<>();
        final Set<Long> wall = new HashSet<>();
        int floor = 60;

        static long k(int x, int y, int z) {
            return ((long) x * 73856093L) ^ ((long) y * 19349663L) ^ ((long) z * 83492791L);
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 128;
        }

        @Override
        public boolean isReady(int x, int z) {
            return true;
        }

        @Override
        public Cell cell(int x, int y, int z) {
            if (wall.contains(k(x, y, z))) return Cell.BLOCKED;
            if (lava.contains(k(x, y, z))) return Cell.LIQUID;
            if (y < floor) return Cell.SOLID;
            if (y > floor + 30) return Cell.SOLID;
            return Cell.AIR;
        }

        @Override
        public boolean isHazard(int x, int y, int z) {
            return hazard.contains(k(x, y, z));
        }
    }

    private static PortalSpec portalIn(Cave c) {
        PortalSpec p = NetherSite.find(c, 1, 0, 0, 61, Axis.X, BlockFace.SOUTH, List.of(), 8);
        assertNotNull(p);
        return p;
    }

    @Test
    void fortyPlayersGetFortyDifferentSafeBlocksNearThePortal() {
        Cave c = new Cave();
        PortalSpec p = portalIn(c);
        List<ArrivalSpots.Spot> spots = ArrivalSpots.find(c, p, 5, 40, List.of());
        assertEquals(40, spots.size());
        assertEquals(40, new HashSet<>(spots).size(), "every player has a block of their own");
        int[] box = p.box();
        for (ArrivalSpots.Spot s : spots) {
            assertEquals(NetherGrid.Cell.SOLID, c.cell(s.x(), s.y() - 1, s.z()), "ground under the feet");
            assertEquals(NetherGrid.Cell.AIR, c.cell(s.x(), s.y(), s.z()));
            assertEquals(NetherGrid.Cell.AIR, c.cell(s.x(), s.y() + 1, s.z()));
            boolean insidePortal = s.x() >= box[0] - 1 && s.x() <= box[3] + 1 && s.z() >= box[2] - 1 && s.z() <= box[5] + 1;
            assertFalse(insidePortal, "not in or against the portal frame");
        }
        double nearest = spots.stream().mapToDouble(s -> Math.hypot(s.x() + 0.5 - p.centerX(), s.z() + 0.5 - p.centerZ())).min().orElse(0);
        assertTrue(nearest >= 3 && nearest <= 8, "the first places are about the asked distance away: " + nearest);
    }

    @Test
    void lavaFireAndMissingFloorsAreAvoided() {
        Cave c = new Cave();
        PortalSpec p = portalIn(c);
        for (int x = -30; x <= 30; x++) {
            for (int z = 3; z <= 12; z++) c.lava.add(Cave.k(x, c.floor - 1, z));
        }
        for (int x = -6; x <= 6; x++) {
            for (int z = -12; z <= -3; z++) c.hazard.add(Cave.k(x, c.floor, z));
        }
        List<ArrivalSpots.Spot> spots = ArrivalSpots.find(c, p, 5, 30, List.of());
        assertFalse(spots.isEmpty());
        for (ArrivalSpots.Spot s : spots) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) assertNotEquals(NetherGrid.Cell.LIQUID, c.cell(s.x() + dx, s.y() - 1, s.z() + dz), "lava next to " + s);
            }
            assertFalse(c.isHazard(s.x(), s.y(), s.z()), "fire at " + s);
        }
    }

    @Test
    void aCrampedCaveGivesFewerPlacesAndSaysSoByReturningFewer() {
        Cave c = new Cave();
        PortalSpec p = portalIn(c);
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                if (Math.abs(x) + Math.abs(z) > 4) {
                    for (int y = c.floor; y < c.floor + 3; y++) c.wall.add(Cave.k(x, y, z));
                }
            }
        }
        List<ArrivalSpots.Spot> spots = ArrivalSpots.find(c, p, 5, 40, List.of());
        assertTrue(spots.size() < 40);
    }

    @Test
    void anotherPortalsSurroundingsAreLeftAlone() {
        Cave c = new Cave();
        PortalSpec p = portalIn(c);
        PortalSpec other = new PortalSpec(2, 12, 61, 4, Axis.X, BlockFace.SOUTH, true);
        List<ArrivalSpots.Spot> spots = ArrivalSpots.find(c, p, 5, 40, List.of(other));
        int[] b = other.box();
        for (ArrivalSpots.Spot s : spots) {
            assertFalse(s.x() >= b[0] - 2 && s.x() <= b[3] + 2 && s.z() >= b[2] - 2 && s.z() <= b[5] + 2, "too close to another portal: " + s);
        }
    }

    @Test
    void theyLookAtThePortal() {
        // standing south of the portal at z=10 and looking at z=0 means looking north: yaw 180
        assertEquals(180f, Math.abs(ArrivalSpots.yawToward(0, 10, 0, 0)), 0.01f);
        // standing east (x=10) looking west: yaw 90 in Minecraft's convention
        assertEquals(90f, ArrivalSpots.yawToward(10, 0, 0, 0), 0.01f);
        assertTrue(new ArrayList<>(List.of(1)).size() == 1);
    }
}
