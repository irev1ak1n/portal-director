package dev.portaldirector;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnchorTest {

    @Test
    void anchorIsTheLowerBottomInteriorBlock() {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            PortalSpec p = PortalSpec.fromAnchor(1, 150, 80, -200, f, false);
            assertEquals(150, p.anchorX());
            assertEquals(80, p.anchorY());
            assertEquals(-200, p.anchorZ());
            assertEquals(f, p.facing());
            assertEquals(f == BlockFace.NORTH || f == BlockFace.SOUTH ? Axis.X : Axis.Z, p.axis());
            assertTrue(p.interiorBlocks().stream().anyMatch(c -> c[0] == 150 && c[1] == 80 && c[2] == -200));
            assertEquals(79, p.y());
        }
    }

    @Test
    void facingDecidesWhichSideTheWalkwayIsOn() {
        PortalSpec north = PortalSpec.fromAnchor(1, 0, 10, 0, BlockFace.NORTH, false);
        PortalSpec west = PortalSpec.fromAnchor(2, 0, 10, 0, BlockFace.WEST, false);
        assertTrue(north.corridorBlocks().stream().anyMatch(c -> c[2] == -3));
        assertTrue(west.corridorBlocks().stream().anyMatch(c -> c[0] == -3));
        assertEquals(-1, north.stepZ());
        assertEquals(-1, west.stepX());
    }
}
