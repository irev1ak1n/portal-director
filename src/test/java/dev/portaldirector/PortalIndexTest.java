package dev.portaldirector;

import dev.portaldirector.gen.CachedTerrain;
import dev.portaldirector.gen.PortalIndex;
import dev.portaldirector.gen.Terrain;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.util.Metrics;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PortalIndexTest {

    private static PortalSpec random(Random rnd, int id, int range) {
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        BlockFace face = faces[rnd.nextInt(4)];
        Axis axis = face == BlockFace.NORTH || face == BlockFace.SOUTH ? Axis.X : Axis.Z;

        return new PortalSpec(id, rnd.nextInt(range) - range / 2, 60 + rnd.nextInt(80), rnd.nextInt(range) - range / 2, axis, face, rnd.nextBoolean());
    }

    private static boolean bruteClear(List<PortalSpec> all, PortalSpec c, int gapH, int gapV) {
        for (PortalSpec o : all) {
            if (!c.separatedFrom(o, gapH, gapV)) return false;
        }

        return true;
    }

    @Test
    void theIndexAnswersExactlyLikeALoopOverEveryPortal() {
        int[][] gaps = {{8, 5}, {1, 1}, {2, 2}, {20, 10}, {40, 3}};

        for (long seed = 1; seed <= 6; seed++) {
            Random rnd = new Random(seed);
            List<PortalSpec> all = new ArrayList<>();

            for (int i = 1; i <= 250; i++) all.add(random(rnd, i, 500));

            PortalIndex index = new PortalIndex(all);

            for (int i = 0; i < 1500; i++) {
                PortalSpec candidate = random(rnd, 1000 + i, 560);

                for (int[] g : gaps) {
                    assertEquals(bruteClear(all, candidate, g[0], g[1]), index.isClear(candidate, g[0], g[1]), "seed " + seed + " candidate " + i + " gaps " + g[0] + "/" + g[1]);
                }
            }
        }
    }

    @Test
    void theClashItNamesReallyClashes() {
        Random rnd = new Random(9);
        List<PortalSpec> all = new ArrayList<>();

        for (int i = 1; i <= 200; i++) all.add(random(rnd, i, 300));

        PortalIndex index = new PortalIndex(all);

        for (int i = 0; i < 800; i++) {
            PortalSpec candidate = random(rnd, 5000 + i, 340);
            PortalSpec clash = index.firstClash(candidate, 1);

            assertEquals(!bruteClear(all, candidate, 1, 1), clash != null);

            if (clash != null) assertFalse(candidate.separatedFrom(clash, 1));
        }
    }

    @Test
    void portalsAddedLaterAreSeenAtOnce() {
        PortalIndex index = new PortalIndex(List.of());
        PortalSpec first = new PortalSpec(1, 0, 100, 0, Axis.X, BlockFace.SOUTH, true);
        PortalSpec nextDoor = new PortalSpec(2, 2, 100, 0, Axis.X, BlockFace.SOUTH, true);

        assertTrue(index.isClear(nextDoor, 8, 5));

        index.add(first);

        assertFalse(index.isClear(nextDoor, 8, 5));
        assertEquals(1, index.size());
    }

    @Test
    void aPortalSpanningTwoCellsIsFoundFromEitherSide() {
        PortalSpec onTheEdge = new PortalSpec(1, 62, 100, 0, Axis.X, BlockFace.SOUTH, true);
        PortalIndex index = new PortalIndex(List.of(onTheEdge));

        assertTrue(index.contains(onTheEdge.x(), onTheEdge.y(), onTheEdge.z()));
        assertTrue(index.contains(onTheEdge.x() + 3, onTheEdge.y() + 2, onTheEdge.z()));
        assertFalse(index.isClear(new PortalSpec(2, 66, 100, 0, Axis.X, BlockFace.SOUTH, true), 1, 1));
        assertFalse(index.isClear(new PortalSpec(3, 58, 100, 0, Axis.X, BlockFace.SOUTH, true), 1, 1));
    }

    @Test
    void containsMatchesTheBoxOfEveryPortal() {
        Random rnd = new Random(4);
        List<PortalSpec> all = new ArrayList<>();

        for (int i = 1; i <= 150; i++) all.add(random(rnd, i, 300));

        PortalIndex index = new PortalIndex(all);

        for (int i = 0; i < 4000; i++) {
            int x = rnd.nextInt(320) - 160;
            int y = 55 + rnd.nextInt(100);
            int z = rnd.nextInt(320) - 160;
            boolean expected = all.stream().anyMatch(p -> p.contains(x, y, z));

            assertEquals(expected, index.contains(x, y, z), x + " " + y + " " + z);
        }
    }

    // Column cache

    private static final class CountingTerrain implements Terrain {

        int topCalls;
        int highestCalls;

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }

        @Override
        public boolean isReady(int x, int z) {
            return true;
        }

        @Override
        public int topY(int x, int z) {
            topCalls++;

            return 60 + (x + z) % 5;
        }

        @Override
        public int highestY(int x, int z) {
            highestCalls++;

            return topY(x, z) + 7;
        }

        @Override
        public boolean isLiquid(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isNatural(int x, int y, int z) {
            return true;
        }
    }

    @Test
    void aColumnIsReadFromTheWorldOnlyOnce() {
        CountingTerrain world = new CountingTerrain();
        CachedTerrain cache = new CachedTerrain(world);

        for (int i = 0; i < 50; i++) {
            assertEquals(63, cache.topY(3, 0));
            assertEquals(70, cache.highestY(3, 0));
        }

        assertEquals(2, world.topCalls, "once for topY and once inside highestY");
        assertEquals(1, world.highestCalls);
        assertEquals(98, cache.hits());
    }

    @Test
    void theCacheGivesTheSameAnswersAsTheWorld() {
        CountingTerrain world = new CountingTerrain();
        CachedTerrain cache = new CachedTerrain(world);
        Random rnd = new Random(2);

        for (int i = 0; i < 2000; i++) {
            int x = rnd.nextInt(40) - 20;
            int z = rnd.nextInt(40) - 20;

            assertEquals(new CountingTerrain().topY(x, z), cache.topY(x, z));
            assertEquals(new CountingTerrain().highestY(x, z), cache.highestY(x, z));
        }
    }

    @Test
    void clearingTheCacheReadsTheWorldAgain() {
        CountingTerrain world = new CountingTerrain();
        CachedTerrain cache = new CachedTerrain(world);

        cache.topY(1, 1);
        cache.clear();
        cache.topY(1, 1);

        assertEquals(2, world.topCalls);
    }

    // Metrics

    @Test
    void timersAndCountersAddUpAndAreListedAToZ() {
        Metrics m = new Metrics();
        m.record("b.timer", 4_000_000);
        m.record("b.timer", 2_000_000);
        m.record("a.timer", 1_000_000);
        m.count("z.count");
        m.count("a.count", 5);

        assertEquals(2, m.timing("b.timer").count());
        assertEquals(3.0, m.timing("b.timer").meanMillis(), 1e-9);
        assertEquals(4.0, m.timing("b.timer").maxMillis(), 1e-9);
        assertEquals(0, m.timing("none").count());
        assertEquals(List.of("a.timer", "b.timer"), new ArrayList<>(m.timings().keySet()));
        assertEquals(List.of("a.count", "z.count"), new ArrayList<>(m.counters().keySet()));
        assertEquals(5, m.counter("a.count"));

        m.reset();

        assertEquals(0, m.counter("a.count"));
    }

    @Test
    void metricsCanBeUsedFromSeveralThreadsAtOnce() throws InterruptedException {
        Metrics m = new Metrics();
        List<Thread> threads = new ArrayList<>();

        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 5000; i++) {
                    m.record("x", 10);
                    m.count("y");
                }
            });

            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads) thread.join();

        assertEquals(40_000, m.timing("x").count());
        assertEquals(40_000, m.counter("y"));
    }
}
