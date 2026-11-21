package dev.portaldirector.gen;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers the height of each column while portals are being planned, so a column that many candidate spots touch is only read
 * from the world once. It is for planning only: building always reads the live world, and {@link #clear} frees the memory.
 */
public final class CachedTerrain implements Terrain {

    private final Terrain inner;
    private final Map<Long, Integer> tops = new HashMap<>();
    private final Map<Long, Integer> highest = new HashMap<>();
    private long hits;
    private long misses;

    public CachedTerrain(Terrain inner) {
        this.inner = inner;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public void clear() {
        tops.clear();
        highest.clear();
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    @Override
    public int minY() {
        return inner.minY();
    }

    @Override
    public int maxY() {
        return inner.maxY();
    }

    @Override
    public boolean isReady(int x, int z) {
        return inner.isReady(x, z);
    }

    @Override
    public int topY(int x, int z) {
        Integer cached = tops.get(key(x, z));

        if (cached != null) {
            hits++;
            return cached;
        }

        misses++;
        int value = inner.topY(x, z);
        tops.put(key(x, z), value);

        return value;
    }

    @Override
    public int highestY(int x, int z) {
        Integer cached = highest.get(key(x, z));

        if (cached != null) {
            hits++;
            return cached;
        }

        misses++;
        int value = inner.highestY(x, z);
        highest.put(key(x, z), value);

        return value;
    }

    @Override
    public boolean isLiquid(int x, int y, int z) {
        return inner.isLiquid(x, y, z);
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return inner.isAir(x, y, z);
    }

    @Override
    public boolean isNatural(int x, int y, int z) {
        return inner.isNatural(x, y, z);
    }
}
