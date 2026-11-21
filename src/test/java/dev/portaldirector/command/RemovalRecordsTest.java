package dev.portaldirector.command;

import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.BlockLog;
import dev.portaldirector.scene.Scene;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Removing one portal must pick exactly the block records a full scan of the log would pick. */
class RemovalRecordsTest {

    private static List<BlockLog.Rec> byScanning(Scene sc, PortalSpec p) {
        List<BlockLog.Rec> mine = new ArrayList<>();
        int[] box = p.box();

        for (BlockLog.Rec r : sc.blocks().all()) {
            BlockLog.Pos q = r.pos();
            boolean support = q.y() < p.y() && q.y() >= p.y() - 5 && q.x() >= box[0] && q.x() <= box[3] && q.z() >= box[2] && q.z() <= box[5] && onFrameColumn(p, q.x(), q.z());

            if (p.contains(q.x(), q.y(), q.z()) || support) mine.add(r);
        }

        return mine;
    }

    private static boolean onFrameColumn(PortalSpec p, int x, int z) {
        for (int i = 0; i < PortalSpec.WIDTH; i++) {
            if (p.cellX(i) == x && p.cellZ(i) == z) return true;
        }

        return false;
    }

    private static Set<BlockLog.Pos> positions(List<BlockLog.Rec> recs) {
        Set<BlockLog.Pos> out = new HashSet<>();

        for (BlockLog.Rec r : recs) out.add(r.pos());

        return out;
    }

    @Test
    void theDirectLookupFindsTheSameRecordsAsAFullScan() {
        Random rnd = new Random(11);
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        Scene scene = new Scene("bench", "world");
        List<PortalSpec> portals = new ArrayList<>();

        for (int i = 1; i <= 120; i++) {
            BlockFace face = faces[rnd.nextInt(4)];
            Axis axis = face == BlockFace.NORTH || face == BlockFace.SOUTH ? Axis.X : Axis.Z;
            PortalSpec p = new PortalSpec(i, (i % 12) * 30, 80 + (i / 12) * 6, (i / 12) * 30, axis, face, i % 2 == 0);
            portals.add(p);
            scene.addPortal(p);

            for (int[] c : p.frameBlocks()) scene.blocks().record(c[0], c[1], c[2], "minecraft:air", "minecraft:obsidian");
            for (int[] c : p.interiorBlocks()) scene.blocks().record(c[0], c[1], c[2], "minecraft:air", "minecraft:nether_portal");
            for (int[] c : p.corridorBlocks()) scene.blocks().record(c[0], c[1], c[2], "minecraft:stone", "minecraft:air");

            for (int col = 0; col < PortalSpec.WIDTH; col++) {
                for (int dy = 1; dy <= 5; dy++) scene.blocks().record(p.cellX(col), p.y() - dy, p.cellZ(col), "minecraft:air", "minecraft:dirt");
            }
        }

        for (int i = 0; i < 800; i++) {
            PortalSpec near = portals.get(rnd.nextInt(portals.size()));
            scene.blocks().record(near.x() + rnd.nextInt(14) - 5, near.y() + rnd.nextInt(18) - 8, near.z() + rnd.nextInt(14) - 5, "minecraft:air", "minecraft:glass");
        }

        for (PortalSpec p : portals) {
            List<BlockLog.Rec> direct = PortalCommands.recordsOf(scene, p);

            assertEquals(positions(byScanning(scene, p)), positions(direct), "portal " + p.id());
            assertEquals(byScanning(scene, p).size(), direct.size(), "no record twice, portal " + p.id());
            assertFalse(direct.isEmpty());
        }
    }

    @Test
    void aPortalWithNoRecordsHasNothingToRestore() {
        Scene scene = new Scene("empty", "world");
        PortalSpec p = new PortalSpec(1, 0, 100, 0, Axis.X, BlockFace.SOUTH, true);
        scene.addPortal(p);

        assertTrue(PortalCommands.recordsOf(scene, p).isEmpty());
    }
}
