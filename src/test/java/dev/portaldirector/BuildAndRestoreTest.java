package dev.portaldirector;

import dev.portaldirector.gen.PortalEdits;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.RemovalJob;
import dev.portaldirector.scene.Scene;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Orientable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BuildAndRestoreTest {

    private ServerMock server;
    private PortalDirectorPlugin plugin;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PortalDirectorPlugin.class);
        world = new WorldMock(Material.GRASS_BLOCK, 4);
        server.addWorld((WorldMock) world);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void place(Scene scene, PortalSpec p) {
        for (PortalEdits.Edit e : PortalEdits.forPortal(world, p, 4)) {
            var b = world.getBlockAt(e.x(), e.y(), e.z());
            scene.blocks().record(e.x(), e.y(), e.z(), b.getBlockData().getAsString(), e.data().getAsString());
            b.setBlockData(e.data(), false);
        }
        scene.portals().put(p.id(), p);
    }

    private long count(PortalSpec p, Material m) {
        return java.util.stream.Stream.concat(p.frameBlocks().stream(), p.interiorBlocks().stream())
                .filter(c -> world.getBlockAt(c[0], c[1], c[2]).getType() == m).count();
    }

    @Test
    void buildsFrameAndActiveInteriorOnBothAxes() {
        int top = world.getHighestBlockYAt(0, 0);
        Scene scene = new Scene("t", world.getName());
        PortalSpec x = new PortalSpec(1, 0, top + 1, 0, Axis.X, BlockFace.SOUTH, false);
        PortalSpec z = new PortalSpec(2, 20, top + 1, 0, Axis.Z, BlockFace.EAST, false);
        place(scene, x);
        place(scene, z);
        for (PortalSpec p : new PortalSpec[]{x, z}) {
            assertEquals(14, count(p, Material.OBSIDIAN));
            assertEquals(6, count(p, Material.NETHER_PORTAL));
            int[] c = p.interiorBlocks().get(0);
            Orientable data = (Orientable) world.getBlockAt(c[0], c[1], c[2]).getBlockData();
            assertEquals(p.axis(), data.getAxis());
        }
    }

    @Test
    void floatingPortalsGetNoSupportBelow() {
        int top = world.getHighestBlockYAt(0, 0);
        Scene scene = new Scene("t", world.getName());
        PortalSpec p = new PortalSpec(1, 0, top + 30, 0, Axis.X, BlockFace.SOUTH, true);
        place(scene, p);
        assertEquals(Material.AIR, world.getBlockAt(1, top + 29, 0).getType());
        assertEquals(14, scene.blocks().size() - 6);
    }

    @Test
    void removalRestoresTheTerrainButNotBlocksChangedLater() {
        int top = world.getHighestBlockYAt(0, 0);
        Scene scene = new Scene("t", world.getName());
        PortalSpec p = new PortalSpec(1, 0, top + 1, 0, Axis.X, BlockFace.SOUTH, false);
        Material before = world.getBlockAt(0, top, 0).getType();
        place(scene, p);
        int[] frame = p.frameBlocks().get(3);
        world.getBlockAt(frame[0], frame[1], frame[2]).setType(Material.DIAMOND_BLOCK);

        AtomicReference<RemovalJob.Result> result = new AtomicReference<>();
        RemovalJob job = new RemovalJob(plugin, world, scene, 1000, result::set);
        job.start();
        server.getScheduler().performTicks(5);

        assertNotNull(result.get());
        assertEquals(1, result.get().kept());
        assertEquals(scene.blocks().size(), 0);
        assertEquals(Material.DIAMOND_BLOCK, world.getBlockAt(frame[0], frame[1], frame[2]).getType());
        assertEquals(0, count(p, Material.NETHER_PORTAL));
        assertEquals(before, world.getBlockAt(0, top, 0).getType());
        long obsidianLeft = p.frameBlocks().stream()
                .filter(c -> world.getBlockAt(c[0], c[1], c[2]).getType() == Material.OBSIDIAN).count();
        assertEquals(0, obsidianLeft);
    }
}
