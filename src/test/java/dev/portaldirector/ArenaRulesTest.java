package dev.portaldirector;

import dev.portaldirector.arena.ArenaRules;
import dev.portaldirector.db.Take;
import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArenaRulesTest {

    @Test
    void cornersInAnyOrderGiveTheSameBox() {
        Take.Arena a = ArenaRules.box("world", 10, 70, -5, -10, 60, 5, "a.schem");

        assertEquals(-10, a.minX());
        assertEquals(10, a.maxX());
        assertEquals(60, a.minY());
        assertEquals(70, a.maxY());
        assertEquals(-5, a.minZ());
        assertEquals(5, a.maxZ());
        assertEquals(21L * 11 * 11, a.volume());
    }

    @Test
    void aBoxInsideTheLimitsIsFine() {
        assertNull(ArenaRules.problem(ArenaRules.box("world", 0, 0, 0, 20, 20, 20, "a"), -64, 320, 250_000));
    }

    @Test
    void aBoxThatIsTooBigIsRefusedWithTheLimit() {
        String problem = ArenaRules.problem(ArenaRules.box("world", 0, 0, 0, 999, 100, 999, "a"), -64, 320, 250_000);

        assertNotNull(problem);
        assertTrue(problem.contains("250000"));
        assertTrue(problem.contains("whole world"));
    }

    @Test
    void aBoxOutsideTheWorldHeightIsRefused() {
        assertNotNull(ArenaRules.problem(ArenaRules.box("world", 0, -100, 0, 5, 5, 5, "a"), -64, 320, 250_000));
        assertNotNull(ArenaRules.problem(ArenaRules.box("world", 0, 0, 0, 5, 320, 5, "a"), -64, 320, 250_000));
        assertNull(ArenaRules.problem(ArenaRules.box("world", 0, -64, 0, 5, 319, 5, "a"), -64, 320, 250_000));
    }

    @Test
    void aPortalInsideTheBoxOverlapsAndOneFarAwayDoesNot() {
        Take.Arena a = ArenaRules.box("world", 0, 60, 0, 30, 90, 30, "a");
        PortalSpec inside = PortalSpec.fromAnchor(1, 10, 70, 10, BlockFace.SOUTH, true);
        PortalSpec outside = PortalSpec.fromAnchor(2, 200, 70, 200, BlockFace.SOUTH, true);

        assertTrue(ArenaRules.overlaps(a, inside));
        assertFalse(ArenaRules.overlaps(a, outside));
        assertEquals(List.of(inside), ArenaRules.overlapping(a, List.of(inside, outside)));
    }

    @Test
    void aPortalAboveTheBoxDoesNotOverlap() {
        Take.Arena a = ArenaRules.box("world", 0, 60, 0, 30, 90, 30, "a");

        assertFalse(ArenaRules.overlaps(a, PortalSpec.fromAnchor(1, 10, 120, 10, BlockFace.SOUTH, true)));
    }

    @Test
    void theSnapshotNameIsLowerCaseWithAnExtension() {
        assertEquals("scene-1.schem", ArenaRules.snapshotName("Scene-1"));
        assertNotNull(Axis.X);
    }
}
