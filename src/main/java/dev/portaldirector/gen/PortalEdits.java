package dev.portaldirector.gen;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;

import java.util.ArrayList;
import java.util.List;

/** The block changes that turn a {@link PortalSpec} into a real portal. */
public final class PortalEdits {

    public record Edit(int x, int y, int z, BlockData data) {
    }

    private PortalEdits() {
    }

    /** Places one edit and logs what was there, so the scene can be removed safely later. */
    public static void apply(World world, dev.portaldirector.scene.BlockLog log, Edit e) {
        var b = world.getBlockAt(e.x(), e.y(), e.z());
        String placed = e.data().getAsString();
        String original = b.getBlockData().getAsString();
        if (original.equals(placed) && !log.has(e.x(), e.y(), e.z())) return;
        log.record(e.x(), e.y(), e.z(), original, placed);
        b.setBlockData(e.data(), false);
    }

    /** Support under the frame, a cleared walkway, the obsidian frame and finally the activated interior. */
    public static List<Edit> forPortal(World world, PortalSpec p, int maxSupport) {
        List<Edit> edits = new ArrayList<>();
        BukkitTerrain terrain = new BukkitTerrain(world);
        BlockData air = Material.AIR.createBlockData();
        if (!p.floating()) {
            for (int i = 0; i < PortalSpec.WIDTH; i++) {
                int x = p.cellX(i);
                int z = p.cellZ(i);
                int top = terrain.topY(x, z);
                if (top < world.getMinHeight() || p.y() - 1 - top > maxSupport) continue;
                BlockData fill = supportFor(world.getBlockAt(x, top, z).getType());
                for (int y = top + 1; y < p.y(); y++) edits.add(new Edit(x, y, z, fill));
            }
        }
        for (int[] c : p.corridorBlocks()) {
            if (!world.getBlockAt(c[0], c[1], c[2]).getType().isAir()) edits.add(new Edit(c[0], c[1], c[2], air));
        }
        BlockData obsidian = Material.OBSIDIAN.createBlockData();
        for (int[] c : p.frameBlocks()) edits.add(new Edit(c[0], c[1], c[2], obsidian));
        Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
        portal.setAxis(p.axis());
        for (int[] c : p.interiorBlocks()) edits.add(new Edit(c[0], c[1], c[2], portal));
        return edits;
    }

    private static BlockData supportFor(Material top) {
        String n = top.name();
        if (!top.isSolid() || top.isAir() || n.equals("GRASS_BLOCK") || n.equals("PODZOL") || n.equals("MYCELIUM")
                || n.equals("DIRT_PATH") || n.equals("FARMLAND") || n.equals("ROOTED_DIRT") || n.endsWith("_LEAVES")
                || n.endsWith("_LOG") || n.equals("SNOW_BLOCK") || n.equals("SNOW")) {
            return Material.DIRT.createBlockData();
        }
        return top.createBlockData();
    }
}
