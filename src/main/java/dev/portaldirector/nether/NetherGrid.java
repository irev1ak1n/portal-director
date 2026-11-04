package dev.portaldirector.nether;

/** What the Nether site search needs to know about the world. Implemented over Bukkit and by fakes in tests. */
public interface NetherGrid {

    /** What a block is, for the purpose of fitting a portal in. */
    enum Cell {
        /** Open space (air, fire). */
        AIR,
        /** A plant or fungus that is fine to clear, but is no floor. */
        PLANT,
        /** Natural solid ground (netherrack, basalt, blackstone, soul sand...): a floor, and fine to carve. */
        SOLID,
        /** Lava or water. */
        LIQUID,
        /** Anything else: portals, obsidian, structures, chests, bedrock, ancient debris. Never touched. */
        BLOCKED
    }

    int minY();

    /** Exclusive upper limit. */
    int maxY();

    /** False when the column's chunk is not loaded. */
    boolean isReady(int x, int z);

    Cell cell(int x, int y, int z);
}
