package dev.portaldirector;

import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.FormationPlanner;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.RevealOrder;
import dev.portaldirector.gen.SquareFacing;
import dev.portaldirector.model.PortalSpec;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RevealOrderTest {

    private static List<Integer> ids(List<PortalSpec> l) {
        return l.stream().map(PortalSpec::id).toList();
    }

    private static List<PortalSpec> grid(FacingMode f) {
        return FormationPlanner.grid(1, 0, 0, 100, 4, 5, 8, 5, f, BlockFace.SOUTH, new Random(1));
    }

    private static RevealOrder.Info wall(BlockFace face, int columns) {
        return new RevealOrder.Info(face, columns, 0, 1, 0, 0);
    }

    @Test
    void gridBottomToTopGoesRowByRowAndLeftToRightWithinTheRow() {
        List<PortalSpec> plan = grid(FacingMode.SOUTH);
        assertEquals(ids(plan), ids(RevealOrder.arrange(Layout.GRID, RevealOrder.BOTTOM_TO_TOP, plan, wall(BlockFace.SOUTH, 5), new Random(1))));
        assertEquals(ids(plan), ids(RevealOrder.arrange(Layout.GRID, RevealOrder.AUTO, plan, wall(BlockFace.SOUTH, 5), new Random(1))));
    }

    @Test
    void gridTopToBottomStartsAtTheTopRow() {
        List<PortalSpec> plan = grid(FacingMode.SOUTH);
        List<PortalSpec> out = RevealOrder.arrange(Layout.GRID, RevealOrder.TOP_TO_BOTTOM, plan, wall(BlockFace.SOUTH, 5), new Random(1));
        assertEquals(List.of(16, 17, 18, 19, 20, 11, 12, 13, 14, 15), ids(out).subList(0, 10));
        assertEquals(out.get(0).y(), plan.get(15).y());
    }

    @Test
    void gridLeftToRightFinishesEachColumnBottomToTopBeforeTheNext() {
        List<PortalSpec> plan = grid(FacingMode.SOUTH);
        List<PortalSpec> out = RevealOrder.arrange(Layout.GRID, RevealOrder.LEFT_TO_RIGHT, plan, wall(BlockFace.SOUTH, 5), new Random(1));
        assertEquals(List.of(1, 6, 11, 16, 2, 7, 12, 17), ids(out).subList(0, 8));
        for (int i = 1; i < out.size(); i++) assertTrue(out.get(i).centerX() >= out.get(i - 1).centerX(), "moves left to right (east) for a south-facing wall");
    }

    @Test
    void leftToRightIsAsSeenFromInFrontOfTheWallForEveryDirection() {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            List<PortalSpec> plan = grid(FacingMode.valueOf(face.name()));
            List<PortalSpec> out = RevealOrder.arrange(Layout.GRID, RevealOrder.LEFT_TO_RIGHT, plan, wall(face, 5), new Random(1));
            double first = out.get(0).centerX() + out.get(0).centerZ();
            double last = out.get(out.size() - 1).centerX() + out.get(out.size() - 1).centerZ();
            // viewer in front of a south wall looks north: left is west, so reveal goes toward +x. North: toward -x. East: toward -z. West: toward +z.
            boolean increasing = face == BlockFace.SOUTH || face == BlockFace.WEST;
            assertTrue(increasing ? last > first : last < first, "wall facing " + face);
        }
    }

    @Test
    void waveRevealsRowByRowOrThroughTheStaggeredRowsLeftToRight() {
        List<PortalSpec> plan = FormationPlanner.wave(1, 0, 0, 100, 4, 5, 8, 5, FacingMode.SOUTH, BlockFace.SOUTH, new Random(1));
        List<PortalSpec> rows = RevealOrder.arrange(Layout.WAVE, RevealOrder.BOTTOM_TO_TOP, plan, wall(BlockFace.SOUTH, 5), new Random(1));
        assertEquals(ids(plan), ids(rows));
        List<PortalSpec> across = RevealOrder.arrange(Layout.WAVE, RevealOrder.AUTO, plan, wall(BlockFace.SOUTH, 5), new Random(1));
        for (int i = 1; i < across.size(); i++) assertTrue(across.get(i).centerX() >= across.get(i - 1).centerX());
        assertNotEquals(ids(plan), ids(across), "the staggered rows interleave when going across");
    }

    @Test
    void squareClockwiseGoesAroundLayerByLayer() {
        List<PortalSpec> plan = FormationPlanner.square(1, 0, 0, 100, 3, 3, 8, 5, SquareFacing.INWARD);
        RevealOrder.Info info = new RevealOrder.Info(BlockFace.SOUTH, 1, 3, 3, 0, 0);
        assertEquals(ids(plan), ids(RevealOrder.arrange(Layout.SQUARE, RevealOrder.CLOCKWISE, plan, info, new Random(1))));
        assertEquals(ids(plan), ids(RevealOrder.arrange(Layout.SQUARE, RevealOrder.AUTO, plan, info, new Random(1))));
        List<PortalSpec> top = RevealOrder.arrange(Layout.SQUARE, RevealOrder.TOP_TO_BOTTOM, plan, info, new Random(1));
        assertEquals(25, top.get(0).id());
        assertEquals(1, top.get(24).id());
    }

    @Test
    void squareCounterclockwiseReversesEachLayerButKeepsLayersInOrder() {
        List<PortalSpec> plan = FormationPlanner.square(1, 0, 0, 100, 3, 2, 8, 5, SquareFacing.INWARD);
        RevealOrder.Info info = new RevealOrder.Info(BlockFace.SOUTH, 1, 3, 2, 0, 0);
        List<PortalSpec> out = RevealOrder.arrange(Layout.SQUARE, RevealOrder.COUNTERCLOCKWISE, plan, info, new Random(1));
        assertEquals(12, out.get(0).id(), "starts at the portal next to the north-west corner on the west side");
        assertEquals(1, out.get(11).id());
        assertEquals(24, out.get(12).id());
        assertTrue(out.subList(0, 12).stream().allMatch(p -> p.y() == plan.get(0).y()));
    }

    @Test
    void ordersThatDoNotFitALayoutExplainWhy() {
        assertNotNull(RevealOrder.problem(Layout.GRID, RevealOrder.CLOCKWISE));
        assertTrue(RevealOrder.problem(Layout.WAVE, RevealOrder.COUNTERCLOCKWISE).contains("left-to-right"));
        assertTrue(RevealOrder.problem(Layout.SQUARE, RevealOrder.LEFT_TO_RIGHT).contains("clockwise"));
        assertNull(RevealOrder.problem(Layout.SQUARE, RevealOrder.BOTTOM_TO_TOP));
        assertNull(RevealOrder.problem(Layout.RANDOM, RevealOrder.CLOCKWISE));
        assertNull(RevealOrder.problem(Layout.GRID, RevealOrder.AUTO));
    }

    @Test
    void randomLayoutCanBeRevealedBySpace() {
        List<PortalSpec> plan = new ArrayList<>();
        int id = 1;
        for (int[] p : new int[][]{{40, 80, 0}, {-30, 120, 10}, {5, 100, -50}, {60, 90, 60}, {-50, 70, -40}}) {
            plan.add(PortalSpec.at(id++, p[0], p[1], p[2], org.bukkit.Axis.X, BlockFace.SOUTH, true));
        }
        RevealOrder.Info info = new RevealOrder.Info(BlockFace.SOUTH, 1, 0, 1, 0, 0);
        List<PortalSpec> lr = RevealOrder.arrange(Layout.RANDOM, RevealOrder.LEFT_TO_RIGHT, plan, info, new Random(1));
        assertEquals(List.of(5, 2, 3, 1, 4), ids(lr));
        List<PortalSpec> up = RevealOrder.arrange(Layout.RANDOM, RevealOrder.BOTTOM_TO_TOP, plan, info, new Random(1));
        assertEquals(List.of(5, 1, 4, 3, 2), ids(up));
        List<PortalSpec> cw = RevealOrder.arrange(Layout.RANDOM, RevealOrder.CLOCKWISE, plan, info, new Random(1));
        assertEquals(List.of(3, 1, 4, 2, 5), ids(cw), "clockwise from north: north, east, south, west");
        List<PortalSpec> ccw = RevealOrder.arrange(Layout.RANDOM, RevealOrder.COUNTERCLOCKWISE, plan, info, new Random(1));
        assertEquals(ids(cw).reversed(), ids(ccw));
        List<PortalSpec> shuffled = RevealOrder.arrange(Layout.RANDOM, RevealOrder.RANDOM, plan, info, new Random(5));
        assertEquals(5, shuffled.size());
        assertTrue(ids(shuffled).containsAll(ids(plan)));
    }

    @Test
    void generationSettingsAcceptHyphenatedOrdersAndDecimalDelaysAndSaveThem() {
        Settings s = new Settings();
        s.options().get("generate.generation").set("delayed");
        s.options().get("generate.generation-delay").set("0.2");
        s.options().get("generate.generation-order").set("left-to-right");
        s.options().get("generate.generation-effects").set("on");
        assertEquals(0.2, s.gen.delay);
        assertEquals(RevealOrder.LEFT_TO_RIGHT, s.gen.order);
        assertTrue(s.gen.effects);
        assertThrows(IllegalArgumentException.class, () -> s.options().get("generate.generation-delay").set("0"));
        assertThrows(IllegalArgumentException.class, () -> s.options().get("generate.generation-order").set("sideways"));
        YamlConfiguration saved = new YamlConfiguration();
        s.save(saved);
        Settings again = new Settings();
        again.load(saved);
        assertEquals(RevealOrder.LEFT_TO_RIGHT, again.gen.order);
        assertEquals(0.2, again.gen.delay);
        assertEquals("left-to-right", again.options().get("generate.generation-order").get());
        assertEquals("instant", new Settings().options().get("generate.generation").get());
    }
}
