package dev.portaldirector.group;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Pure rules for giving group members a portal each. */
public final class Assigner {

    private Assigner() {
    }

    /**
     * Portal 1 gets the first member, portal 2 the second, and so on. Extra portals stay empty and extra
     * members stay unassigned. With {@code keepExisting} only members without a portal are placed, on the
     * lowest free portals.
     */
    public static TreeMap<Integer, UUID> auto(List<Integer> portalIds, List<UUID> members,
                                              TreeMap<Integer, UUID> existing, boolean keepExisting) {
        TreeMap<Integer, UUID> out = new TreeMap<>();
        Set<UUID> used = new HashSet<>();
        if (keepExisting) {
            for (var e : existing.entrySet()) {
                if (portalIds.contains(e.getKey()) && members.contains(e.getValue()) && used.add(e.getValue())) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        List<Integer> free = new ArrayList<>();
        for (int id : portalIds) {
            if (!out.containsKey(id)) free.add(id);
        }
        free.sort(Integer::compare);
        int next = 0;
        for (UUID member : members) {
            if (used.contains(member)) continue;
            if (next >= free.size()) break;
            out.put(free.get(next++), member);
            used.add(member);
        }
        return out;
    }

    /** Puts a player on one portal, taking them off any other portal they held. Returns the previous holder. */
    public static UUID assign(TreeMap<Integer, UUID> map, int portalId, UUID player) {
        map.values().removeIf(player::equals);
        return map.put(portalId, player);
    }

    /** Lowest portal id that nobody holds, or -1. */
    public static int firstFree(List<Integer> portalIds, TreeMap<Integer, UUID> map) {
        return portalIds.stream().sorted().filter(id -> !map.containsKey(id)).findFirst().orElse(-1);
    }
}
