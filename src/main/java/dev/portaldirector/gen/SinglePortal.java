package dev.portaldirector.gen;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Building one named portal at exact coordinates: the checks, the chunk loading and the build. Used by /pd create portal and by sequences. */
public final class SinglePortal {

    private SinglePortal() {
    }

    /** Everything that can be checked before touching the world; throws with a plain reason. */
    public static PortalSpec check(PortalDirectorPlugin pl, World world, Scene scene, String name, int x, int y, int z, BlockFace facing) {
        if (!scene.portals().isEmpty() && !scene.worldName().equals(world.getName())) {
            throw new IllegalArgumentException("The existing portals are in world " + scene.worldName() + ". Run /pd clear first to build in " + world.getName() + ".");
        }
        scene.checkName(name);
        if (scene.portals().size() >= pl.settings().maxPortals) {
            throw new IllegalArgumentException("There are already " + pl.settings().maxPortals + " portals, the most allowed. /pd clear removes them.");
        }
        PortalSpec probe = PortalSpec.fromAnchor(scene.nextId(), x, y, z, facing, false);
        if (probe.y() < world.getMinHeight() + 1 || probe.y() + PortalSpec.HEIGHT >= world.getMaxHeight() - 1) {
            throw new IllegalArgumentException("y " + y + " is outside the build limits (" + (world.getMinHeight() + 2) + " to " + (world.getMaxHeight() - 6) + ").");
        }
        int[] box = probe.box();
        for (int bx : new int[]{box[0], box[3]}) {
            for (int bz : new int[]{box[2], box[5]}) {
                if (!world.getWorldBorder().isInside(new Location(world, bx, y, bz))) {
                    throw new IllegalArgumentException("That is outside the world border.");
                }
            }
        }
        checkSpacing(pl, scene, world, probe);
        return probe;
    }

    public static void checkSpacing(PortalDirectorPlugin pl, Scene scene, World world, PortalSpec probe) {
        for (PortalSpec other : pl.scenes().portalsIn(world.getName())) {
            if (!probe.separatedFrom(other, 1)) {
                String near = pl.scenes().current() == null ? "another portal" : pl.scenes().current().nameOf(other.id());
                throw new IllegalArgumentException("Too close to " + near + " (" + other.anchorX() + ", " + other.anchorY() + ", "
                        + other.anchorZ() + "). Leave at least one empty block between portals.");
            }
        }
    }

    /** Loads the chunks the portal covers; completes once they are all there. */
    public static CompletableFuture<Void> load(World world, PortalSpec probe) {
        int[] box = probe.box();
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) loads.add(world.getChunkAtAsync(cx, cz, true));
        }
        return CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0]));
    }

    /** Builds the portal and adds it to the scene. Call on the main thread once its chunks are loaded. */
    public static PortalSpec build(PortalDirectorPlugin pl, World world, Scene scene, String name, int x, int y, int z, BlockFace facing, boolean force) {
        scene.checkName(name);
        PortalSpec probe = PortalSpec.fromAnchor(scene.nextId(), x, y, z, facing, false);
        checkSpacing(pl, scene, world, probe);
        List<int[]> cells = new ArrayList<>(probe.frameBlocks());
        cells.addAll(probe.interiorBlocks());
        cells.addAll(probe.corridorBlocks());
        if (!force) {
            for (int[] c : cells) {
                var type = world.getBlockAt(c[0], c[1], c[2]).getType();
                if (!NaturalBlocks.isNatural(type)) {
                    throw new IllegalArgumentException("That would replace " + type.name().toLowerCase(Locale.ROOT) + " at " + c[0] + ", " + c[1] + ", " + c[2]
                            + ", which looks built by a player. Move the portal, or add 'force' to replace it anyway.");
                }
            }
        }
        BukkitTerrain terrain = new BukkitTerrain(world);
        int maxTop = Integer.MIN_VALUE;
        for (int i = 0; i < PortalSpec.WIDTH; i++) maxTop = Math.max(maxTop, terrain.topY(probe.cellX(i), probe.cellZ(i)));
        int support = PortalPlanner.MAX_SUPPORT;
        boolean floating = probe.y() - 1 - maxTop > support;
        PortalSpec spec = new PortalSpec(probe.id(), probe.x(), probe.y(), probe.z(), probe.axis(), probe.facing(), floating);
        for (PortalEdits.Edit e : PortalEdits.forPortal(world, spec, support)) PortalEdits.apply(world, scene.blocks(), e);
        scene.addPortal(spec, name);
        pl.scenes().select(scene);
        pl.scenes().save(scene);
        pl.saveSettings();
        return spec;
    }
}
