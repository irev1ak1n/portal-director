package dev.portaldirector.scene;

import dev.portaldirector.util.Job;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Puts a scene's blocks back chunk by chunk. A block is restored only while it still holds the block the
 * scene placed, so anything changed afterwards is left exactly as it is.
 */
public final class RemovalJob implements Job {

    public record Result(int restored, int kept, boolean cancelled) {
    }

    private final Plugin plugin;
    private final World world;
    private final Scene scene;
    private final int blocksPerTick;
    private final Consumer<Result> onDone;
    private final Deque<List<BlockLog.Rec>> chunks = new ArrayDeque<>();
    private final int totalBlocks;
    private List<BlockLog.Rec> current;
    private int currentIndex;
    private boolean loading;
    private int waited;
    private final java.util.Set<Long> held = new java.util.HashSet<>();
    private int chunkX;
    private int chunkZ;
    private boolean cancelled;
    private boolean done;
    private int restored;
    private int kept;
    private int processed;
    private BukkitTask task;

    public RemovalJob(Plugin plugin, World world, Scene scene, int blocksPerTick, Consumer<Result> onDone) {
        this(plugin, world, scene, blocksPerTick, new ArrayList<>(scene.blocks().all()), onDone);
    }

    /** Restores only the given records, for removing a single portal. */
    public RemovalJob(Plugin plugin, World world, Scene scene, int blocksPerTick, List<BlockLog.Rec> all,
                      Consumer<Result> onDone) {
        this.plugin = plugin;
        this.world = world;
        this.scene = scene;
        this.blocksPerTick = blocksPerTick;
        this.onDone = onDone;
        Map<Long, List<BlockLog.Rec>> byChunk = new LinkedHashMap<>();
        // Newest first, so layered changes unwind in the right order.
        for (int i = all.size() - 1; i >= 0; i--) {
            BlockLog.Rec r = all.get(i);
            long key = ((long) (r.pos().x() >> 4) << 32) | ((r.pos().z() >> 4) & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        chunks.addAll(byChunk.values());
        totalBlocks = all.size();
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @Override
    public String describe() {
        return "removing blocks: " + processed + " of " + totalBlocks + " done" + (totalBlocks > 0 ? " (" + (100 * processed / totalBlocks) + "%)" : "");
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    private void tick() {
        if (done) return;
        if (cancelled) {
            finish();
            return;
        }
        int budget = blocksPerTick;
        while (budget > 0) {
            if (current == null || currentIndex >= current.size()) {
                if (chunks.isEmpty()) {
                    finish();
                    return;
                }
                current = chunks.poll();
                currentIndex = 0;
                chunkX = current.get(0).pos().x() >> 4;
                chunkZ = current.get(0).pos().z() >> 4;
            }
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                if (!loading || ++waited > 100) {
                    loading = true;
                    waited = 0;
                    final int cx = chunkX;
                    final int cz = chunkZ;
                    world.getChunkAtAsync(cx, cz, true).whenComplete((c, err) ->
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                // The ticket keeps the chunk loaded until its blocks are done, so it cannot unload again.
                                if (err == null && world.addPluginChunkTicket(cx, cz, plugin)) held.add(key(cx, cz));
                                loading = false;
                            }));
                }
                return;
            }
            if (held.contains(key(chunkX, chunkZ))) {
                // already ticketed
            } else if (world.addPluginChunkTicket(chunkX, chunkZ, plugin)) {
                held.add(key(chunkX, chunkZ));
            }
            BlockLog.Rec r = current.get(currentIndex++);
            restoreOne(r);
            budget--;
            processed++;
            if (currentIndex >= current.size()) release(chunkX, chunkZ);
        }
    }

    private void restoreOne(BlockLog.Rec r) {
        Block b = world.getBlockAt(r.pos().x(), r.pos().y(), r.pos().z());
        if (b.getBlockData().getAsString().equals(r.placed())) {
            BlockData original = plugin.getServer().createBlockData(r.original());
            b.setBlockData(original, false);
            restored++;
        } else {
            kept++;
        }
        scene.blocks().remove(r.pos());
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private void release(int cx, int cz) {
        if (held.remove(key(cx, cz))) world.removePluginChunkTicket(cx, cz, plugin);
    }

    private void finish() {
        if (done) return;
        done = true;
        for (long k : new java.util.ArrayList<>(held)) world.removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
        held.clear();
        if (task != null) task.cancel();
        onDone.accept(new Result(restored, kept, cancelled));
    }
}
