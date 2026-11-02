package dev.portaldirector.gen;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.random.RandomGenerator;

/** Decides where portals go. Pure logic over {@link Terrain}, so it can run a few attempts per tick. */
public final class PortalPlanner {

    private PortalPlanner() {
    }

    /**
     * An incremental placement run. {@code defaultDx/defaultDz} is the leaving direction used by the
     * automatic facing of grid and wave layouts.
     */
    public static final class Session {
        private final Terrain terrain;
        private final GenSettings s;
        private final int cx;
        private final int cz;
        private final int defaultDx;
        private final int defaultDz;
        private final List<PortalSpec> blockers;
        private final List<PortalSpec> placed = new ArrayList<>();
        private final RandomGenerator rnd;
        private final Iterator<int[]> candidates;
        private int nextId;
        private int attemptsLeft;
        private int rejected;

        public Session(Terrain terrain, GenSettings settings, int cx, int cz, int defaultDx, int defaultDz,
                       List<PortalSpec> existing, int firstId, RandomGenerator rnd) {
            this.terrain = terrain;
            this.s = settings.copy();
            this.cx = cx;
            this.cz = cz;
            this.defaultDx = defaultDx;
            this.defaultDz = defaultDz;
            this.blockers = new ArrayList<>(existing);
            this.nextId = firstId;
            this.rnd = rnd;
            this.candidates = candidates();
            this.attemptsLeft = s.layout == Layout.RANDOM ? s.count * 60 + 300 : Integer.MAX_VALUE;
        }

        public boolean done() {
            return placed.size() >= s.count || attemptsLeft <= 0 || !candidates.hasNext();
        }

        /** Tries up to {@code budget} candidate positions. */
        public void step(int budget) {
            while (budget-- > 0 && !done()) {
                attemptsLeft--;
                int[] c = candidates.next();
                PortalSpec spec = tryPlace(c[0], c[1]);
                if (spec == null) {
                    rejected++;
                    continue;
                }
                placed.add(spec);
                blockers.add(spec);
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

        private int gap() {
            return Math.max(1, s.spacing);
        }

        private int pitch() {
            return 5 + gap();
        }

        private PortalSpec tryPlace(int px, int pz) {
            boolean air = switch (s.placement) {
                case GROUND -> false;
                case AIR -> true;
                case MIXED -> rnd.nextDouble() < s.airRatio;
            };
            PortalSpec spec = attempt(px, pz, air);
            if (spec == null && s.placement == Placement.MIXED) spec = attempt(px, pz, !air);
            return spec;
        }

        private PortalSpec attempt(int px, int pz, boolean air) {
            double[] dir = direction(px, pz);
            Axis axis = chooseAxis();
            BlockFace face = chooseFacing(axis, dir[0], dir[1]);
            int x = axis == Axis.X ? px - 2 : px;
            int z = axis == Axis.Z ? pz - 2 : pz;
            PortalSpec probe = new PortalSpec(nextId, x, 0, z, axis, face, air);
            int[] box = probe.box();
            for (int bx = box[0]; bx <= box[3]; bx++) {
                for (int bz = box[2]; bz <= box[5]; bz++) {
                    if (!terrain.isReady(bx, bz)) return null;
                }
            }
            int y = air ? airY(probe) : groundY(probe);
            if (y == Integer.MIN_VALUE) return null;
            PortalSpec spec = new PortalSpec(nextId, x, y, z, axis, face, air);
            if (spec.y() < terrain.minY() + 1 || spec.y() + PortalSpec.HEIGHT >= terrain.maxY() - 1) return null;
            if (!volumeFree(spec, air)) return null;
            for (PortalSpec other : blockers) {
                if (!spec.separatedFrom(other, gap())) return null;
            }
            return spec;
        }

        private int groundY(PortalSpec p) {
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
            if (hi - lo > s.maxSlope) return Integer.MIN_VALUE;
            return hi + 1;
        }

        private int airY(PortalSpec p) {
            int[] box = p.box();
            int top = terrain.minY();
            for (int bx = box[0]; bx <= box[3]; bx++) {
                for (int bz = box[2]; bz <= box[5]; bz++) top = Math.max(top, terrain.topY(bx, bz));
            }
            int lo = Math.max(2, Math.min(s.heightMin, s.heightMax));
            int hi = Math.max(lo, Math.max(s.heightMin, s.heightMax));
            return top + lo + rnd.nextInt(hi - lo + 1);
        }

        private boolean volumeFree(PortalSpec p, boolean air) {
            List<int[]> cells = new ArrayList<>(p.frameBlocks());
            cells.addAll(p.interiorBlocks());
            cells.addAll(p.corridorBlocks());
            for (int[] c : cells) {
                if (air ? !terrain.isAir(c[0], c[1], c[2]) : !terrain.isNatural(c[0], c[1], c[2])) return false;
            }
            return true;
        }

        private Axis chooseAxis() {
            return switch (s.orientation) {
                case X -> Axis.X;
                case Z -> Axis.Z;
                case RANDOM -> switch (s.facing) {
                    case NORTH, SOUTH -> Axis.X;
                    case EAST, WEST -> Axis.Z;
                    default -> rnd.nextBoolean() ? Axis.X : Axis.Z;
                };
            };
        }

        private BlockFace chooseFacing(Axis axis, double dx, double dz) {
            if (axis == Axis.X) return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }

        /** Desired leaving direction at a position, as a vector. */
        private double[] direction(int px, int pz) {
            FacingMode mode = s.facing;
            if (mode == FacingMode.AUTO) {
                if (s.layout == Layout.GRID || s.layout == Layout.WAVE) return new double[]{defaultDx, defaultDz};
                mode = FacingMode.INWARD;
            }
            return switch (mode) {
                case INWARD -> centerOr(cx - px, cz - pz);
                case OUTWARD -> centerOr(px - cx, pz - cz);
                case RANDOM -> switch (rnd.nextInt(4)) {
                    case 0 -> new double[]{0, -1};
                    case 1 -> new double[]{0, 1};
                    case 2 -> new double[]{1, 0};
                    default -> new double[]{-1, 0};
                };
                case NORTH -> new double[]{0, -1};
                case SOUTH -> new double[]{0, 1};
                case EAST -> new double[]{1, 0};
                case WEST -> new double[]{-1, 0};
                default -> new double[]{defaultDx, defaultDz};
            };
        }

        private double[] centerOr(double dx, double dz) {
            return dx == 0 && dz == 0 ? new double[]{0, 1} : new double[]{dx, dz};
        }

        private Iterator<int[]> candidates() {
            return switch (s.layout) {
                case RANDOM -> new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public int[] next() {
                        double a = rnd.nextDouble() * Math.PI * 2;
                        double r = s.radius * Math.sqrt(rnd.nextDouble());
                        return new int[]{cx + (int) Math.round(Math.cos(a) * r), cz + (int) Math.round(Math.sin(a) * r)};
                    }
                };
                case GRID -> gridCandidates().iterator();
                case CIRCLE -> circleCandidates().iterator();
                case WAVE -> waveCandidates().iterator();
            };
        }

        private List<int[]> gridCandidates() {
            int p = pitch();
            int n = s.radius / p;
            List<int[]> out = new ArrayList<>();
            for (int gx = -n; gx <= n; gx++) {
                for (int gz = -n; gz <= n; gz++) {
                    if (Math.hypot(gx * p, gz * p) <= s.radius) out.add(new int[]{cx + gx * p, cz + gz * p});
                }
            }
            out.sort(Comparator.comparingDouble((int[] c) -> Math.hypot(c[0] - cx, c[1] - cz))
                    .thenComparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
            return out;
        }

        private List<int[]> circleCandidates() {
            int p = pitch();
            int radius = Math.max(s.radius, p);
            List<int[]> wanted = new ArrayList<>();
            List<int[]> spare = new ArrayList<>();
            int remaining = s.count;
            for (int r = radius; r >= p; r -= p) {
                int cap = Math.max(1, (int) (2 * Math.PI * r / p));
                int n = Math.min(remaining, cap);
                for (int k = 0; k < n; k++) wanted.add(ringPoint(r, k, n));
                for (int k = 0; k < cap; k++) spare.add(ringPoint(r, k, cap));
                remaining -= n;
            }
            wanted.addAll(spare);
            return wanted;
        }

        private int[] ringPoint(int r, int k, int n) {
            double a = 2 * Math.PI * k / n;
            return new int[]{cx + (int) Math.round(Math.cos(a) * r), cz + (int) Math.round(Math.sin(a) * r)};
        }

        private List<int[]> waveCandidates() {
            int p = pitch();
            int perRow = Math.max(1, Math.min(s.count, 2 * s.radius / p + 1));
            int amplitude = Math.max(2, p / 2);
            List<int[]> out = new ArrayList<>();
            int rows = (int) Math.ceil(s.count * 1.5 / perRow) + 1;
            for (int row = 0; row < rows; row++) {
                int rowZ = (row % 2 == 0 ? 1 : -1) * ((row + 1) / 2) * (2 * amplitude + p);
                for (int k = 0; k < perRow; k++) {
                    int px = cx + Math.round((k - (perRow - 1) / 2f) * p);
                    int pz = cz + rowZ + (int) Math.round(Math.sin(k * Math.PI / 3) * amplitude);
                    out.add(new int[]{px, pz});
                }
            }
            return out;
        }
    }
}
