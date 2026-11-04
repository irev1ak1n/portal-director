package dev.portaldirector.gen;

import dev.portaldirector.Settings;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Job;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Generates portals: load the chunks of the area, pick positions (random layout), then build. Instant generation places
 * blocks a few hundred per tick. Delayed generation builds one complete portal every {@code generation delay} seconds,
 * in reveal order. A portal joins the scene the moment it is built, so a stopped or interrupted sequence always leaves
 * exactly the portals that exist tracked, with their blocks logged for removal.
 */
public final class GenerationJob implements Job {

    public record Result(int requested, int placed, int rejected, boolean cancelled, boolean failed, String error,
                         Map<String, Integer> reasons) {
    }

    private enum Phase { LOAD, PLAN, BUILD, DONE }

    private final Plugin plugin;
    private final World world;
    private final Scene scene;
    private final Settings settings;
    private final PortalPlanner.Session planner;
    private final List<PortalSpec> fixed;
    private final int centerX;
    private final int centerZ;
    private final Consumer<Result> onDone;
    private final Deque<long[]> chunksToLoad = new ArrayDeque<>();
    private final Set<Long> ticketed = new HashSet<>();
    private final int totalChunks;
    private int outstandingLoads;
    private int loaded;

    private Phase phase = Phase.LOAD;
    private BukkitTask task;
    private List<PortalSpec> toBuild = List.of();
    private int buildIndex;
    private int completed;
    private PortalSpec current;
    private long buildTicks;
    private long lastSave;
    private Runnable autosave;
    private final Deque<PortalEdits.Edit> pending = new ArrayDeque<>();
    private boolean cancelled;
    private String error;

    /** Plans and builds new portals. {@code existing} are all portals already in the world, in any scene. */
    public GenerationJob(Plugin plugin, World world, Scene scene, Settings settings, int cx, int cz,
                         List<PortalSpec> existing, Consumer<Result> onDone) {
        this.plugin = plugin;
        this.world = world;
        this.scene = scene;
        this.settings = settings;
        this.onDone = onDone;
        this.fixed = null;
        this.centerX = cx;
        this.centerZ = cz;
        this.planner = new PortalPlanner.Session(new BukkitTerrain(world), settings.gen, cx, cz,
                existing, scene.nextId(), new Random());
        int r = settings.gen.effectiveRadius() + 8;
        for (int chunkX = (cx - r) >> 4; chunkX <= (cx + r) >> 4; chunkX++) {
            for (int chunkZ = (cz - r) >> 4; chunkZ <= (cz + r) >> 4; chunkZ++) chunksToLoad.add(new long[]{chunkX, chunkZ});
        }
        this.totalChunks = chunksToLoad.size();
    }

    /** Builds portals that were already planned and checked, in the order given. */
    public GenerationJob(Plugin plugin, World world, Scene scene, Settings settings, List<PortalSpec> specs, Consumer<Result> onDone) {
        this.plugin = plugin;
        this.world = world;
        this.scene = scene;
        this.settings = settings;
        this.onDone = onDone;
        this.fixed = new ArrayList<>(specs);
        this.planner = null;
        this.centerX = 0;
        this.centerZ = 0;
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

    /** Called now and then while a delayed sequence runs, so a crash loses little. */
    public void autosave(Runnable saver) {
        this.autosave = saver;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @Override
    public String describe() {
        return switch (phase) {
            case LOAD -> "loading the area: " + loaded + " of " + totalChunks + " chunks";
            case PLAN -> "finding spots: " + (planner == null ? 0 : planner.placed().size()) + " portals so far";
            case BUILD -> completed + "/" + toBuild.size() + " portals created";
            case DONE -> "finished";
        };
    }

    @Override
    public void cancel() {
        cancelled = true;
        // A server that is shutting down never runs another tick, so finish the bookkeeping right away.
        if (plugin.getServer().isStopping()) finish();
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
        List<PortalSpec> placed = new ArrayList<>(planner.placed());
        if (settings.gen.generation == GenerationMode.DELAYED) {
            RevealOrder.Info info = new RevealOrder.Info(BlockFace.SOUTH, 1, 0, 1, centerX, centerZ);
            placed = RevealOrder.arrange(Layout.RANDOM, settings.gen.order, placed, info, new Random());
        }
        toBuild = placed;
        phase = Phase.BUILD;
    }

    private void buildStep() {
        if (settings.gen.generation == GenerationMode.DELAYED) delayedStep();
        else instantStep();
    }

    /** Portal number k appears k delays after the first one, which appears at once. */
    private void delayedStep() {
        double delayTicks = Math.max(1.0, settings.gen.delay * 20.0);
        while (buildIndex < toBuild.size() && buildTicks >= (long) Math.floor(buildIndex * delayTicks)) {
            PortalSpec p = toBuild.get(buildIndex++);
            for (PortalEdits.Edit e : PortalEdits.forPortal(world, p, PortalPlanner.MAX_SUPPORT)) PortalEdits.apply(world, scene.blocks(), e);
            scene.addPortal(p);
            completed++;
            if (settings.gen.effects) reveal(p);
            break;
        }
        buildTicks++;
        if (autosave != null && buildTicks - lastSave >= 40) {
            lastSave = buildTicks;
            autosave.run();
        }
        if (buildIndex >= toBuild.size()) finish();
    }

    private void reveal(PortalSpec p) {
        Location at = new Location(world, p.centerX(), p.standY() + 1.5, p.centerZ());
        world.playSound(at, Sound.BLOCK_PORTAL_AMBIENT, 0.5f, 1.6f);
        world.spawnParticle(Particle.PORTAL, at, 40, 0.6, 1.0, 0.6, 0.4);
    }

    private void instantStep() {
        int budget = settings.blocksPerTick;
        while (budget > 0) {
            if (pending.isEmpty()) {
                if (current != null) {
                    completed++;
                    current = null;
                }
                if (buildIndex >= toBuild.size()) {
                    finish();
                    return;
                }
                current = toBuild.get(buildIndex++);
                scene.addPortal(current);
                pending.addAll(PortalEdits.forPortal(world, current, PortalPlanner.MAX_SUPPORT));
            }
            PortalEdits.Edit e = pending.poll();
            if (e == null) continue;
            PortalEdits.apply(world, scene.blocks(), e);
            budget--;
        }
    }

    private void finish() {
        if (phase == Phase.DONE) return;
        phase = Phase.DONE;
        if (task != null) task.cancel();
        if (current != null && !pending.isEmpty()) {
            // The portal being built when the job stopped is only partly there; leave it out of the scene. Its blocks stay
            // in the log, so clearing still puts the ground back.
            scene.dropPortal(current.id());
        } else if (current != null) {
            completed++;
        }
        current = null;
        for (long k : ticketed) world.removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
        ticketed.clear();
        onDone.accept(new Result(planner != null ? planner.requested() : toBuild.size(), completed, planner == null ? 0 : planner.rejected(), cancelled, error != null, error,
                planner == null ? Map.of() : planner.reasons()));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
