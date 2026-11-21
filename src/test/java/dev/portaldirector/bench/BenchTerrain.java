package dev.portaldirector.bench;

import dev.portaldirector.gen.Terrain;

/** Flat ground at y 64, always loaded. Used only to time the planners. */
final class BenchTerrain implements Terrain {

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
        return 64;
    }

    @Override
    public boolean isLiquid(int x, int y, int z) {
        return false;
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return y > 64;
    }

    @Override
    public boolean isNatural(int x, int y, int z) {
        return true;
    }
}
