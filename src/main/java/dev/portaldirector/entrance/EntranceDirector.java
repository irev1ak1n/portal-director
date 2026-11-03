package dev.portaldirector.entrance;

import dev.portaldirector.Settings;
import dev.portaldirector.group.Group;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SceneManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Runs the scripted entrance for each group: stage the players, then release them so they step out of
 * their portals together. Players are teleported and walked, vanilla portal linking is never used.
 */
public final class EntranceDirector implements Listener {

    public enum Phase { IDLE, PREPARED, RELEASING, RELEASED }

    public record Report(int staged, List<String> offline, int emptyPortals) {
    }

    private static final class Staged {
        final Location returnTo;
        final int portalId;

        Staged(Location returnTo, int portalId) {
            this.returnTo = returnTo;
            this.portalId = portalId;
        }
    }

    private static final class Run {
        final Group group;
        final Scene scene;
        Phase phase = Phase.IDLE;
        final Map<UUID, Staged> staged = new LinkedHashMap<>();
        final List<BukkitTask> tasks = new ArrayList<>();
        final Set<Long> tickets = new HashSet<>();

        Run(Group group, Scene scene) {
            this.group = group;
            this.scene = scene;
        }
    }

    private static final class Walker {
        final UUID player;
        final PortalSpec portal;
        int ticksLeft;

        Walker(UUID player, PortalSpec portal, int ticksLeft) {
            this.player = player;
            this.portal = portal;
            this.ticksLeft = ticksLeft;
        }
    }

    private final Plugin plugin;
    private final Settings settings;
    private final SceneManager scenes;
    private final NamespacedKey stagedKey;
    private final Map<String, Run> runs = new HashMap<>();
    private final Set<UUID> frozen = new HashSet<>();
    private final Map<UUID, Integer> fallProtectedUntil = new HashMap<>();
    private final List<Walker> walkers = new ArrayList<>();
    private BukkitTask walkTask;

    public EntranceDirector(Plugin plugin, Settings settings, SceneManager scenes) {
        this.plugin = plugin;
        this.settings = settings;
        this.scenes = scenes;
        this.stagedKey = new NamespacedKey(plugin, "staged");
    }

    public void start() {
        walkTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::walkTick, 1L, 1L);
    }

    public void shutdown() {
        if (walkTask != null) walkTask.cancel();
        for (Run run : new ArrayList<>(runs.values())) reset(run.group, false);
    }

    public Phase phase(Group g) {
        Run r = runs.get(key(g));
        return r == null ? Phase.IDLE : r.phase;
    }

    public int stagedCount(Group g) {
        Run r = runs.get(key(g));
        return r == null ? 0 : r.staged.size();
    }

    private static String key(Group g) {
        return g.name().toLowerCase(Locale.ROOT);
    }

    // Prepare

    /** Moves every online assigned player to their portal (or the staging area) and holds them there. */
    public Report prepare(Group g, Scene scene) {
        Run run = runs.get(key(g));
        if (run != null && run.phase != Phase.IDLE) reset(g, true);
        run = new Run(g, scene);
        runs.put(key(g), run);
        World world = Bukkit.getWorld(scene.worldName());
        if (world == null) throw new IllegalStateException("World " + scene.worldName() + " is not loaded.");
        TreeMap<Integer, UUID> map = scene.assignmentsOf(g.name());
        List<String> offline = new ArrayList<>();
        for (Map.Entry<Integer, UUID> e : map.entrySet()) {
            PortalSpec spec = scene.portals().get(e.getKey());
            if (spec == null) continue;
            holdChunk(run, world, spec);
            Player p = Bukkit.getPlayer(e.getValue());
            if (p == null) {
                offline.add(g.members().getOrDefault(e.getValue(), e.getValue().toString()));
            } else {
                stage(run, p, spec, world);
            }
        }
        run.phase = Phase.PREPARED;
        return new Report(run.staged.size(), offline, scene.portals().size() - map.size());
    }

    private void holdChunk(Run run, World world, PortalSpec spec) {
        int cx = (int) Math.floor(spec.centerX()) >> 4;
        int cz = (int) Math.floor(spec.centerZ()) >> 4;
        if (run.tickets.add(((long) cx << 32) | (cz & 0xFFFFFFFFL))) world.addPluginChunkTicket(cx, cz, plugin);
    }

    private void stage(Run run, Player p, PortalSpec spec, World world) {
        run.staged.computeIfAbsent(p.getUniqueId(), id -> new Staged(p.getLocation().clone(), spec.id()));
        p.getPersistentDataContainer().set(stagedKey, PersistentDataType.BYTE, (byte) 1);
        if (settings.stagingInvisible) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
        Location target = settings.staging != null ? settings.staging.clone() : standLocation(world, spec);
        if (settings.freezeStaged) frozen.add(p.getUniqueId());
        p.teleportAsync(target);
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_AMBIENT, 0.6f, 1f);
    }

    private Location standLocation(World world, PortalSpec spec) {
        return new Location(world, spec.centerX(), spec.standY(), spec.centerZ(), spec.yaw(), 0f);
    }

    // Release

    /** Releases everyone at once, or in waves when {@code waveSize > 0}. Returns the number of waves. */
    public int release(Group g, Scene scene, int waveSize, int delayTicks) {
        List<Integer> slots = new ArrayList<>(scene.assignmentsOf(g.name()).keySet());
        List<List<Integer>> groups = new ArrayList<>();
        for (WavePlan.Wave w : waveSize <= 0 ? WavePlan.synchronous(slots.size()) : WavePlan.staggered(slots.size(), waveSize, delayTicks)) {
            groups.add(slots.subList(w.from(), w.to()));
        }
        return release(g, scene, groups, delayTicks);
    }

    /** Releases the given groups of portal ids one after another, {@code delayTicks} apart. Returns the group count. */
    public int release(Group g, Scene scene, List<List<Integer>> groups, int delayTicks) {
        Run run = runs.get(key(g));
        if (run == null || run.phase == Phase.IDLE) {
            run = new Run(g, scene);
            runs.put(key(g), run);
        } else if (run.phase == Phase.RELEASING) {
            throw new IllegalStateException("Group " + g.name() + " is already being released. Use reset to cancel it.");
        }
        World world = Bukkit.getWorld(scene.worldName());
        if (world == null) throw new IllegalStateException("World " + scene.worldName() + " is not loaded.");
        final Run active = run;
        for (int id : scene.assignmentsOf(g.name()).keySet()) {
            PortalSpec spec = scene.portals().get(id);
            if (spec != null) holdChunk(active, world, spec);
        }
        active.phase = Phase.RELEASING;
        for (int i = 0; i < groups.size(); i++) {
            List<Integer> ids = groups.get(i);
            boolean last = i == groups.size() - 1;
            int tick = i * delayTicks;
            if (tick == 0) {
                releaseWave(active, world, ids, last);
            } else {
                active.tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> releaseWave(active, world, ids, last), tick));
            }
        }
        if (groups.isEmpty()) active.phase = Phase.RELEASED;
        return groups.size();
    }

    private void releaseWave(Run run, World world, List<Integer> portalIds, boolean last) {
        TreeMap<Integer, UUID> map = run.scene.assignmentsOf(run.group.name());
        List<CompletableFuture<Boolean>> teleports = new ArrayList<>();
        List<Walker> started = new ArrayList<>();
        int expiry = Bukkit.getCurrentTick() + settings.fallProtectionSeconds * 20 + settings.walkTicks;
        for (int id : portalIds) {
            UUID uuid = map.get(id);
            PortalSpec spec = run.scene.portals().get(id);
            Player p = uuid == null ? null : Bukkit.getPlayer(uuid);
            if (p == null || spec == null) continue;
            run.staged.computeIfAbsent(uuid, u -> new Staged(p.getLocation().clone(), id));
            frozen.remove(uuid);
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            p.getPersistentDataContainer().remove(stagedKey);
            if (settings.fallProtectionSeconds > 0) fallProtectedUntil.put(uuid, expiry);
            Location out = standLocation(world, spec);
            teleports.add(p.teleportAsync(out));
            world.spawnParticle(Particle.PORTAL, out.clone().add(0, 1, 0), 40, 0.5, 0.9, 0.5, 0.3);
            started.add(new Walker(uuid, spec, settings.walkTicks));
        }
        CompletableFuture.allOf(teleports.toArray(new CompletableFuture<?>[0])).whenComplete((v, err) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    walkers.addAll(started);
                    if (last && run.phase == Phase.RELEASING) run.phase = Phase.RELEASED;
                }));
    }

    private void walkTick() {
        if (walkers.isEmpty()) return;
        Iterator<Walker> it = walkers.iterator();
        while (it.hasNext()) {
            Walker w = it.next();
            Player p = Bukkit.getPlayer(w.player);
            if (p == null || !p.isOnline() || p.isDead() || w.ticksLeft-- <= 0) {
                it.remove();
                continue;
            }
            Vector v = p.getVelocity();
            v.setX(w.portal.stepX() * settings.walkSpeed);
            v.setZ(w.portal.stepZ() * settings.walkSpeed);
            p.setVelocity(v);
        }
    }

    // Reset

    /** Cancels everything in progress for the group and sends staged players back. */
    public int reset(Group g, boolean sendBack) {
        Run run = runs.remove(key(g));
        if (run == null) return 0;
        run.tasks.forEach(BukkitTask::cancel);
        int returned = 0;
        for (Map.Entry<UUID, Staged> e : run.staged.entrySet()) {
            UUID id = e.getKey();
            frozen.remove(id);
            fallProtectedUntil.remove(id);
            walkers.removeIf(w -> w.player.equals(id));
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            p.getPersistentDataContainer().remove(stagedKey);
            if (sendBack && settings.returnOnReset && e.getValue().returnTo.getWorld() != null) {
                p.teleportAsync(e.getValue().returnTo);
                returned++;
            }
        }
        World world = Bukkit.getWorld(run.scene.worldName());
        if (world != null) {
            for (long t : run.tickets) world.removePluginChunkTicket((int) (t >> 32), (int) t, plugin);
        }
        return returned;
    }

    /** Resets every group that uses the scene, used before a scene is cleared. */
    public void resetScene(Scene scene) {
        for (Run run : new ArrayList<>(runs.values())) {
            if (run.scene == scene) reset(run.group, true);
        }
    }

    public String describe(Group g) {
        Run r = runs.get(key(g));
        if (r == null) return "idle";
        return r.phase.name().toLowerCase(Locale.ROOT) + ", " + r.staged.size() + " players staged";
    }

    // Events

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (frozen.isEmpty() || !frozen.contains(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        Location held = from.clone();
        held.setYaw(to.getYaw());
        held.setPitch(to.getPitch());
        e.setTo(held);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        if (frozen.contains(id) || p.getPersistentDataContainer().has(stagedKey, PersistentDataType.BYTE)) {
            e.setCancelled(true);
            return;
        }
        Integer until = fallProtectedUntil.get(id);
        if (until == null) return;
        if (until < Bukkit.getCurrentTick()) {
            fallProtectedUntil.remove(id);
        } else if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        boolean marked = p.getPersistentDataContainer().has(stagedKey, PersistentDataType.BYTE);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            for (Run run : runs.values()) {
                TreeMap<Integer, UUID> map = run.scene.assignmentsOf(run.group.name());
                for (Map.Entry<Integer, UUID> a : map.entrySet()) {
                    if (!a.getValue().equals(id)) continue;
                    PortalSpec spec = run.scene.portals().get(a.getKey());
                    World world = Bukkit.getWorld(run.scene.worldName());
                    if (spec != null && world != null && run.phase == Phase.PREPARED) stage(run, p, spec, world);
                    return;
                }
            }
            if (marked) {
                p.removePotionEffect(PotionEffectType.INVISIBILITY);
                p.getPersistentDataContainer().remove(stagedKey);
            }
        }, 10L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (settings.blockVanillaTravel && scenes.inPortalArea(e.getFrom())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (settings.blockVanillaTravel && scenes.inPortalArea(e.getFrom())) e.setCancelled(true);
    }
}
