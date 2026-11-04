package dev.portaldirector.gen;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The organised layouts: a vertical grid wall, a staggered wave wall and square perimeters stacked in layers.
 * Nothing here is random except the facing of individual portals when facing is set to random.
 *
 * <p>Vertical placement: the item at index {@code (n - 1) / 2} (the middle one, or the lower of the two middle
 * ones) has its frame floor one block below the center height, so its doorway starts at the center height. The others
 * are stacked above and below it, one row or layer pitch apart.
 */
public final class FormationPlanner {

    /** Height of the wave, in blocks above and below a row's baseline. */
    public static final int WAVE_AMPLITUDE = 2;
    private static final int[] WAVE = {0, 1, 2, 1, 0, -1, -2, -1};
    private static final BlockFace[] CARDINALS = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private FormationPlanner() {
    }

    public static int gridCount(int rows, int columns) {
        return rows * columns;
    }

    public static int squareCount(int perSide, int layers) {
        return 4 * perSide * layers;
    }

    public static int waveCount(int rows, int perRow) {
        return rows * perRow;
    }

    /** Frame floor of item {@code k} out of {@code n}, given the floor of the middle item and the pitch between items. */
    public static int floorOf(int k, int n, int centerFloor, int pitch) {
        return centerFloor + (k - (n - 1) / 2) * pitch;
    }

    /** Offset of item {@code i} out of {@code n} along a line, centered on 0. */
    public static int offsetOf(int i, int n, int pitch) {
        return -((n - 1) * pitch) / 2 + i * pitch;
    }

    private static PortalSpec portal(int id, int cx, int cz, int floorY, BlockFace facing) {
        Axis axis = facing == BlockFace.NORTH || facing == BlockFace.SOUTH ? Axis.X : Axis.Z;
        return PortalSpec.at(id, cx, floorY, cz, axis, facing, true);
    }

    /** The cardinal direction a wall faces: the chosen one, or {@code auto} for everything else but random. */
    public static BlockFace wallFacing(FacingMode mode, BlockFace auto) {
        return switch (mode) {
            case NORTH -> BlockFace.NORTH;
            case SOUTH -> BlockFace.SOUTH;
            case EAST -> BlockFace.EAST;
            case WEST -> BlockFace.WEST;
            default -> auto;
        };
    }

    /**
     * A vertical wall of {@code rows} x {@code columns} portals in a plane through the center, facing one direction.
     * Columns run along x for north/south walls and along z for east/west walls. With random facing the positions stay
     * on the same grid but each portal turns a random way.
     */
    public static List<PortalSpec> grid(int firstId, int cx, int cz, int centerFloor, int rows, int columns,
                                        int gapH, int gapV, FacingMode mode, BlockFace auto, Random rnd) {
        boolean random = mode == FacingMode.RANDOM;
        BlockFace wall = random ? BlockFace.SOUTH : wallFacing(mode, auto);
        int pitchH = (random ? 7 : 4) + gapH;
        List<PortalSpec> out = new ArrayList<>();
        int id = firstId;
        for (int r = 0; r < rows; r++) {
            int floor = floorOf(r, rows, centerFloor, 5 + gapV);
            for (int c = 0; c < columns; c++) {
                out.add(placeInWall(id++, cx, cz, offsetOf(c, columns, pitchH), floor, wall, random, rnd));
            }
        }
        return out;
    }

    /**
     * Rows of portals that follow a wave: every row rises and falls by {@link #WAVE_AMPLITUDE} blocks along its length,
     * neighbouring rows are half a wave apart and shifted sideways by half a portal, so it clearly differs from a grid.
     */
    public static List<PortalSpec> wave(int firstId, int cx, int cz, int centerFloor, int rows, int perRow,
                                        int gapH, int gapV, FacingMode mode, BlockFace auto, Random rnd) {
        boolean random = mode == FacingMode.RANDOM;
        BlockFace wall = random ? BlockFace.SOUTH : wallFacing(mode, auto);
        int pitchH = (random ? 7 : 4) + gapH;
        int rowPitch = 5 + gapV + 2 * WAVE_AMPLITUDE;
        List<PortalSpec> out = new ArrayList<>();
        int id = firstId;
        for (int r = 0; r < rows; r++) {
            int base = floorOf(r, rows, centerFloor, rowPitch);
            int shift = r % 2 == 0 ? -pitchH / 4 : pitchH / 4;
            for (int i = 0; i < perRow; i++) {
                int floor = base + WAVE[(i + 4 * (r % 2)) % WAVE.length];
                out.add(placeInWall(id++, cx, cz, offsetOf(i, perRow, pitchH) + shift, floor, wall, random, rnd));
            }
        }
        return out;
    }

    private static PortalSpec placeInWall(int id, int cx, int cz, int along, int floor, BlockFace wall, boolean random, Random rnd) {
        BlockFace face = random ? CARDINALS[rnd.nextInt(4)] : wall;
        boolean alongX = wall == BlockFace.NORTH || wall == BlockFace.SOUTH;
        return portal(id, alongX ? cx + along : cx, alongX ? cz : cz + along, floor, face);
    }

    /**
     * {@code layers} identical squares stacked vertically, {@code perSide} portals on each side, ids layer by layer and
     * clockwise from the north-west corner in each layer.
     */
    public static List<PortalSpec> square(int firstId, int cx, int cz, int centerFloor, int perSide, int layers,
                                          int gapH, int gapV, SquareFacing facing) {
        int size = SquarePlanner.sizeFor(perSide, gapH);
        int x0 = cx - size / 2;
        int z0 = cz - size / 2;
        List<PortalSpec> out = new ArrayList<>();
        int id = firstId;
        for (int k = 0; k < layers; k++) {
            int floor = floorOf(k, layers, centerFloor, 5 + gapV);
            for (PortalSpec p : SquarePlanner.layout(id, x0, z0, perSide, gapH, facing)) {
                out.add(new PortalSpec(p.id(), p.x(), floor, p.z(), p.axis(), p.facing(), true));
            }
            id += 4 * perSide;
        }
        return out;
    }

    public static int lowestFloor(List<PortalSpec> specs) {
        int low = Integer.MAX_VALUE;
        for (PortalSpec p : specs) low = Math.min(low, p.y());
        return low;
    }

    public static List<PortalSpec> shift(List<PortalSpec> specs, int dy) {
        List<PortalSpec> out = new ArrayList<>();
        for (PortalSpec p : specs) out.add(new PortalSpec(p.id(), p.x(), p.y() + dy, p.z(), p.axis(), p.facing(), p.floating()));
        return out;
    }

    /** Portals that can be built, and a reason for each one that cannot. */
    public record Fit(List<PortalSpec> usable, List<String> problems) {
    }

    /**
     * Checks every portal: the area is loaded, it is inside the build limits, nothing a player built is in the way
     * (natural blocks are simply replaced), and no portal in the world is too close. In ground mode the lowest portals
     * sit on the terrain and are flagged floating only when the ground is too far below to be filled in.
     */
    public static Fit check(Terrain terrain, List<PortalSpec> specs, int minY, int maxY, List<PortalSpec> existing,
                            boolean ground, int support, int lowestFloor) {
        List<PortalSpec> usable = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int lowest = Math.max(minY, terrain.minY() + 1);
        int highest = Math.min(maxY, terrain.maxY() - 2);
        for (PortalSpec raw : specs) {
            String where = "portal " + raw.id() + " at " + raw.anchorX() + " " + raw.anchorY() + " " + raw.anchorZ();
            int[] b = raw.box();
            boolean ready = true;
            for (int x = b[0]; x <= b[3] && ready; x++) {
                for (int z = b[2]; z <= b[5] && ready; z++) ready = terrain.isReady(x, z);
            }
            if (!ready) {
                problems.add(where + ": the area is not loaded");
                continue;
            }
            boolean floating = true;
            if (ground && raw.y() == lowestFloor) {
                int top = Integer.MIN_VALUE;
                boolean wet = false;
                for (int i = 0; i < PortalSpec.WIDTH; i++) {
                    int x = raw.cellX(i);
                    int z = raw.cellZ(i);
                    int t = terrain.topY(x, z);
                    top = Math.max(top, t);
                    wet |= terrain.isLiquid(x, t, z);
                }
                if (wet) {
                    problems.add(where + ": there is water here");
                    continue;
                }
                floating = raw.y() - 1 - top > support;
            }
            PortalSpec p = new PortalSpec(raw.id(), raw.x(), raw.y(), raw.z(), raw.axis(), raw.facing(), floating);
            if (p.y() < lowest || p.y() + PortalSpec.HEIGHT - 1 > highest) {
                problems.add(where + ": outside world-y-min / world-y-max (frames must stay between y " + lowest + " and " + highest + ")");
                continue;
            }
            if (!PortalPlanner.volumeFree(terrain, p, false)) {
                problems.add(where + ": something built is in the way");
                continue;
            }
            PortalSpec clash = null;
            for (PortalSpec other : existing) {
                if (!p.separatedFrom(other, 1)) {
                    clash = other;
                    break;
                }
            }
            if (clash != null) {
                problems.add(where + ": too close to existing portal " + clash.id());
                continue;
            }
            usable.add(p);
        }
        return new Fit(usable, problems);
    }
}
