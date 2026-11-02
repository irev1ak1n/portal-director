package dev.portaldirector.gen;

/** What the planner needs to know about the world. Implemented over Bukkit and by fakes in tests. */
public interface Terrain {

    int minY();

    /** Exclusive upper build limit. */
    int maxY();

    /** False when the column is in a chunk that is not loaded. */
    boolean isReady(int x, int z);

    /** Highest non-air block in the column, liquids included. {@code minY() - 1} when empty. */
    int topY(int x, int z);

    boolean isLiquid(int x, int y, int z);

    boolean isAir(int x, int y, int z);

    /** Air, plants, terrain or trees: safe to replace without wrecking something somebody built. */
    boolean isNatural(int x, int y, int z);
}
