package dev.portaldirector;

import dev.portaldirector.command.PdCommand;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SceneStore;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PortalNamesTest {

    @TempDir
    File dir;

    private static PortalSpec spec(int id, int x, int y, int z) {
        return new PortalSpec(id, x, y, z, Axis.X, BlockFace.NORTH, false);
    }

    @Test
    void generatedPortalsGetReadableDefaultNames() {
        Scene s = new Scene("a", "world");
        s.addPortal(spec(1, 0, 64, 0));
        s.addPortal(spec(2, 20, 64, 0));
        assertEquals("portal-1", s.nameOf(1));
        assertEquals("portal-2", s.nameOf(2));
        assertEquals(2, s.byName("PORTAL-2").id(), "lookup ignores case");
    }

    @Test
    void namesAreUniqueIgnoringCaseAndMustBeUsable() {
        Scene s = new Scene("a", "world");
        s.addPortal(spec(1, 10, 64, -20), "EntranceA");
        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class, () -> s.checkName("entrancea"));
        assertTrue(dup.getMessage().contains("already exists"));
        PortalSpec at = s.byName("EntranceA");
        assertTrue(dup.getMessage().contains(at.anchorX() + " " + at.anchorY() + " " + at.anchorZ()), "says where the existing portal is");
        assertDoesNotThrow(() -> s.checkName("EntranceB"));
        for (String bad : new String[]{"", "7", "1a", "has space", "all", "GROUP", "x".repeat(25)}) {
            assertThrows(IllegalArgumentException.class, () -> s.checkName(bad), bad);
        }
    }

    @Test
    void aDefaultNameNeverCollidesWithAManualOne() {
        Scene s = new Scene("a", "world");
        s.addPortal(spec(1, 0, 64, 0), "portal-2");
        s.addPortal(spec(2, 20, 64, 0));
        assertEquals("portal-2", s.nameOf(1));
        assertNotEquals("portal-2", s.nameOf(2));
        assertEquals(1, s.byName("portal-2").id());
    }

    @Test
    void coordinateLookupMatchesTheExactAnchorOnly() {
        Scene s = new Scene("a", "world");
        PortalSpec p = PortalSpec.fromAnchor(1, 150, 100, -200, BlockFace.EAST, false);
        s.addPortal(p, "B");
        assertSame(p, s.atAnchor(150, 100, -200));
        assertNull(s.atAnchor(151, 100, -200));
        assertNull(s.atAnchor(150, 101, -200));
        assertNull(s.atAnchor(150, 100, -199));
    }

    @Test
    void removingAPortalDropsItsNameAndAssignments() {
        Scene s = new Scene("a", "world");
        s.addPortal(spec(1, 0, 64, 0), "A");
        s.addPortal(spec(2, 20, 64, 0), "B");
        s.assignmentsOf("team").put(1, UUID.randomUUID());
        s.assignmentsOf("team").put(2, UUID.randomUUID());
        s.dropPortal(1);
        assertNull(s.byName("A"));
        assertFalse(s.assignmentsOf("team").containsKey(1));
        assertTrue(s.assignmentsOf("team").containsKey(2));
        assertDoesNotThrow(() -> s.checkName("A"), "the name is free again");
    }

    @Test
    void namesAndAssignmentsSurviveSaving() throws IOException {
        SceneStore store = new SceneStore(dir);
        Scene s = new Scene("show", "world");
        s.addPortal(spec(1, 0, 64, 0), "EntranceA");
        s.addPortal(spec(2, 20, 64, 0));
        UUID player = UUID.randomUUID();
        s.assignmentsOf("team").put(1, player);
        store.save(s);
        Scene back = store.load("show");
        assertEquals("EntranceA", back.nameOf(1));
        assertEquals("portal-2", back.nameOf(2));
        assertEquals(player, back.assignmentsOf("team").get(1));
    }

    @Test
    void sceneFilesFromBeforeNamesStillLoadWithTheirAssignments() throws IOException {
        File old = new File(dir, "old.yml");
        YamlConfiguration y = new YamlConfiguration();
        UUID player = UUID.randomUUID();
        y.set("world", "world");
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", id);
            m.put("x", id * 20);
            m.put("y", 64);
            m.put("z", 0);
            m.put("axis", "X");
            m.put("facing", "SOUTH");
            m.put("floating", false);
            list.add(m);
        }
        y.set("portals", list);
        y.set("assignments.team.2", player.toString());
        y.save(old);
        Scene s = new SceneStore(dir).load("old");
        assertEquals(3, s.portals().size());
        assertEquals("portal-2", s.nameOf(2));
        assertEquals(2, s.byName("portal-2").id());
        assertEquals(player, s.assignmentsOf("team").get(2));
    }

    @Test
    void suggestionsAreAlphabetical() {
        List<String> l = new ArrayList<>(List.of("tnt", "set", "Clear", "create", "10", "2", "list", "group"));
        l.sort(PdCommand.ALPHABETICAL);
        assertEquals(List.of("10", "2", "Clear", "create", "group", "list", "set", "tnt"), l);
    }
}
