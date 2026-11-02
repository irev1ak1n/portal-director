package dev.portaldirector;

import dev.portaldirector.group.Assigner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AssignerTest {

    private static List<UUID> players(int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(UUID.randomUUID());
        return out;
    }

    private static List<Integer> portals(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(i);
        return out;
    }

    @Test
    void portalOneGetsPlayerOne() {
        List<UUID> p = players(5);
        TreeMap<Integer, UUID> map = Assigner.auto(portals(5), p, new TreeMap<>(), false);
        for (int i = 0; i < 5; i++) assertEquals(p.get(i), map.get(i + 1));
    }

    @Test
    void extraPortalsStayEmpty() {
        TreeMap<Integer, UUID> map = Assigner.auto(portals(30), players(12), new TreeMap<>(), false);
        assertEquals(12, map.size());
        assertEquals(12, map.lastKey());
        assertNull(map.get(13));
    }

    @Test
    void extraPlayersGetNothing() {
        List<UUID> p = players(8);
        TreeMap<Integer, UUID> map = Assigner.auto(portals(5), p, new TreeMap<>(), false);
        assertEquals(5, map.size());
        assertFalse(map.containsValue(p.get(7)));
    }

    @Test
    void everyPlayerAppearsOnlyOnce() {
        TreeMap<Integer, UUID> map = Assigner.auto(portals(30), players(30), new TreeMap<>(), false);
        assertEquals(30, map.values().stream().distinct().count());
    }

    @Test
    void fillKeepsManualChoicesAndPlacesTheRest() {
        List<UUID> p = players(4);
        TreeMap<Integer, UUID> manual = new TreeMap<>();
        manual.put(3, p.get(0));
        TreeMap<Integer, UUID> map = Assigner.auto(portals(6), p, manual, true);
        assertEquals(p.get(0), map.get(3));
        assertEquals(p.get(1), map.get(1));
        assertEquals(p.get(2), map.get(2));
        assertEquals(p.get(3), map.get(4));
    }

    @Test
    void fillDropsStaleEntries() {
        List<UUID> p = players(2);
        TreeMap<Integer, UUID> old = new TreeMap<>();
        old.put(1, UUID.randomUUID());
        old.put(99, p.get(0));
        TreeMap<Integer, UUID> map = Assigner.auto(portals(3), p, old, true);
        assertEquals(p.get(0), map.get(1));
        assertEquals(p.get(1), map.get(2));
        assertEquals(2, map.size());
    }

    @Test
    void manualAssignMovesAPlayerOffTheirOldPortal() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        TreeMap<Integer, UUID> map = new TreeMap<>();
        map.put(1, a);
        map.put(2, b);
        UUID previous = Assigner.assign(map, 2, a);
        assertEquals(b, previous);
        assertNull(map.get(1));
        assertEquals(a, map.get(2));
    }

    @Test
    void firstFreeFindsTheLowestGap() {
        TreeMap<Integer, UUID> map = new TreeMap<>();
        map.put(1, UUID.randomUUID());
        map.put(3, UUID.randomUUID());
        assertEquals(2, Assigner.firstFree(portals(5), map));
        map.put(2, UUID.randomUUID());
        map.put(4, UUID.randomUUID());
        map.put(5, UUID.randomUUID());
        assertEquals(-1, Assigner.firstFree(portals(5), map));
    }
}
