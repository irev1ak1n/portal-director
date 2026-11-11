package dev.portaldirector;

import dev.portaldirector.group.Group;
import dev.portaldirector.group.GroupManager;
import dev.portaldirector.group.RevealPlan;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.nether.NetherGrid;
import dev.portaldirector.nether.NetherSite;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SceneManager;
import dev.portaldirector.scene.SceneStore;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class GroupAndNetherTest {

    @TempDir
    File dir;

    private GroupManager groups() {
        return new GroupManager(new File(dir, "groups.yml"), Logger.getLogger("test"));
    }

    // Groups

    @Test
    void membersLinksAndSwitchesSurviveASaveAndLoad() {
        GroupManager gm = groups();
        Group g = gm.create("NULL");
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        gm.join(g, steve, "Steve");
        gm.join(g, alex, "Alex");
        gm.link(g, steve, "portal-1");
        g.netherTravel(true);
        g.visibilityInPortal(false);
        g.visibilityDelay(0.5);
        gm.save();
        GroupManager back = groups();
        back.load();
        Group b = back.get("null");
        assertEquals("NULL", b.name());
        assertEquals(2, b.members().size());
        assertEquals("portal-1", b.portals().get(steve));
        assertNull(b.portals().get(alex));
        assertTrue(b.netherTravel());
        assertFalse(b.visibilityInPortal());
        assertEquals(0.5, b.visibilityDelay());
    }

    @Test
    void anOldGroupsFileWithoutTheNewKeysGetsTheDefaults() throws IOException {
        java.nio.file.Files.writeString(new File(dir, "groups.yml").toPath(),
                "groups:\n  Old:\n    join-block: EMERALD_BLOCK\n    members:\n      11111111-2222-3333-4444-555555555555: Steve\n");
        GroupManager gm = groups();
        gm.load();
        Group g = gm.get("old");
        assertEquals(1, g.members().size());
        assertEquals(org.bukkit.Material.EMERALD_BLOCK, g.joinBlock());
        assertFalse(g.netherTravel());
        assertTrue(g.visibilityInPortal());
        assertEquals(0.35, g.visibilityDelay());
        assertTrue(g.enrollment(), "a group from before enrollment existed keeps letting players join");
        assertSame(g, gm.groupForBlock(org.bukkit.Material.EMERALD_BLOCK));
    }

    @Test
    void enrollmentIsOpenByDefaultAndClosedStaysClosedAfterASaveAndLoad() {
        GroupManager gm = groups();
        Group g = gm.create("NULL");
        gm.setJoinBlock(g, org.bukkit.Material.OBSIDIAN);
        UUID steve = UUID.randomUUID();
        gm.join(g, steve, "Steve");
        assertTrue(g.enrollment());
        g.enrollment(false);
        gm.save();
        GroupManager back = groups();
        back.load();
        Group b = back.get("null");
        assertFalse(b.enrollment());
        assertEquals(org.bukkit.Material.OBSIDIAN, b.joinBlock(), "closing enrollment keeps the block");
        assertTrue(b.has(steve), "and the members");
        assertSame(b, back.groupForBlock(org.bukkit.Material.OBSIDIAN));
        b.enrollment(true);
        back.save();
        GroupManager again = groups();
        again.load();
        assertTrue(again.get("null").enrollment());
    }

    @Test
    void twoPlayersCannotShareAPortalAndRelinkingMovesAPlayer() {
        GroupManager gm = groups();
        Group g = gm.create("A");
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        gm.join(g, steve, "Steve");
        gm.join(g, alex, "Alex");
        gm.link(g, steve, "portal-1");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> gm.link(g, alex, "PORTAL-1"));
        assertTrue(e.getMessage().contains("Steve") && e.getMessage().contains("unlink-from-portal"), e.getMessage());
        assertEquals("portal-1", gm.link(g, steve, "portal-2"));
        assertNull(gm.link(g, alex, "portal-1"), "the portal is free once Steve moved on");
        assertEquals(alex, gm.ownerOf(g, "Portal-1"));
    }

    @Test
    void removingAPlayerClearsTheirLinkAndRemovingAPortalClearsEveryLinkToIt() {
        GroupManager gm = groups();
        Group g = gm.create("A");
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        gm.join(g, steve, "Steve");
        gm.join(g, alex, "Alex");
        gm.link(g, steve, "portal-1");
        gm.link(g, alex, "EntranceA");
        gm.remove(g, steve);
        assertFalse(g.has(steve));
        assertTrue(g.portals().keySet().stream().noneMatch(steve::equals));
        gm.forgetPortal("entrancea");
        assertTrue(g.portals().isEmpty());
        gm.link(g, alex, "portal-9");
        gm.forgetAllPortals();
        assertTrue(g.portals().isEmpty());
        assertTrue(g.has(alex), "membership is untouched");
    }

    @Test
    void deletingAGroupKeepsOthersAndFreesItsJoinBlock() {
        GroupManager gm = groups();
        Group a = gm.create("A");
        Group b = gm.create("B");
        gm.setJoinBlock(a, org.bukkit.Material.OBSIDIAN);
        assertThrows(IllegalArgumentException.class, () -> gm.setJoinBlock(b, org.bukkit.Material.OBSIDIAN));
        gm.delete(a);
        assertNull(gm.get("A"));
        assertNull(gm.groupForBlock(org.bukkit.Material.OBSIDIAN));
        assertSame(b, gm.only());
        gm.setJoinBlock(b, org.bukkit.Material.OBSIDIAN);
    }

    @Test
    void aGroupCanOnlyBeCreatedWithAUsableUniqueName() {
        GroupManager gm = groups();
        gm.create("Group1");
        assertThrows(IllegalArgumentException.class, () -> gm.create("group1"));
        assertThrows(IllegalArgumentException.class, () -> gm.create("bad name"));
        assertNotNull(gm.only(), "one group exists");
        gm.create("Group2");
        assertNull(gm.only(), "with two groups there is no only group");
    }

    @Test
    void oldSceneAssignmentsMoveOntoGroupsAsPortalNames() throws IOException {
        File scenes = new File(dir, "scenes");
        SceneStore store = new SceneStore(scenes);
        Scene s = new Scene("default", "world");
        s.addPortal(new PortalSpec(1, 0, 64, 0, Axis.X, BlockFace.SOUTH, false), "EntranceA");
        s.addPortal(new PortalSpec(2, 20, 64, 0, Axis.X, BlockFace.SOUTH, false));
        store.save(s);
        UUID steve = UUID.randomUUID();
        UUID ghost = UUID.randomUUID();
        File yml = new File(scenes, "default.yml");
        java.nio.file.Files.writeString(yml.toPath(), java.nio.file.Files.readString(yml.toPath())
                + "assignments:\n  team:\n    1: " + steve + "\n    2: " + ghost + "\n    9: " + steve + "\n");

        SceneManager sm = new SceneManager(new SceneStore(scenes), Logger.getLogger("test"));
        sm.loadAll("default");
        assertEquals(3, sm.current().legacyAssignments().get("team").size());
        GroupManager gm = groups();
        Group g = gm.create("Team");
        gm.join(g, steve, "Steve");
        assertEquals(1, gm.migrateLegacy(sm), "only a member's assignment to a portal that exists moves");
        assertEquals("EntranceA", g.portals().get(steve));
        assertTrue(sm.current().legacyAssignments().isEmpty());
        assertFalse(java.nio.file.Files.readString(yml.toPath()).contains("assignments:"), "the scene file no longer holds them");
        assertEquals(0, gm.migrateLegacy(sm), "it happens once");
        GroupManager reloaded = groups();
        reloaded.load();
        assertEquals("EntranceA", reloaded.get("team").portals().get(steve));
    }

    // Reveal timing

    @Test
    void playersAppearInSmallGroupsAndEveryoneAppearsOnce() {
        for (int players : new int[]{1, 2, 3, 4, 5, 9, 14, 40}) {
            List<RevealPlan.Batch> plan = RevealPlan.plan(players, 7);
            int seen = 0;
            for (RevealPlan.Batch b : plan) {
                assertEquals(seen, b.from());
                assertTrue(b.to() - b.from() >= 1 && b.to() - b.from() <= 4, "batch size " + (b.to() - b.from()));
                seen = b.to();
            }
            assertEquals(players, seen, players + " players");
            assertEquals(0, plan.get(0).tick(), "the first group appears at once");
        }
    }

    @Test
    void batchesAreSpacedByTheDelayWithAnOccasionalLongerPause() {
        List<RevealPlan.Batch> plan = RevealPlan.plan(40, 8);
        assertEquals(List.of(3, 2, 4, 2, 3, 3), plan.stream().limit(6).map(b -> b.to() - b.from()).toList());
        assertEquals(List.of(0, 8, 16, 28, 36, 44), plan.stream().limit(6).map(RevealPlan.Batch::tick).toList());
        assertEquals(plan, RevealPlan.plan(40, 8), "the same every time");
        assertTrue(plan.stream().map(RevealPlan.Batch::tick).distinct().count() == plan.size(), "no two groups at the same moment");
        assertEquals(1, RevealPlan.plan(2, 0).size(), "two players appear together");
    }

    // Nether site

    /** A flat Nether floor at y 60 with a ceiling at 100, and holes to put lava, structures or walls in. */
    private static final class FakeNether implements NetherGrid {
        final Set<Long> lava = new HashSet<>();
        final Set<Long> blocked = new HashSet<>();
        BiFunction<Integer, Integer, Boolean> solidColumn = (x, z) -> true;

        static long k(int x, int y, int z) {
            return ((long) x * 73856093L) ^ ((long) y * 19349663L) ^ ((long) z * 83492791L);
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 128;
        }

        @Override
        public boolean isReady(int x, int z) {
            return true;
        }

        @Override
        public Cell cell(int x, int y, int z) {
            if (blocked.contains(k(x, y, z))) return Cell.BLOCKED;
            if (lava.contains(k(x, y, z))) return Cell.LIQUID;
            if (y < 60) return solidColumn.apply(x, z) ? Cell.SOLID : Cell.LIQUID;
            if (y > 100) return Cell.SOLID;
            return Cell.AIR;
        }
    }

    @Test
    void manyClosePortalsGetTheirOwnNetherSitesThatNeverTouch() {
        FakeNether grid = new FakeNether();
        List<PortalSpec> taken = new ArrayList<>();
        // 40 Overworld portals 10 apart land within 5 blocks of each other at one eighth of the coordinates.
        for (int i = 0; i < 40; i++) {
            PortalSpec p = NetherSite.find(grid, i + 1, 100 + (i % 8) * 10 / 8, 100 + (i / 8) * 10 / 8, 64, Axis.X, BlockFace.SOUTH, taken, 32);
            assertNotNull(p, "portal " + i + " found no room");
            for (PortalSpec other : taken) assertTrue(p.separatedFrom(other, NetherSite.GAP), "portal " + i + " is too close to " + other.id());
            taken.add(p);
        }
        assertEquals(40, taken.stream().map(p -> p.x() + "," + p.y() + "," + p.z()).distinct().count());
    }

    @Test
    void aSiteNeverIncludesLavaStructuresOrAMissingFloor() {
        FakeNether grid = new FakeNether();
        // lava pool and a fortress wall around the aimed spot, and no floor in a strip
        for (int x = 90; x <= 110; x++) {
            for (int z = 90; z <= 110; z++) grid.lava.add(FakeNether.k(x, 60, z));
        }
        for (int y = 60; y < 70; y++) {
            for (int x = 112; x <= 114; x++) {
                for (int z = 90; z <= 120; z++) grid.blocked.add(FakeNether.k(x, y, z));
            }
        }
        grid.solidColumn = (x, z) -> !(x >= 115 && x <= 118);
        PortalSpec p = NetherSite.find(grid, 1, 100, 100, 64, Axis.X, BlockFace.NORTH, List.of(), 32);
        assertNotNull(p);
        for (int x = p.box()[0] - 2; x <= p.box()[3] + 2; x++) {
            for (int y = p.box()[1] - 2; y <= p.box()[4] + 2; y++) {
                for (int z = p.box()[2] - 2; z <= p.box()[5] + 2; z++) {
                    assertNotEquals(NetherGrid.Cell.BLOCKED, grid.cell(x, y, z), "structure within two blocks at " + x + " " + y + " " + z);
                }
            }
        }
        for (int i = 0; i < 4; i++) assertEquals(NetherGrid.Cell.SOLID, grid.cell(p.cellX(i), p.y() - 1, p.cellZ(i)), "no floor under column " + i);
        for (int[] c : p.frameBlocks()) assertNotEquals(NetherGrid.Cell.LIQUID, grid.cell(c[0], c[1], c[2]));
    }

    @Test
    void whenNothingFitsThereIsNoSiteInsteadOfAWrongOne() {
        FakeNether grid = new FakeNether();
        grid.solidColumn = (x, z) -> false;
        assertNull(NetherSite.find(grid, 1, 0, 0, 64, Axis.Z, BlockFace.EAST, List.of(), 20));
        FakeNether tight = new FakeNether();
        PortalSpec first = NetherSite.find(tight, 1, 0, 0, 64, Axis.X, BlockFace.SOUTH, List.of(), 0);
        assertNotNull(first);
        assertNull(NetherSite.find(tight, 2, 0, 0, 64, Axis.X, BlockFace.SOUTH, List.of(first), 0), "radius 0 and the spot is taken");
    }

    @Test
    void searchingFromARememberedCenterGivesTheSameCoordinatesAgain() {
        FakeNether grid = new FakeNether();
        // other pairs crowd the aimed spot, so the first site is not simply the aimed one
        List<PortalSpec> taken = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            taken.add(NetherSite.find(grid, i + 1, 100 + i, 100, 64, Axis.X, BlockFace.SOUTH, taken, 32));
        }
        PortalSpec first = NetherSite.find(grid, 7, 100, 100, 64, Axis.X, BlockFace.SOUTH, taken, 32);
        assertNotNull(first);
        int cx = (int) Math.floor(first.centerX());
        int cz = (int) Math.floor(first.centerZ());
        // the portal is deleted and built again at the same Overworld spot: the search starts where its partner stood
        PortalSpec again = NetherSite.find(grid, 8, cx, cz, first.y(), Axis.X, BlockFace.SOUTH, taken, 32);
        assertNotNull(again);
        assertEquals(first.anchorX(), again.anchorX());
        assertEquals(first.anchorY(), again.anchorY());
        assertEquals(first.anchorZ(), again.anchorZ());
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST}) {
            Axis axis = f == BlockFace.NORTH ? Axis.X : Axis.Z;
            PortalSpec p = NetherSite.find(grid, 9, 40, 40, 64, axis, f, List.of(), 8);
            assertEquals(40, (int) Math.floor(p.centerX()), "the center cell round-trips for " + f);
            assertEquals(40, (int) Math.floor(p.centerZ()), "the center cell round-trips for " + f);
        }
    }

    @Test
    void theSiteKeepsTheOverworldFacing() {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Axis axis = f == BlockFace.NORTH || f == BlockFace.SOUTH ? Axis.X : Axis.Z;
            PortalSpec p = NetherSite.find(new FakeNether(), 1, 5, 5, 64, axis, f, List.of(), 8);
            assertNotNull(p);
            assertEquals(f, p.facing());
            assertEquals(axis, p.axis());
            assertTrue(p.floating(), "a Nether site is built without the Overworld ground fill");
        }
    }

    @Test
    void theTreeMapOfLegacyAssignmentsIsSortedById() {
        Scene s = new Scene("a", "w");
        s.legacyAssignments().computeIfAbsent("g", k -> new TreeMap<>()).put(5, UUID.randomUUID());
        s.legacyAssignments().get("g").put(2, UUID.randomUUID());
        assertEquals(List.of(2, 5), new ArrayList<>(s.legacyAssignments().get("g").keySet()));
    }
}
