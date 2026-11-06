package dev.portaldirector.nether;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.Set;

/** {@link NetherGrid} over a real Nether world. Only reads loaded chunks. */
public final class BukkitNetherGrid implements NetherGrid {

    private static final Set<Material> NATURAL = Set.of(
            Material.NETHERRACK, Material.BASALT, Material.SMOOTH_BASALT, Material.BLACKSTONE, Material.SOUL_SAND,
            Material.SOUL_SOIL, Material.GRAVEL, Material.GLOWSTONE, Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE,
            Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM, Material.NETHER_WART_BLOCK, Material.WARPED_WART_BLOCK,
            Material.SHROOMLIGHT, Material.CRIMSON_STEM, Material.WARPED_STEM, Material.BONE_BLOCK);

    private static final Set<Material> PLANTS = Set.of(
            Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS,
            Material.NETHER_SPROUTS, Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT, Material.TWISTING_VINES,
            Material.TWISTING_VINES_PLANT, Material.NETHER_WART, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM);

    private final World world;

    public BukkitNetherGrid(World world) {
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
    public Cell cell(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return Cell.BLOCKED;
        Material m = world.getBlockAt(x, y, z).getType();
        if (m.isAir() || m == Material.FIRE || m == Material.SOUL_FIRE) return Cell.AIR;
        if (m == Material.LAVA || m == Material.WATER) return Cell.LIQUID;
        if (NATURAL.contains(m)) return Cell.SOLID;
        if (PLANTS.contains(m)) return Cell.PLANT;
        return Cell.BLOCKED;
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        Material m = world.getBlockAt(x, y, z).getType();
        return m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
                || m == Material.WITHER_ROSE || m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW;
    }
}
