package dev.portaldirector.gen;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The order portals appear in when they are revealed one by one. Orders are based on where the portals really are:
 * "left to right" is as seen standing in front of a wall, "clockwise" is as seen from above with north up.
 */
public enum RevealOrder {
    AUTO, RANDOM, LEFT_TO_RIGHT, BOTTOM_TO_TOP, TOP_TO_BOTTOM, CLOCKWISE, COUNTERCLOCKWISE;

    /** What the order needs to know about the formation. {@code columns} is the portals per row of a grid or wave. */
    public record Info(BlockFace wall, int columns, int perSide, int layers, double centerX, double centerZ) {
    }

    /** Null when the order makes sense for the layout, otherwise why not and what to use instead. */
    public static String problem(Layout layout, RevealOrder order) {
        if ((layout == Layout.GRID || layout == Layout.WAVE) && (order == CLOCKWISE || order == COUNTERCLOCKWISE)) {
            return "A " + layout.name().toLowerCase(java.util.Locale.ROOT) + " is a flat wall of rows and columns, so it has no clockwise direction. "
                    + "Use left-to-right, bottom-to-top, top-to-bottom or random: /pd set generation order left-to-right.";
        }
        if (layout == Layout.SQUARE && order == LEFT_TO_RIGHT) {
            return "A square goes around instead of left to right. Use clockwise, counterclockwise, bottom-to-top (layer by layer), "
                    + "top-to-bottom or random: /pd set generation order clockwise.";
        }
        return null;
    }

    public RevealOrder resolve(Layout layout) {
        if (this != AUTO) return this;
        return switch (layout) {
            case GRID -> BOTTOM_TO_TOP;
            case WAVE -> LEFT_TO_RIGHT;
            case SQUARE -> CLOCKWISE;
            case RANDOM -> RANDOM;
        };
    }

    /** The plan (in its natural order: row by row, or layer by layer around the ring) put in reveal order. */
    public static List<PortalSpec> arrange(Layout layout, RevealOrder wanted, List<PortalSpec> plan, Info info, Random rnd) {
        RevealOrder order = wanted.resolve(layout);
        if (order == RANDOM) {
            List<PortalSpec> shuffled = new ArrayList<>(plan);
            Collections.shuffle(shuffled, rnd);
            return shuffled;
        }
        return switch (layout) {
            case GRID, WAVE -> wall(plan, order, info);
            case SQUARE -> square(plan, order, info);
            case RANDOM -> spatial(plan, order, info);
        };
    }

    /** How far right a portal is when you stand in front of the wall and look at it. */
    private static double across(PortalSpec p, BlockFace wall) {
        return switch (wall) {
            case NORTH -> -p.centerX();
            case EAST -> -p.centerZ();
            case WEST -> p.centerZ();
            default -> p.centerX();
        };
    }

    private static List<PortalSpec> wall(List<PortalSpec> plan, RevealOrder order, Info info) {
        int cols = Math.max(1, info.columns());
        List<Integer> index = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) index.add(i);
        Comparator<Integer> byAcross = Comparator.comparingDouble(i -> across(plan.get(i), info.wall()));
        Comparator<Integer> byRowUp = Comparator.comparingInt(i -> i / cols);
        Comparator<Integer> byRowDown = (a, b) -> Integer.compare(b / cols, a / cols);
        switch (order) {
            case LEFT_TO_RIGHT -> index.sort(byAcross.thenComparing(byRowUp));
            case TOP_TO_BOTTOM -> index.sort(byRowDown.thenComparing(byAcross));
            default -> index.sort(byRowUp.thenComparing(byAcross));
        }
        List<PortalSpec> out = new ArrayList<>();
        for (int i : index) out.add(plan.get(i));
        return out;
    }

    private static List<PortalSpec> square(List<PortalSpec> plan, RevealOrder order, Info info) {
        int ring = 4 * info.perSide();
        int layers = Math.max(1, info.layers());
        List<PortalSpec> out = new ArrayList<>();
        for (int step = 0; step < layers; step++) {
            int layer = order == TOP_TO_BOTTOM ? layers - 1 - step : step;
            List<PortalSpec> around = new ArrayList<>(plan.subList(layer * ring, Math.min(plan.size(), (layer + 1) * ring)));
            if (order == COUNTERCLOCKWISE) Collections.reverse(around);
            out.addAll(around);
        }
        return out;
    }

    private static List<PortalSpec> spatial(List<PortalSpec> plan, RevealOrder order, Info info) {
        List<PortalSpec> out = new ArrayList<>(plan);
        switch (order) {
            case LEFT_TO_RIGHT -> out.sort(Comparator.comparingDouble(PortalSpec::centerX).thenComparingDouble(PortalSpec::centerZ));
            case BOTTOM_TO_TOP -> out.sort(Comparator.comparingInt(PortalSpec::y).thenComparingDouble(PortalSpec::centerX));
            case TOP_TO_BOTTOM -> out.sort(Comparator.comparingInt(PortalSpec::y).reversed().thenComparingDouble(PortalSpec::centerX));
            default -> {
                Comparator<PortalSpec> angle = Comparator.comparingDouble(p -> {
                    double a = Math.atan2(p.centerX() - info.centerX(), -(p.centerZ() - info.centerZ()));
                    return a < 0 ? a + 2 * Math.PI : a;
                });
                out.sort(order == COUNTERCLOCKWISE ? angle.reversed() : angle);
            }
        }
        return out;
    }
}
