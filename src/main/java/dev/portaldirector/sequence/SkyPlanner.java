package dev.portaldirector.sequence;

import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.GenSettings;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.Placement;
import dev.portaldirector.gen.PortalPlanner;
import dev.portaldirector.gen.Terrain;
import dev.portaldirector.model.PortalSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Plans a cloud of floating portals around a point, and widens its search when the first try is not enough.
 *
 * <p>The first stage uses the area and height the creator asked for. Every later stage keeps what was already placed and
 * looks for the missing portals in a wider area, then in a taller band, then with the gaps between portals narrowed (never
 * below one empty block, so frames never touch). The usable height is always inside the world's build limits, and the
 * area never grows past {@code maxRadius}.
 */
public final class SkyPlanner {

    /** What one attempt may use. */
    public record Stage(String label, int radius, int halfHeight, int spacingH, int spacingV) {
    }

    private SkyPlanner() {
    }

    public static List<Stage> stages(int radius, int halfHeight, int maxRadius) {
        int limit = Math.max(radius, maxRadius);
        Set<Stage> out = new LinkedHashSet<>();
        out.add(new Stage("the area you asked for", radius, halfHeight, 8, 5));
        out.add(new Stage("a wider area", Math.min(limit, radius * 3 / 2), halfHeight, 8, 5));
        out.add(new Stage("a much wider area", Math.min(limit, radius * 2), halfHeight, 8, 5));
        out.add(new Stage("a taller band of sky", Math.min(limit, radius * 2), halfHeight * 2, 8, 5));
        out.add(new Stage("narrower gaps between portals", Math.min(limit, radius * 2), halfHeight * 2, 4, 3));
        out.add(new Stage("the narrowest safe gaps", Math.min(limit, radius * 2), halfHeight * 2, 2, 2));
        return new ArrayList<>(out);
    }

    /** One portal index for every player: all different while there are enough portals, shared evenly when there are not. */
    public static int[] assign(int players, int portals, RandomGenerator rnd) {
        int[] out = new int[players];
        if (portals <= 0) return out;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < portals; i++) order.add(i);
        java.util.Collections.shuffle(order, new java.util.Random(rnd.nextLong()));
        for (int i = 0; i < players; i++) out[i] = order.get(i % portals);
        return out;
    }

    /** Where the k-th of {@code total} players stands inside a portal: the middle alone, otherwise the two interior blocks. */
    public static double[] stand(PortalSpec p, int k, int total) {
        if (total <= 1) return new double[]{p.centerX(), p.centerZ()};
        int cell = 1 + (k % 2);
        double x = p.cellX(cell) + 0.5;
        double z = p.cellZ(cell) + 0.5;
        int round = k / 2;
        if (round > 0) {
            double shift = (round % 2 == 1 ? -0.25 : 0.25);
            if (p.axis() == org.bukkit.Axis.X) x += shift;
            else z += shift;
        }
        return new double[]{x, z};
    }

    /** An incremental plan, a few attempts per tick. */
    public static final class Run {
        private final Terrain terrain;
        private final int cx;
        private final int cy;
        private final int cz;
        private final int need;
        private final List<PortalSpec> existing;
        private final List<PortalSpec> placed = new ArrayList<>();
        private final List<Stage> stages;
        private final RandomGenerator rnd;
        private final List<String> notes = new ArrayList<>();
        private PortalPlanner.Session session;
        private int stage = -1;
        private int nextId;
        private boolean finished;

        public Run(Terrain terrain, int cx, int cy, int cz, int need, List<PortalSpec> existing, int firstId,
                   int radius, int halfHeight, int maxRadius, RandomGenerator rnd) {
            this.terrain = terrain;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.need = need;
            this.existing = new ArrayList<>(existing);
            this.nextId = firstId;
            this.stages = stages(radius, halfHeight, maxRadius);
            this.rnd = rnd;
            begin(0);
        }

        private void begin(int index) {
            stage = index;
            Stage st = stages.get(index);
            GenSettings g = new GenSettings();
            g.count = need - placed.size();
            g.radius = st.radius();
            g.spacingH = st.spacingH();
            g.spacingV = st.spacingV();
            g.placement = Placement.AIR;
            g.layout = Layout.RANDOM;
            g.facing = FacingMode.RANDOM;
            g.altitudeMin = 2;
            g.altitudeMax = 1000;
            g.worldYMin = cy - st.halfHeight();
            g.worldYMax = cy + st.halfHeight() + PortalSpec.HEIGHT - 1;
            List<PortalSpec> blockers = new ArrayList<>(existing);
            blockers.addAll(placed);
            session = new PortalPlanner.Session(terrain, g, cx, cz, blockers, nextId, rnd);
        }

        public boolean done() {
            return finished;
        }

        public void step(int budget) {
            if (finished) return;
            session.step(budget);
            if (!session.done()) return;
            placed.addAll(session.placed());
            nextId += session.placed().size();
            if (placed.size() >= need || stage == stages.size() - 1) {
                finished = true;
                return;
            }
            Stage from = stages.get(stage);
            Stage to = stages.get(stage + 1);
            notes.add(placed.size() + " of " + need + " portals fit in " + from.label() + "; trying " + to.label()
                    + " (radius " + to.radius() + ", height " + (to.halfHeight() * 2) + ", gaps " + to.spacingH() + "/" + to.spacingV() + ")");
            begin(stage + 1);
        }

        public List<PortalSpec> placed() {
            return placed;
        }

        public Stage stage() {
            return stages.get(Math.min(stage, stages.size() - 1));
        }

        public int need() {
            return need;
        }

        /** Messages about each widening, in order. */
        public List<String> notes() {
            return notes;
        }

        /** The widest radius any stage may use, to know which chunks to load. */
        public int widestRadius() {
            int r = 0;
            for (Stage s : stages) r = Math.max(r, s.radius());
            return r;
        }

        /** The radius of the stage being tried now. */
        public int currentRadius() {
            return stages.get(Math.min(stage, stages.size() - 1)).radius();
        }
    }
}
