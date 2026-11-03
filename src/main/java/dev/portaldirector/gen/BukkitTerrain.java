package dev.portaldirector.gen;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** {@link Terrain} over a real world. Only reads loaded chunks. */
public final class BukkitTerrain implements Terrain {

    private final World world;

    public BukkitTerrain(World world) {
        this.world = world;
    }

    @Override
    public int minY() {
        return world.getMinHeight();
    }

    @Override
    public int maxY() {
        return world.getMaxHeight();
    }

    @Override
    public boolean isReady(int x, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public int topY(int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int min = world.getMinHeight();
        while (y > min) {
            Block b = world.getBlockAt(x, y, z);
            Material m = b.getType();
            boolean treeOrPlant = m.name().endsWith("_LOG") || m.name().endsWith("_WOOD")
                    || m.name().endsWith("_LEAVES") || (b.isPassable() && !b.isLiquid() && !m.isAir());
            if (!treeOrPlant) break;
            y--;
        }
        return world.getBlockAt(x, y, z).getType().isAir() ? min - 1 : y;
    }

    @Override
    public int highestY(int x, int z) {
        return Math.max(topY(x, z), world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE));
    }

    @Override
    public boolean isLiquid(int x, int y, int z) {
        return world.getBlockAt(x, y, z).isLiquid();
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isAir();
    }

    @Override
    public boolean isNatural(int x, int y, int z) {
        return NaturalBlocks.isNatural(world.getBlockAt(x, y, z).getType());
    }
}
