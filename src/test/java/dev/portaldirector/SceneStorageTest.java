package dev.portaldirector;

import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SceneStore;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SceneStorageTest {

    @TempDir
    File dir;

    @Test
    void sceneSurvivesAWriteAndRead() throws IOException {
        SceneStore store = new SceneStore(dir);
        Scene s = new Scene("invasion", "world");
        s.portals().put(1, new PortalSpec(1, 10, 64, -20, Axis.X, BlockFace.NORTH, false));
        s.portals().put(2, new PortalSpec(2, 40, 120, 5, Axis.Z, BlockFace.EAST, true));
        s.blocks().record(10, 64, -20, "minecraft:grass_block[snowy=false]", "minecraft:obsidian");
        s.blocks().record(11, 65, -20, "minecraft:air", "minecraft:nether_portal[axis=x]");
        store.save(s);

        Scene back = store.load("invasion");
        assertEquals("world", back.worldName());
        assertEquals(s.portals(), back.portals());
        assertEquals(2, back.blocks().size());
        assertTrue(back.blocks().has(11, 65, -20));
        assertEquals(3, back.nextId());
    }

    @Test
    void blockLogKeepsTheFirstOriginalWhenARegionIsBuiltTwice() {
        Scene s = new Scene("a", "world");
        s.blocks().record(1, 2, 3, "minecraft:stone", "minecraft:obsidian");
        s.blocks().record(1, 2, 3, "minecraft:obsidian", "minecraft:obsidian");
        assertEquals(1, s.blocks().size());
        assertEquals("minecraft:stone", s.blocks().all().iterator().next().original());
    }

    @Test
    void scenesCanBeRenamedAndListed() throws IOException {
        SceneStore store = new SceneStore(dir);
        store.save(new Scene("default", "world"));
        store.rename("default", "invasion");
        assertTrue(store.exists("invasion"));
        assertFalse(store.exists("default"));
        assertEquals(java.util.List.of("invasion"), store.names());
    }
}
