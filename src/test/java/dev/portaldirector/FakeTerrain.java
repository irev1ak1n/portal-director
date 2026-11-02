package dev.portaldirector;

import dev.portaldirector.gen.Terrain;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntBinaryOperator;

/** A world made of a height function, with optional structure and water columns. */
final class FakeTerrain implements Terrain {

    private final IntBinaryOperator height;
    private final Set<Long> structures = new HashSet<>();
    private final Set<Long> liquid = new HashSet<>();

    FakeTerrain(IntBinaryOperator height) {
        this.height = height;
    }

    static FakeTerrain flat(int y) {
        return new FakeTerrain((x, z) -> y);
    }

    void structureAt(int x, int z) {
        structures.add(key(x, z));
    }

    void liquidAt(int x, int z) {
        liquid.add(key(x, z));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

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
        return height.applyAsInt(x, z);
    }

    @Override
    public boolean isLiquid(int x, int y, int z) {
        return liquid.contains(key(x, z)) && y == height.applyAsInt(x, z);
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return y > height.applyAsInt(x, z) && !structures.contains(key(x, z));
    }

    @Override
    public boolean isNatural(int x, int y, int z) {
        return !structures.contains(key(x, z));
    }
}
