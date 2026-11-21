package dev.portaldirector.preview;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.gen.BukkitTerrain;
import dev.portaldirector.gen.CachedTerrain;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.PortalPlanner;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Shows an admin where portals would go, by sending them fake blocks that nobody else sees. The world is never changed and
 * nothing is saved. Only the random layout is previewed. The blocks are put back when the admin hides the preview, changes world,
 * leaves, or the plugin stops.
 */
public final class PreviewManager implements Listener {

    private static final int MAX_PLANNING_TICKS = 100;

    private final PortalDirectorPlugin pl;
    private final PreviewRegistry registry = new PreviewRegistry();
    private final Map<UUID, BukkitTask> planning = new HashMap<>();

    public PreviewManager(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    private int maxPortals() {
        return Math.max(1, pl.getConfig().getInt("preview.max-portals", 100));
    }

    private int maxBlocks() {
        return Math.max(100, pl.getConfig().getInt("preview.max-blocks", 8000));
    }

    public PreviewRegistry registry() {
        return registry;
    }

    // Show

    public void show(Player admin, int cx, int cz) {
        if (pl.settings().gen.layout != Layout.RANDOM) {
            throw new IllegalArgumentException("The preview shows the random layout only. /pd set layout random, or run /pd create to build the "
                    + pl.settings().gen.layout.name().toLowerCase(Locale.ROOT) + " layout.");
        }

        String problem = pl.settings().gen.problem();
        if (problem != null) throw new IllegalArgumentException("These settings cannot make portals: " + problem);

        if (planning.containsKey(admin.getUniqueId())) {
            throw new IllegalArgumentException("Your preview is still being worked out. Wait a moment, or /pd preview hide.");
        }

        World world = admin.getWorld();
        int wanted = Math.min(pl.settings().gen.count, maxPortals());
        var settings = pl.settings().gen.copy();
        settings.count = wanted;

        var terrain = new CachedTerrain(new BukkitTerrain(world));
        var existing = pl.scenes().portalsIn(world.getName());
        var session = new PortalPlanner.Session(terrain, settings, cx, cz, existing, 1_000_000, new Random());
        hide(admin, false);

        int budget = Math.max(10, pl.settings().plannerBudget);
        UUID id = admin.getUniqueId();
        int[] ticks = {0};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(pl, () -> {
            Player now = Bukkit.getPlayer(id);

            if (now == null || !now.isOnline() || !now.getWorld().equals(world)) {
                cancelPlanning(id);

                return;
            }

            session.step(budget);

            if (!session.done() && ++ticks[0] < MAX_PLANNING_TICKS) return;

            cancelPlanning(id);
            send(now, world, session.placed(), wanted);
        }, 1L, 1L);

        planning.put(id, task);
        Msg.info(admin, "Working out " + wanted + " portals around " + cx + " " + cz + " (radius " + settings.radius + ")...");
    }

    private void cancelPlanning(UUID id) {
        BukkitTask task = planning.remove(id);
        if (task != null) task.cancel();
    }

    private void send(Player admin, World world, List<PortalSpec> placed, int wanted) {
        BlockData obsidian = Material.OBSIDIAN.createBlockData();
        List<BlockState> states = new ArrayList<>();
        List<int[]> positions = new ArrayList<>();
        int portals = 0;

        for (PortalSpec p : placed) {
            Orientable interior = (Orientable) Material.NETHER_PORTAL.createBlockData();
            interior.setAxis(p.axis());

            int before = states.size();
            boolean fits = add(states, positions, world, p.frameBlocks(), obsidian) && add(states, positions, world, p.interiorBlocks(), interior);

            if (!fits) {
                while (states.size() > before) {
                    states.remove(states.size() - 1);
                    positions.remove(positions.size() - 1);
                }

                break;
            }

            portals++;
        }

        if (states.isEmpty()) {
            Msg.err(admin, "No portal fits here with the current settings, so there is nothing to show. Try a smaller radius or another placement.");

            return;
        }

        admin.sendBlockChanges(states);
        registry.put(admin.getUniqueId(), new PreviewRegistry.Entry(world.getName(), positions, portals));

        Msg.ok(admin, "Showing " + portals + " of " + wanted + " portals, only to you. Nothing was built. /pd preview hide takes them off your screen. "
                + "/pd create builds portals for real, but their spots are random, so they will differ.");
    }

    private boolean add(List<BlockState> states, List<int[]> positions, World world, List<int[]> cells, BlockData data) {
        for (int[] c : cells) {
            if (states.size() >= maxBlocks()) return false;
            if (!world.isChunkLoaded(c[0] >> 4, c[2] >> 4)) continue;

            BlockState state = world.getBlockAt(c[0], c[1], c[2]).getState();
            state.setBlockData(data);
            states.add(state);
            positions.add(new int[]{c[0], c[1], c[2]});
        }

        return true;
    }

    // Hide and cleanup

    /** Takes the preview off one admin's screen. Returns false when there was none. */
    public boolean hide(Player admin, boolean tell) {
        cancelPlanning(admin.getUniqueId());
        PreviewRegistry.Entry entry = registry.remove(admin.getUniqueId());

        if (entry == null) {
            if (tell) Msg.info(admin, "You have no preview showing.");

            return false;
        }

        restore(admin, entry);
        if (tell) Msg.ok(admin, "The preview is off your screen. The world was never changed.");

        return true;
    }

    private void restore(Player admin, PreviewRegistry.Entry entry) {
        if (!admin.isOnline() || !admin.getWorld().getName().equals(entry.world())) return;

        List<BlockState> real = new ArrayList<>();

        for (int[] c : entry.positions()) {
            if (admin.getWorld().isChunkLoaded(c[0] >> 4, c[2] >> 4)) real.add(admin.getWorld().getBlockAt(c[0], c[1], c[2]).getState());
        }

        admin.sendBlockChanges(real);
    }

    public void status(Player admin) {
        PreviewRegistry.Entry entry = registry.get(admin.getUniqueId());

        if (planning.containsKey(admin.getUniqueId())) {
            Msg.info(admin, "Your preview is being worked out.");
        } else if (entry == null) {
            Msg.info(admin, "You have no preview showing. /pd preview show [x z] shows one.");
        } else {
            Msg.info(admin, "Showing " + entry.portals() + " portals (" + entry.positions().size() + " blocks) in " + entry.world() + ", only to you. "
                    + registry.count() + " admins have a preview now.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelPlanning(e.getPlayer().getUniqueId());
        registry.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        cancelPlanning(e.getPlayer().getUniqueId());
        registry.remove(e.getPlayer().getUniqueId());
    }

    /** Puts every real block back on every screen and stops all planning. */
    public void shutdown() {
        for (BukkitTask t : planning.values()) t.cancel();
        planning.clear();

        for (Map.Entry<UUID, PreviewRegistry.Entry> e : registry.drain().entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) restore(p, e.getValue());
        }
    }
}
