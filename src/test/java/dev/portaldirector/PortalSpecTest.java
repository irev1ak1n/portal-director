package dev.portaldirector;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PortalSpecTest {

    @Test
    void frameIsFourByFiveWithTwoByThreeInterior() {
        PortalSpec p = new PortalSpec(1, 10, 64, 20, Axis.X, BlockFace.SOUTH, false);
        assertEquals(14, p.frameBlocks().size());
        assertEquals(6, p.interiorBlocks().size());
        Set<String> frame = new HashSet<>();
        p.frameBlocks().forEach(c -> frame.add(c[0] + "," + c[1] + "," + c[2]));
        p.interiorBlocks().forEach(c -> assertFalse(frame.contains(c[0] + "," + c[1] + "," + c[2])));
    }

    @Test
    void axisXRunsAlongXAndAxisZAlongZ() {
        PortalSpec x = new PortalSpec(1, 0, 0, 0, Axis.X, BlockFace.NORTH, false);
        PortalSpec z = new PortalSpec(2, 0, 0, 0, Axis.Z, BlockFace.EAST, false);
        assertTrue(x.frameBlocks().stream().allMatch(c -> c[2] == 0));
        assertTrue(z.frameBlocks().stream().allMatch(c -> c[0] == 0));
        assertEquals(4, x.frameBlocks().stream().mapToInt(c -> c[0]).max().getAsInt() + 1);
        assertEquals(4, z.frameBlocks().stream().mapToInt(c -> c[2]).max().getAsInt() + 1);
    }

    @Test
    void facingMustBePerpendicularToTheFrame() {
        assertThrows(IllegalArgumentException.class, () -> new PortalSpec(1, 0, 0, 0, Axis.X, BlockFace.EAST, false));
        assertThrows(IllegalArgumentException.class, () -> new PortalSpec(1, 0, 0, 0, Axis.Z, BlockFace.NORTH, false));
        assertThrows(IllegalArgumentException.class, () -> new PortalSpec(1, 0, 0, 0, Axis.Y, BlockFace.NORTH, false));
    }

    @Test
    void yawMatchesFacing() {
        assertEquals(0f, new PortalSpec(1, 0, 0, 0, Axis.X, BlockFace.SOUTH, false).yaw());
        assertEquals(180f, new PortalSpec(1, 0, 0, 0, Axis.X, BlockFace.NORTH, false).yaw());
        assertEquals(-90f, new PortalSpec(1, 0, 0, 0, Axis.Z, BlockFace.EAST, false).yaw());
        assertEquals(90f, new PortalSpec(1, 0, 0, 0, Axis.Z, BlockFace.WEST, false).yaw());
    }

    @Test
    void centerIsInTheMiddleOfTheInterior() {
        PortalSpec x = new PortalSpec(1, 10, 64, 20, Axis.X, BlockFace.SOUTH, false);
        assertEquals(12.0, x.centerX());
        assertEquals(20.5, x.centerZ());
        PortalSpec z = new PortalSpec(1, 10, 64, 20, Axis.Z, BlockFace.EAST, false);
        assertEquals(10.5, z.centerX());
        assertEquals(22.0, z.centerZ());
        assertEquals(65.0, z.standY());
    }

    @Test
    void corridorLiesOnTheFacingSideAndClearsTheWalkway() {
        PortalSpec p = new PortalSpec(1, 0, 0, 0, Axis.X, BlockFace.SOUTH, false);
        long front = p.corridorBlocks().stream().filter(c -> c[2] > 0).count();
        long back = p.corridorBlocks().stream().filter(c -> c[2] < 0).count();
        assertEquals(PortalSpec.FRONT * 6, front);
        assertEquals(PortalSpec.BACK * 6, back);
        assertTrue(p.contains(1, 2, 3));
        assertFalse(p.contains(1, 2, 4));
    }

    @Test
    void separationCountsEmptyBlocksBetweenBoxes() {
        PortalSpec a = new PortalSpec(1, 0, 0, 0, Axis.X, BlockFace.SOUTH, false);
        PortalSpec near = new PortalSpec(2, 4, 0, 0, Axis.X, BlockFace.SOUTH, false);
        PortalSpec far = new PortalSpec(3, 10, 0, 0, Axis.X, BlockFace.SOUTH, false);
        assertFalse(a.separatedFrom(near, 1));
        assertTrue(a.separatedFrom(far, 6));
        assertFalse(a.separatedFrom(far, 7));
        PortalSpec above = new PortalSpec(4, 0, 12, 0, Axis.X, BlockFace.SOUTH, true);
        assertTrue(a.separatedFrom(above, 5));
    }
}
