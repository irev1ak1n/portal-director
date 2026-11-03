package dev.portaldirector.entrance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** How a square formation releases its players: all together, around the square, or side by side. */
public enum ReleasePattern {
    ALL_AT_ONCE, CLOCKWISE, OPPOSITE_SIDES, SIDE_BY_SIDE;

    /** The pattern named by an admin, or null. */
    public static ReleasePattern parse(String text) {
        return switch (text.toLowerCase(Locale.ROOT).replace("_", "-")) {
            case "all-at-once", "all", "together" -> ALL_AT_ONCE;
            case "clockwise" -> CLOCKWISE;
            case "opposite-sides", "opposite" -> OPPOSITE_SIDES;
            case "side-by-side", "sides" -> SIDE_BY_SIDE;
            default -> null;
        };
    }

    /**
     * Groups of portal ids released one after another. {@code sides} are the portal ids of north, east, south
     * and west, {@code ring} all portals in the order they go around; only ids in {@code assigned} are used and empty groups are dropped.
     */
    public List<List<Integer>> waves(List<List<Integer>> sides, List<Integer> ring, Set<Integer> assigned, int groupSize) {
        List<List<Integer>> raw = new ArrayList<>();
        switch (this) {
            case ALL_AT_ONCE -> raw.add(ring);
            case SIDE_BY_SIDE -> raw.addAll(sides);
            case OPPOSITE_SIDES -> {
                raw.add(flatten(sides, 0, 2));
                raw.add(flatten(sides, 1, 3));
            }
            case CLOCKWISE -> {
                int size = Math.max(1, groupSize);
                for (int i = 0; i < ring.size(); i += size) raw.add(ring.subList(i, Math.min(ring.size(), i + size)));
            }
        }
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> wave : raw) {
            List<Integer> used = wave.stream().filter(assigned::contains).toList();
            if (!used.isEmpty()) out.add(used);
        }
        return out;
    }

    private static List<Integer> flatten(List<List<Integer>> sides, int... which) {
        List<Integer> out = new ArrayList<>();
        for (int i : which) out.addAll(sides.get(i));
        return out;
    }
}
