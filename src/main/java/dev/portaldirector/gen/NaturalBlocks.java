package dev.portaldirector.gen;

import org.bukkit.Material;

import java.util.Set;

/**
 * Decides whether a block may be replaced without destroying something a player built.
 * Anything not recognised as terrain, plants or trees counts as a structure and is left alone.
 */
public final class NaturalBlocks {

    private static final Set<String> TERRAIN = Set.of(
            "STONE", "GRANITE", "DIORITE", "ANDESITE", "DEEPSLATE", "TUFF", "CALCITE", "DRIPSTONE_BLOCK",
            "DIRT", "GRASS_BLOCK", "COARSE_DIRT", "PODZOL", "MYCELIUM", "ROOTED_DIRT", "MUD", "CLAY", "GRAVEL",
            "SAND", "RED_SAND", "SANDSTONE", "RED_SANDSTONE", "SNOW", "SNOW_BLOCK", "ICE", "PACKED_ICE",
            "BLUE_ICE", "POWDER_SNOW", "WATER", "NETHERRACK", "BASALT", "BLACKSTONE", "SOUL_SAND", "SOUL_SOIL",
            "TERRACOTTA", "WHITE_TERRACOTTA", "ORANGE_TERRACOTTA", "YELLOW_TERRACOTTA", "BROWN_TERRACOTTA",
            "RED_TERRACOTTA", "LIGHT_GRAY_TERRACOTTA", "MOSS_BLOCK", "MOSS_CARPET", "PALE_MOSS_BLOCK",
            "PALE_MOSS_CARPET", "AZALEA", "FLOWERING_AZALEA", "MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS",
            "SUGAR_CANE", "CACTUS", "BAMBOO", "VINE", "GLOW_LICHEN", "COBWEB", "FIREFLY_BUSH", "BUSH",
            "SHORT_DRY_GRASS", "TALL_DRY_GRASS", "LEAF_LITTER", "WILDFLOWERS", "CACTUS_FLOWER", "SNOW_LAYER");

    private static final String[] SUFFIXES = {
            "_ORE", "_LEAVES", "_LOG", "_WOOD", "_SAPLING", "_FLOWER", "_TULIP", "_ROOTS", "_FUNGUS",
            "_MUSHROOM", "_BUSH", "_PLANT", "_VINES", "_MUSHROOM_BLOCK"};

    private static final String[] NAMES = {
            "GRASS", "FERN", "KELP", "SEAGRASS", "DANDELION", "POPPY", "ORCHID", "ALLIUM", "AZURE_BLUET",
            "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "LILAC", "ROSE_BUSH", "PEONY", "SUNFLOWER",
            "DEAD_BUSH", "LILY_PAD", "SWEET_BERRY_BUSH", "TORCHFLOWER", "CLOSED_EYEBLOSSOM", "OPEN_EYEBLOSSOM"};

    private NaturalBlocks() {
    }

    public static boolean isNatural(Material m) {
        if (m.isAir()) return true;
        String n = m.name();
        if (TERRAIN.contains(n)) return true;
        for (String s : SUFFIXES) {
            if (n.endsWith(s)) return !n.startsWith("POTTED_") && !n.startsWith("STRIPPED_");
        }
        for (String s : NAMES) {
            if (n.equals(s) || n.endsWith("_" + s)) return !n.startsWith("POTTED_");
        }
        return false;
    }
}
