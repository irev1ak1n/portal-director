package dev.portaldirector.gen;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Decides where portals go. Pure logic over {@link Terrain}, so it can run a few attempts per tick. */
public final class PortalPlanner {

    /** The tallest step in the ground, across a portal's four columns, a ground portal may stand on. */
    public static final int MAX_GROUND_STEP = 3;
    /** How far below a ground portal's frame the ground may be before it is filled up to meet it. */
    public static final int MAX_SUPPORT = MAX_GROUND_STEP + 1;
    /** Nearby spots tried when a ground spot is unsuitable, before giving that attempt up. */
    private static final int NEARBY_TRIES = 6;
    private static final int NEARBY_REACH = 6;

    private PortalPlanner() {
    }

    /** The y of the frame floor for a ground portal here: level with the highest ground under it, so players can walk in, or {@code Integer.MIN_VALUE} when the ground is unsuitable. */
    public static int groundY(Terrain terrain, PortalSpec p, int maxStep) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int i = 0; i < PortalSpec.WIDTH; i++) {
            int x = p.cellX(i);
            int z = p.cellZ(i);
            int top = terrain.topY(x, z);
            if (top < terrain.minY() || terrain.isLiquid(x, top, z) || !terrain.isNatural(x, top, z)) {
                return Integer.MIN_VALUE;
            }
            lo = Math.min(lo, top);
            hi = Math.max(hi, top);
        }
        if (hi - lo > maxStep) return Integer.MIN_VALUE;
        // The walkway is cleared: ground that rises more than a block there is a hillside, and ground that falls
        // away more than a block in front would leave a step too high to walk up.
        for (int[] c : p.corridorBlocks()) {
            if (c[1] != p.y() + 1) continue;
            int top = terrain.topY(c[0], c[2]);
            if (top > hi + 1) return Integer.MIN_VALUE;
            int ahead = (c[0] - p.cellX(1)) * p.stepX() + (c[2] - p.cellZ(1)) * p.stepZ();
            if (ahead > 0 && top < hi - 1) return Integer.MIN_VALUE;
        }
        return hi;
    }

    /** True when the two blocks above a ground portal's frame are open air, so it is not under a tree or a ledge. */
    public static boolean openAbove(Terrain terrain, PortalSpec p) {
        for (int i = 1; i < PortalSpec.WIDTH - 1; i++) {
            for (int dy = PortalSpec.HEIGHT; dy < PortalSpec.HEIGHT + 2; dy++) {
                if (!terrain.isAir(p.cellX(i), p.y() + dy, p.cellZ(i))) return false;
            }
        }
        return true;
    }

    /** True when everything the portal and its walkway would change is air (floating) or natural (ground). */
    public static boolean volumeFree(Terrain terrain, PortalSpec p, boolean air) {
        List<int[]> cells = new ArrayList<>(p.frameBlocks());
        cells.addAll(p.interiorBlocks());
        cells.addAll(p.corridorBlocks());
        for (int[] c : cells) {
            if (air ? !terrain.isAir(c[0], c[1], c[2]) : !terrain.isNatural(c[0], c[1], c[2])) return false;
        }
        return true;
    }

    /** The lowest and highest frame floor a portal may have, from the world-y limits and the world's own build limits. */
    static int[] floorRange(Terrain terrain, GenSettings s) {
        int lowest = Math.max(s.worldYMin, terrain.minY() + 1);
        int highest = Math.min(s.worldYMax, terrain.maxY() - 2);
        return new int[]{lowest, highest - (PortalSpec.HEIGHT - 1)};
    }

    /**
     * An incremental placement run inside the square around (cx, cz): x and z within the effective radius, with the
     * whole obsidian frame inside it.
     */
    public static final class Session {
        private final Terrain terrain;
        private final GenSettings s;
        private final Bounds bounds;
        private final List<PortalSpec> blockers;
        private final List<PortalSpec> placed = new ArrayList<>();
        private final RandomGenerator rnd;
        private int nextId;
        private int attemptsLeft;
        private int rejected;
        private int floatingPlaced;
        private int groundPlaced;
        private final java.util.Map<String, Integer> reasons = new java.util.LinkedHashMap<>();
        private String lastReason = "";

        public Session(Terrain terrain, GenSettings settings, int cx, int cz,
                       List<PortalSpec> existing, int firstId, RandomGenerator rnd) {
            this.terrain = terrain;
            this.s = settings.copy();
            this.bounds = Bounds.around(cx, cz, s.effectiveRadius());
            this.blockers = new ArrayList<>(existing);
            this.nextId = firstId;
            this.rnd = rnd;
            this.attemptsLeft = s.count * 60 + 300;
        }

        public boolean done() {
            return placed.size() >= s.count || attemptsLeft <= 0;
        }

        /** Tries up to {@code budget} candidate positions. */
        public void step(int budget) {
            while (budget-- > 0 && !done()) {
                attemptsLeft--;
                PortalSpec spec = tryPlace();
                if (spec == null) {
                    rejected++;
                    reasons.merge(lastReason, 1, Integer::sum);
                    continue;
                }
                placed.add(spec);
                blockers.add(spec);
                if (spec.floating()) floatingPlaced++;
                else groundPlaced++;
                nextId++;
            }
        }

        public List<PortalSpec> placed() {
            return placed;
        }

        public int requested() {
            return s.count;
        }

        public int rejected() {
            return rejected;
        }

        /** Why candidate spots were skipped, with counts. */
        public java.util.Map<String, Integer> reasons() {
            return reasons;
        }

        private PortalSpec fail(String reason) {
            lastReason = reason;
            return null;
        }

        private int gapH() {
            return Math.max(1, s.spacingH);
        }

        private int gapV() {
            return Math.max(1, s.spacingV);
        }

        /** Mixed mode aims for half floating and half on the ground by always trying the kind that is behind. */
        private PortalSpec tryPlace() {
            boolean air = switch (s.placement) {
                case GROUND -> false;
                case AIR -> true;
                case MIXED -> floatingPlaced == groundPlaced ? rnd.nextBoolean() : floatingPlaced < groundPlaced;
            };
            PortalSpec spec = attempt(air);
            if (spec == null && s.placement == Placement.MIXED) spec = attempt(!air);
            return spec;
        }

        private PortalSpec attempt(boolean air) {
            BlockFace face = chooseFacing();
            Axis axis = face == BlockFace.NORTH || face == BlockFace.SOUTH ? Axis.X : Axis.Z;
            int span = PortalSpec.WIDTH - 1;
            int loX = bounds.minX();
            int hiX = bounds.maxX() - (axis == Axis.X ? span : 0);
            int loZ = bounds.minZ();
            int hiZ = bounds.maxZ() - (axis == Axis.Z ? span : 0);
            if (hiX < loX || hiZ < loZ) return fail("the radius is too small for a portal");
            int x = loX + rnd.nextInt(hiX - loX + 1);
            int z = loZ + rnd.nextInt(hiZ - loZ + 1);
            PortalSpec spec = tryAt(x, z, axis, face, air);
            if (spec != null || air) return spec;
            // Unsuitable ground (water, a cliff, a building): look for a good surface a few blocks away.
            String why = lastReason;
            for (int i = 0; i < NEARBY_TRIES && spec == null; i++) {
                int nx = Math.max(loX, Math.min(hiX, x + rnd.nextInt(2 * NEARBY_REACH + 1) - NEARBY_REACH));
                int nz = Math.max(loZ, Math.min(hiZ, z + rnd.nextInt(2 * NEARBY_REACH + 1) - NEARBY_REACH));
                spec = tryAt(nx, nz, axis, face, false);
            }
            if (spec == null) lastReason = why;
            return spec;
        }

        /** Checks one exact position; the frame's minimum corner is (x, z). */
        private PortalSpec tryAt(int x, int z, Axis axis, BlockFace face, boolean air) {
            PortalSpec probe = new PortalSpec(nextId, x, 0, z, axis, face, air);
            int[] box = probe.box();
            for (int bx = box[0]; bx <= box[3]; bx++) {
                for (int bz = box[2]; bz <= box[5]; bz++) {
                    if (!terrain.isReady(bx, bz)) return fail("the area was not loaded");
                }
            }
            int y = air ? airY(probe) : PortalPlanner.groundY(terrain, probe, MAX_GROUND_STEP);
            if (y == Integer.MIN_VALUE) {
                if (air) return null;
                return fail("water, steep ground or buildings");
            }
            PortalSpec spec = new PortalSpec(nextId, x, y, z, axis, face, air);
            int[] floors = floorRange(terrain, s);
            if (spec.y() < floors[0]) return fail("below world-y-min");
            if (spec.y() > floors[1]) return fail("above world-y-max");
            if (!bounds.containsFrame(spec)) return fail("outside the radius");
            if (!PortalPlanner.volumeFree(terrain, spec, air)) return fail(air ? "something in the way" : "trees, water or buildings in the way");
            if (!air && !PortalPlanner.openAbove(terrain, spec)) return fail("under a tree or a ledge");
            for (PortalSpec other : blockers) {
                if (!spec.separatedFrom(other, gapH(), gapV())) return fail("too close to another portal");
            }
            return spec;
        }

        /** A floor height above the terrain that satisfies both the altitude range and the world-y limits, or MIN_VALUE. */
        private int airY(PortalSpec p) {
            int[] box = p.box();
            int top = terrain.minY();
            for (int bx = box[0]; bx <= box[3]; bx++) {
                for (int bz = box[2]; bz <= box[5]; bz++) top = Math.max(top, terrain.highestY(bx, bz));
            }
            int lo = Math.max(2, Math.min(s.altitudeMin, s.altitudeMax));
            int hi = Math.max(lo, Math.max(s.altitudeMin, s.altitudeMax));
            int[] floors = floorRange(terrain, s);
            int from = Math.max(top + lo, floors[0]);
            int to = Math.min(top + hi, floors[1]);
            if (from > to) {
                fail(top + lo > floors[1] ? "above world-y-max" : "below world-y-min");
                return Integer.MIN_VALUE;
            }
            return from + rnd.nextInt(to - from + 1);
        }

        private BlockFace chooseFacing() {
            return switch (s.facing) {
                case NORTH -> BlockFace.NORTH;
                case SOUTH -> BlockFace.SOUTH;
                case EAST -> BlockFace.EAST;
                case WEST -> BlockFace.WEST;
                default -> switch (rnd.nextInt(4)) {
                    case 0 -> BlockFace.NORTH;
                    case 1 -> BlockFace.SOUTH;
                    case 2 -> BlockFace.EAST;
                    default -> BlockFace.WEST;
                };
            };
        }
    }
}
