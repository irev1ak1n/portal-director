package dev.portaldirector.tnt;

import dev.portaldirector.Settings;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.SceneManager;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Launches TNT minecarts out of portals on explicit command only, in timed waves. */
public final class TntDirector implements Listener {

    public record Summary(int minecarts, int waves, int durationTicks, boolean preview) {
    }

    private final class Sequence {
        final World world;
        final List<PortalSpec> portals;
        final List<TntSchedule.Launch> launches;
        final CommandSender sender;
        int index;
        int tick;
        BukkitTask task;
        int skipped;

        Sequence(World world, List<PortalSpec> portals, List<TntSchedule.Launch> launches, CommandSender sender) {
            this.world = world;
            this.portals = portals;
            this.launches = launches;
            this.sender = sender;
        }

        void run() {
            int spawned = 0;
            while (index < launches.size() && launches.get(index).tick() <= tick) {
                if (spawned >= settings.tntSpawnsPerTick) break;
                TntSchedule.Launch l = launches.get(index++);
                if (launchedTotal >= settings.tntMaxActive) {
                    skipped++;
                } else if (spawn(world, portals.get(l.portalIndex()))) {
                    spawned++;
                }
            }
            tick++;
            if (index >= launches.size()) {
                if (skipped > 0 && sender != null) {
                    Msg.info(sender, skipped + " minecarts were not launched: the limit of " + settings.tntMaxActive
                            + " launched in total was reached. /pd tnt limit " + (settings.tntMaxActive + skipped) + " allows these too, or /pd tnt clear starts the count again.");
                }
                stopSequence(this);
            }
        }
    }

    private final Plugin plugin;
    private final Settings settings;
    private final SceneManager scenes;
    private final NamespacedKey tagKey;
    private final Map<UUID, Integer> active = new HashMap<>();
    private final List<Sequence> sequences = new ArrayList<>();
    /** Every minecart launched since the last /pd tnt clear, whether it exploded or not. */
    private int launchedTotal;
    private BukkitTask sweeper;

    public TntDirector(Plugin plugin, Settings settings, SceneManager scenes) {
        this.plugin = plugin;
        this.settings = settings;
        this.scenes = scenes;
        this.tagKey = new NamespacedKey(plugin, "tnt");
    }

    public void start() {
        sweeper = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, 20L, 20L);
    }

    public void shutdown() {
        if (sweeper != null) sweeper.cancel();
        stop();
    }

    public int activeCount() {
        return active.size();
    }

    /** Minecarts launched since the last /pd tnt clear. They count against the limit even after they explode. */
    public int launchedTotal() {
        return launchedTotal;
    }

    public int runningSequences() {
        return sequences.size();
    }

    /** Starts a launch sequence. Nothing is ever launched without this being called from a command. */
    public Summary launch(World world, List<PortalSpec> portals, int perPortal, int waves, int waveDelayTicks, int staggerTicks,
                          CommandSender sender) {
        List<TntSchedule.Launch> plan = TntSchedule.plan(portals.size(), perPortal, waves, waveDelayTicks,
                staggerTicks, settings.tntIntervalTicks);
        Sequence seq = new Sequence(world, new ArrayList<>(portals), plan, sender);
        sequences.add(seq);
        seq.task = plugin.getServer().getScheduler().runTaskTimer(plugin, seq::run, 0L, 1L);
        int duration = plan.isEmpty() ? 0 : plan.get(plan.size() - 1).tick();
        return new Summary(plan.size(), waves, duration, settings.tntPreview);
    }

    /** What {@link #launchOne} did. */
    public enum Launch { LAUNCHED, LIMIT, UNLOADED }

    /** How many more minecarts the cumulative launch limit allows. */
    public int remainingLaunches() {
        return Math.max(0, settings.tntMaxActive - launchedTotal);
    }

    /**
     * Launches one minecart now, for a sequence. It counts against the cumulative limit like every other launch and never
     * goes past it. {@code safe} overrides the global safe/real mode for this minecart only; null follows the global mode.
     */
    public Launch launchOne(World world, PortalSpec p, Boolean safe) {
        if (launchedTotal >= settings.tntMaxActive) return Launch.LIMIT;
        return spawn(world, p, safe) ? Launch.LAUNCHED : Launch.UNLOADED;
    }

    private boolean isSafe(Entity e) {
        Byte mode = e.getPersistentDataContainer().get(tagKey, PersistentDataType.BYTE);
        if (mode != null && mode == 2) return true;
        if (mode != null && mode == 3) return false;
        return settings.tntPreview;
    }

    private boolean spawn(World world, PortalSpec p) {
        return spawn(world, p, null);
    }

    private boolean spawn(World world, PortalSpec p, Boolean safe) {
        boolean preview = safe != null ? safe : settings.tntPreview;
        Location loc = new Location(world, p.centerX(), p.standY(), p.centerZ(), p.yaw(), 0f);
        if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return false;
        ExplosiveMinecart cart = world.spawn(loc, ExplosiveMinecart.class, c -> {
            c.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, safe == null ? (byte) 1 : safe ? (byte) 2 : (byte) 3);
            c.setMaxSpeed(Math.max(c.getMaxSpeed(), settings.tntSpeed));
        });
        cart.setVelocity(new Vector(p.stepX() * settings.tntSpeed, settings.tntLift, p.stepZ() * settings.tntSpeed));
        if (settings.tntFuseSeconds > 0 && !preview) {
            cart.ignite();
            cart.setFuseTicks((int) Math.round(settings.tntFuseSeconds * 20));
        }
        active.put(cart.getUniqueId(), Bukkit.getCurrentTick());
        launchedTotal++;
        return true;
    }

    private void stopSequence(Sequence s) {
        if (s.task != null) s.task.cancel();
        sequences.remove(s);
    }

    /** Stops sending new minecarts. Minecarts already out keep going. */
    public int stop() {
        int n = sequences.size();
        for (Sequence s : new ArrayList<>(sequences)) stopSequence(s);
        return n;
    }

    /** Stops sending new minecarts and removes every minecart this plugin launched. */
    public int clear() {
        stop();
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            for (ExplosiveMinecart cart : w.getEntitiesByClass(ExplosiveMinecart.class)) {
                if (isOurs(cart)) {
                    cart.remove();
                    removed++;
                }
            }
        }
        active.clear();
        launchedTotal = 0;
        return removed;
    }

    private boolean isOurs(Entity e) {
        return active.containsKey(e.getUniqueId()) || e.getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    private void sweep() {
        int now = Bukkit.getCurrentTick();
        int lifetime = settings.tntLifetimeSeconds * 20;
        Iterator<Map.Entry<UUID, Integer>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            Entity cart = Bukkit.getEntity(e.getKey());
            if (cart == null || !cart.isValid()) {
                it.remove();
            } else if (now - e.getValue() > lifetime) {
                cart.remove();
                it.remove();
            }
        }
    }

    /** Tracked minecarts must never travel through a vanilla portal. */
    @EventHandler(ignoreCancelled = true)
    public void onPortal(EntityPortalEvent e) {
        if (isOurs(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPrime(ExplosionPrimeEvent e) {
        if (isOurs(e.getEntity()) && isSafe(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent e) {
        if (!isOurs(e.getEntity())) return;
        if (settings.protectTerrain) {
            e.blockList().clear();
        } else if (settings.protectPortals) {
            String world = e.getEntity().getWorld().getName();
            List<PortalSpec> portals = scenes.portalsIn(world);
            e.blockList().removeIf(b -> {
                for (PortalSpec p : portals) {
                    if (p.contains(b.getX(), b.getY(), b.getZ())) return true;
                }
                return false;
            });
        }
        active.remove(e.getEntity().getUniqueId());
    }
}
