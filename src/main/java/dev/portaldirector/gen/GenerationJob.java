package dev.portaldirector.gen;

import dev.portaldirector.Settings;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Job;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Generates portals in three steps spread over many ticks: load the chunks of the area,
 * pick positions, then place blocks a few hundred at a time.
 */
public final class GenerationJob implements Job {

    public record Result(int requested, int placed, int rejected, boolean cancelled, boolean failed, String error) {
    }

    private enum Phase { LOAD, PLAN, BUILD, DONE }

    private final Plugin plugin;
    private final World world;
    private final Scene scene;
    private final Settings settings;
    private final PortalPlanner.Session planner;
    private final List<PortalSpec> fixed;
    private final boolean dropOnCancel;
    private final Consumer<Result> onDone;
    private final Deque<long[]> chunksToLoad = new ArrayDeque<>();
    private final Set<Long> ticketed = new HashSet<>();
    private final int totalChunks;
    private int outstandingLoads;
    private int loaded;

    private Phase phase = Phase.LOAD;
    private BukkitTask task;
    private List<PortalSpec> toBuild;
    private int buildIndex;
    private final Deque<PortalEdits.Edit> pending = new ArrayDeque<>();
    private boolean cancelled;
    private String error;

    /** Plans and builds new portals. {@code existing} are all portals already in the world, in any scene. */
    public GenerationJob(Plugin plugin, World world, Scene scene, Settings settings, int cx, int cz,
                         int defaultDx, int defaultDz, List<PortalSpec> existing, Consumer<Result> onDone) {
        this.plugin = plugin;
        this.world = world;
        this.scene = scene;
        this.settings = settings;
        this.onDone = onDone;
        this.fixed = null;
        this.dropOnCancel = true;
        this.planner = new PortalPlanner.Session(new BukkitTerrain(world), settings.gen, cx, cz, defaultDx, defaultDz,
                existing, scene.nextId(), new Random());
        int r = settings.gen.radius + 12;
        for (int chunkX = (cx - r) >> 4; chunkX <= (cx + r) >> 4; chunkX++) {
            for (int chunkZ = (cz - r) >> 4; chunkZ <= (cz + r) >> 4; chunkZ++) chunksToLoad.add(new long[]{chunkX, chunkZ});
        }
        this.totalChunks = chunksToLoad.size();
    }

    /** Places the blocks of portals a scene already has, for example after the world was restored. */
    public GenerationJob(Plugin plugin, World world, Scene scene, Settings settings, Consumer<Result> onDone) {
        this(plugin, world, scene, settings, new ArrayList<>(scene.portals().values()), false, onDone);
    }

    /**
     * Places the blocks of the given portals. With {@code dropOnCancel} the portals that were not finished are
     * removed from the scene again when the job is cancelled (for portals that were just added).
     */
    public GenerationJob(Plugin plugin, World world, Scene scene, Settings settings, List<PortalSpec> specs,
                         boolean dropOnCancel, Consumer<Result> onDone) {
        this.plugin = plugin;
        this.world = world;
        this.scene = scene;
        this.settings = settings;
        this.onDone = onDone;
        this.fixed = new ArrayList<>(specs);
        this.dropOnCancel = dropOnCancel;
        this.planner = null;
        Set<Long> seen = new HashSet<>();
        for (PortalSpec p : fixed) {
            int[] b = p.box();
            for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) {
                for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) {
                    if (seen.add(key(cx, cz))) chunksToLoad.add(new long[]{cx, cz});
                }
            }
        }
        this.totalChunks = chunksToLoad.size();
    }

    /** Number of chunks the area covers, for the safety limit. */
    public int chunkCount() {
        return totalChunks;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @Override
    public String describe() {
        return switch (phase) {
            case LOAD -> "loading chunks (" + loaded + "/" + totalChunks + ")";
            case PLAN -> "choosing positions (" + (planner == null ? 0 : planner.placed().size()) + ")";
            case BUILD -> "building portals (" + buildIndex + "/" + (toBuild == null ? 0 : toBuild.size()) + ")";
            case DONE -> "finished";
        };
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    private void tick() {
        try {
            if (cancelled) {
                finish();
                return;
            }
            switch (phase) {
                case LOAD -> loadStep();
                case PLAN -> planStep();
                case BUILD -> buildStep();
                case DONE -> {
                }
            }
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            plugin.getLogger().warning("Generation failed: " + e);
            cancelled = true;
            finish();
        }
    }

    private void loadStep() {
        while (outstandingLoads < settings.chunkLoadsAtOnce && !chunksToLoad.isEmpty()) {
            long[] c = chunksToLoad.poll();
            int x = (int) c[0];
            int z = (int) c[1];
            outstandingLoads++;
            world.getChunkAtAsync(x, z, true).whenComplete((chunk, err) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        outstandingLoads--;
                        loaded++;
                        if (err == null && ticketed.add(key(x, z))) world.addPluginChunkTicket(x, z, plugin);
                    }));
        }
        if (chunksToLoad.isEmpty() && outstandingLoads == 0) {
            if (fixed != null) {
                toBuild = fixed;
                phase = Phase.BUILD;
            } else {
                phase = Phase.PLAN;
            }
        }
    }

    private void planStep() {
        planner.step(settings.plannerBudget);
        if (!planner.done()) return;
        toBuild = new ArrayList<>(planner.placed());
        for (PortalSpec p : toBuild) scene.portals().put(p.id(), p);
        phase = Phase.BUILD;
    }

    private void buildStep() {
        int budget = settings.blocksPerTick;
        while (budget > 0) {
            if (pending.isEmpty()) {
                if (buildIndex >= toBuild.size()) {
                    finish();
                    return;
                }
                pending.addAll(PortalEdits.forPortal(world, toBuild.get(buildIndex), settings.gen.maxSlope + 1));
                buildIndex++;
            }
            PortalEdits.Edit e = pending.poll();
            if (e == null) continue;
            apply(e);
            budget--;
        }
    }

    private void apply(PortalEdits.Edit e) {
        PortalEdits.apply(world, scene.blocks(), e);
    }

    private void finish() {
        if (phase == Phase.DONE) return;
        boolean wasBuilding = phase == Phase.BUILD;
        phase = Phase.DONE;
        if (task != null) task.cancel();
        int built = toBuild == null ? 0 : toBuild.size();
        if (cancelled && wasBuilding && toBuild != null && dropOnCancel) {
            // The portal being built when cancelled may be half done; keep only the finished ones.
            int keep = Math.max(0, buildIndex - 1);
            for (int i = keep; i < toBuild.size(); i++) scene.dropPortal(toBuild.get(i).id());
            built = keep;
        } else if (cancelled && toBuild != null && dropOnCancel) {
            for (PortalSpec p : toBuild) scene.dropPortal(p.id());
            built = 0;
        }
        for (long k : ticketed) world.removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
        ticketed.clear();
        int requested = planner == null ? built : planner.requested();
        onDone.accept(new Result(requested, built, planner == null ? 0 : planner.rejected(), cancelled, error != null, error));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
