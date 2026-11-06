package dev.portaldirector.sequence;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.gen.BukkitTerrain;
import dev.portaldirector.gen.PortalEdits;
import dev.portaldirector.gen.PortalPlanner;
import dev.portaldirector.gen.Terrain;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Msg;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The engine behind sky-army and tnt-strike: load the area, find places for floating portals (widening the search if they do
 * not all fit), then play a schedule in which each portal is built at its own moment. What happens next to each portal (a
 * player appearing, a minecart leaving) is up to the {@link Strategy}.
 *
 * <p>Every portal joins the scene as it is built, with its blocks logged, so stopping at any moment leaves exactly the portals
 * that exist tracked and removable.
 */
public final class FloatingRun implements SequenceManager.Active {

    /** What a particular sequence does with the portals. */
    public interface Strategy {
        /** Portals wanted. */
        int need();

        /** The start of every portal's name, such as "sky-army". */
        String prefix();

        int radius();

        int halfHeight();

        /** Before any planning. */
        void begin(FloatingRun run);

        /** The schedule for the portals that were found. */
        List<SeqTimeline.Event> timeline(FloatingRun run, List<PortalSpec> placed, RandomGenerator rnd);

        /** A PLAYER or LAUNCH event; the portal it belongs to already stands. */
        void onEvent(FloatingRun run, SeqTimeline.Event event);

        /** Short counts for progress lines and the action bar. */
        String progress(FloatingRun run);

        /** Called once, when the run ends for any reason. */
        void finish(FloatingRun run, boolean cancelled, String error);
    }

    private enum Phase { LOAD, PLAN, RUN, DONE }

    /** {@link Terrain} that treats everything outside the world border as not ready. */
    private static final class Bordered implements Terrain {
        private final BukkitTerrain inner;
        private final World world;

        Bordered(World world) {
            this.inner = new BukkitTerrain(world);
            this.world = world;
        }

        @Override
        public int minY() {
            return inner.minY();
        }

        @Override
        public int maxY() {
            return inner.maxY();
        }

        @Override
        public boolean isReady(int x, int z) {
            return inner.isReady(x, z) && world.getWorldBorder().isInside(new Location(world, x + 0.5, 64, z + 0.5));
        }

        @Override
        public int topY(int x, int z) {
            return inner.topY(x, z);
        }

        @Override
        public int highestY(int x, int z) {
            return inner.highestY(x, z);
        }

        @Override
        public boolean isLiquid(int x, int y, int z) {
            return inner.isLiquid(x, y, z);
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return inner.isAir(x, y, z);
        }

        @Override
        public boolean isNatural(int x, int y, int z) {
            return inner.isNatural(x, y, z);
        }
    }

    private final PortalDirectorPlugin pl;
    private final SequenceManager mgr;
    private final CommandSender sender;
    private final String sequence;
    private final World world;
    private final Scene scene;
    private final int cx;
    private final int cy;
    private final int cz;
    private final Strategy strategy;
    private final RandomGenerator rnd = new Random();
    private final SkyPlanner.Run planner;
    private final Deque<long[]> chunksToLoad = new ArrayDeque<>();
    private final Set<Long> requested = new HashSet<>();
    private final Set<Long> ticketed = new HashSet<>();
    private int outstanding;
    private int loaded;
    private int totalChunks;
    private int loadedRadius;

    private Phase phase = Phase.LOAD;
    private BukkitTask task;
    private List<PortalSpec> placed = List.of();
    private List<SeqTimeline.Event> events = List.of();
    private boolean[] built = new boolean[0];
    private String[] names = new String[0];
    private int eventIndex;
    private int builtCount;
    private int tick;
    private int notesShown;
    private int milestone;
    private boolean cancelled;
    private String error;

    public FloatingRun(PortalDirectorPlugin pl, SequenceManager mgr, CommandSender sender, String sequence, World world, Scene scene,
                       int cx, int cy, int cz, Strategy strategy) {
        this.pl = pl;
        this.mgr = mgr;
        this.sender = sender;
        this.sequence = sequence;
        this.world = world;
        this.scene = scene;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.strategy = strategy;
        List<PortalSpec> existing = new ArrayList<>(pl.scenes().portalsIn(world.getName()));
        this.planner = new SkyPlanner.Run(new Bordered(world), cx, cy, cz, strategy.need(), existing, scene.nextId(),
                strategy.radius(), strategy.halfHeight(), maxRadiusFor(pl.settings().maxChunks), rnd);
        request(planner.currentRadius());
    }

    // Sizes

    /** Chunks a square of this radius (plus the automatic margin and a few blocks of room) covers, at worst. */
    public static int chunksFor(int radius) {
        int r = radius * 105 / 100 + 8;
        int span = (2 * r) / 16 + 2;
        return span * span;
    }

    /** The widest radius that stays within the chunk limit, never more than 400. */
    public static int maxRadiusFor(int maxChunks) {
        int r = 8;
        while (r < 400 && chunksFor(r + 8) <= maxChunks) r += 8;
        return r;
    }

    // Access for strategies

    public World world() {
        return world;
    }

    public Scene scene() {
        return scene;
    }

    public CommandSender sender() {
        return sender;
    }

    public PortalSpec portal(int index) {
        return placed.get(index);
    }

    public boolean isBuilt(int index) {
        return index >= 0 && index < built.length && built[index];
    }

    public String portalName(int index) {
        return names[index];
    }

    /** The tick of the schedule being played, counted from the first portal. */
    public int currentTick() {
        return tick;
    }

    public int builtCount() {
        return builtCount;
    }

    public int portalCount() {
        return placed.size();
    }

    public List<String> shortfallNotes() {
        return planner.notes();
    }

    public SkyPlanner.Run planner() {
        return planner;
    }

    // Running

    public void start() {
        strategy.begin(this);
        mgr.active(this);
        pl.activeJob(this);
        task = pl.getServer().getScheduler().runTaskTimer(pl, this::tick, 1L, 1L);
    }

    @Override
    public String sequence() {
        return sequence;
    }

    @Override
    public String describe() {
        return switch (phase) {
            case LOAD -> sequence + ": loading the area, " + loaded + " of " + totalChunks + " chunks";
            case PLAN -> sequence + ": finding spots, " + planner.placed().size() + " of " + strategy.need() + " portals so far";
            case RUN -> sequence + ": " + strategy.progress(this);
            case DONE -> sequence + ": finished";
        };
    }

    @Override
    public void cancel() {
        if (phase == Phase.DONE) return;
        cancelled = true;
        finish();
    }

    private void tick() {
        try {
            switch (phase) {
                case LOAD -> loadStep();
                case PLAN -> planStep();
                case RUN -> runStep();
                case DONE -> {
                }
            }
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            pl.getLogger().warning(sequence + " failed: " + e);
            e.printStackTrace();
            cancelled = true;
            finish();
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Queues the chunks of a square around the center that were not asked for yet. */
    private void request(int radius) {
        int r = radius * 105 / 100 + 8;
        for (int x = (cx - r) >> 4; x <= (cx + r) >> 4; x++) {
            for (int z = (cz - r) >> 4; z <= (cz + r) >> 4; z++) {
                if (requested.add(key(x, z))) {
                    chunksToLoad.add(new long[]{x, z});
                    totalChunks++;
                }
            }
        }
        loadedRadius = Math.max(loadedRadius, radius);
        phase = Phase.LOAD;
    }

    private void loadStep() {
        while (outstanding < pl.settings().chunkLoadsAtOnce && !chunksToLoad.isEmpty()) {
            long[] c = chunksToLoad.poll();
            int x = (int) c[0];
            int z = (int) c[1];
            outstanding++;
            world.getChunkAtAsync(x, z, true).whenComplete((chunk, err) ->
                    pl.getServer().getScheduler().runTask(pl, () -> {
                        outstanding--;
                        loaded++;
                        if (err == null && ticketed.add(key(x, z))) world.addPluginChunkTicket(x, z, pl);
                    }));
        }
        if (chunksToLoad.isEmpty() && outstanding == 0) phase = Phase.PLAN;
    }

    private void planStep() {
        if (planner.currentRadius() > loadedRadius) {
            request(planner.currentRadius());
            return;
        }
        planner.step(pl.settings().plannerBudget);
        List<String> notes = planner.notes();
        while (notesShown < notes.size()) Msg.info(sender, sequence + ": " + notes.get(notesShown++));
        if (!planner.done()) return;
        placed = new ArrayList<>(planner.placed());
        if (placed.isEmpty()) {
            error = "No floating portal fits around " + cx + " " + cy + " " + cz + ": " + why();
            finish();
            return;
        }
        built = new boolean[placed.size()];
        names = new String[placed.size()];
        events = strategy.timeline(this, placed, rnd);
        phase = Phase.RUN;
        tick = 0;
        eventIndex = 0;
        Msg.ok(sender, sequence + ": " + placed.size() + " portal" + (placed.size() == 1 ? "" : "s") + " found"
                + (placed.size() < strategy.need() ? " (" + strategy.need() + " wanted)" : "") + ". Starting now.");
    }

    private String why() {
        return "the sky around it is blocked, or it is outside the world's build height or border. Try another center or a different height.";
    }

    private void runStep() {
        while (eventIndex < events.size() && events.get(eventIndex).tick() <= tick) {
            SeqTimeline.Event e = events.get(eventIndex++);
            if (e.kind() == SeqTimeline.Kind.PORTAL) build(e.index());
            else strategy.onEvent(this, e);
        }
        tick++;
        if (tick % 40 == 0) pl.scenes().save(scene);
        int step = Math.max(1, placed.size() / 4);
        if (builtCount >= (milestone + 1) * step && milestone < 3 && builtCount < placed.size()) {
            milestone++;
            Msg.info(sender, sequence + ": " + strategy.progress(this));
        }
        if (eventIndex >= events.size()) finish();
    }

    private void build(int index) {
        PortalSpec p = placed.get(index);
        for (PortalEdits.Edit e : PortalEdits.forPortal(world, p, PortalPlanner.MAX_SUPPORT)) PortalEdits.apply(world, scene.blocks(), e);
        int n = 1;
        String name;
        do {
            name = strategy.prefix() + "-" + n++;
        } while (scene.byName(name) != null);
        scene.worldName(world.getName());
        scene.addPortal(p, name);
        names[index] = name;
        built[index] = true;
        builtCount++;
        pl.getLogger().info(sequence + " tick " + tick + ": portal " + name + " built facing " + p.facing().name().toLowerCase(java.util.Locale.ROOT));
    }

    private void finish() {
        if (phase == Phase.DONE) return;
        phase = Phase.DONE;
        if (task != null) task.cancel();
        for (long k : ticketed) world.removePluginChunkTicket((int) (k >> 32), (int) k, pl);
        ticketed.clear();
        pl.scenes().select(scene);
        pl.scenes().save(scene);
        pl.saveSettings();
        if (pl.activeJob() == this) pl.activeJob(null);
        mgr.active(null);
        try {
            strategy.finish(this, cancelled, error);
        } catch (RuntimeException e) {
            pl.getLogger().warning(sequence + " cleanup failed: " + e);
            e.printStackTrace();
        }
    }

    public String error() {
        return error;
    }
}
