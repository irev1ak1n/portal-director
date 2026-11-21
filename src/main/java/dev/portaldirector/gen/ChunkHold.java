package dev.portaldirector.gen;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The plugin chunk tickets one job holds. After {@link #releaseAll} nothing can be added, so a chunk load that finishes
 * after the job was cancelled cannot leave a ticket behind.
 */
public final class ChunkHold {

    private static final AtomicInteger TOTAL = new AtomicInteger();

    private final Plugin plugin;
    private final World world;
    private final Set<Long> held = new HashSet<>();
    private boolean released;

    public ChunkHold(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    public static int total() {
        return TOTAL.get();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public boolean add(int x, int z) {
        if (released) return false;

        long key = key(x, z);

        if (held.contains(key)) return true;

        if (!world.addPluginChunkTicket(x, z, plugin)) return false;

        held.add(key);
        TOTAL.incrementAndGet();

        return true;
    }

    public boolean has(int x, int z) {
        return held.contains(key(x, z));
    }

    public void release(int x, int z) {
        if (!held.remove(key(x, z))) return;

        world.removePluginChunkTicket(x, z, plugin);
        TOTAL.decrementAndGet();
    }

    public void releaseAll() {
        released = true;

        for (long key : new HashSet<>(held)) release((int) (key >> 32), (int) key);
    }

    public int size() {
        return held.size();
    }
}
