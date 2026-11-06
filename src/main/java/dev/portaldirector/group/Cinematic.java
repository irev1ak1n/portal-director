package dev.portaldirector.group;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The cinematic part of a group: standing its members in their portals and controlling who can see them.
 *
 * <p>Hiding is per viewer ({@code Player#hideEntity}): other players' clients are told the entity does not exist, so there
 * is no invisibility potion, no particles and no floating armour. Only prepared members of a group whose visibility is off
 * are ever hidden, and every exit (quit, world change, leaving the group, deleting it, reload, shutdown) shows them again.
 */
public final class Cinematic implements Listener {

    /** What {@link #prepare} did, as player names. */
    public record Report(List<String> placed, List<String> offline, List<String> unassigned, List<String> unavailable) {
    }

    private final PortalDirectorPlugin pl;
    private final Set<UUID> prepared = new HashSet<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<String, List<BukkitTask>> reveals = new HashMap<>();

    public Cinematic(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    public boolean isHidden(UUID id) {
        return hidden.contains(id);
    }

    public boolean isPrepared(UUID id) {
        return prepared.contains(id);
    }

    // Preparing

    public Report prepare(Group g) {
        Scene scene = pl.scenes().current();
        if (scene == null || scene.portals().isEmpty()) {
            throw new IllegalArgumentException("There are no portals yet. Build some with /pd create, then link players to them with /pd group link-to-portal.");
        }
        cancelReveals(g);
        List<String> placed = new ArrayList<>();
        List<String> offline = new ArrayList<>();
        List<String> unassigned = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        World world = Bukkit.getWorld(scene.worldName());
        for (Map.Entry<UUID, String> m : g.members().entrySet()) {
            Player p = Bukkit.getPlayer(m.getKey());
            String portalName = g.portals().get(m.getKey());
            if (p == null) {
                offline.add(m.getValue());
                continue;
            }
            if (portalName == null) {
                unassigned.add(m.getValue());
                continue;
            }
            PortalSpec spec = scene.byName(portalName);
            if (spec == null || world == null) {
                unavailable.add(m.getValue() + " (" + portalName + ")");
                continue;
            }
            if (!g.visibilityInPortal()) hide(p);
            else if (hidden.contains(p.getUniqueId())) show(p);
            p.teleport(new Location(world, spec.centerX(), spec.standY(), spec.centerZ(), spec.yaw(), 0f));
            prepared.add(p.getUniqueId());
            placed.add(m.getValue());
        }
        return new Report(placed, offline, unassigned, unavailable);
    }

    /**
     * After Nether travel is switched on: members already standing in their portal had their trip cancelled, and vanilla
     * only starts a new one when someone steps in. They are moved two blocks out and straight back, so travel begins now.
     */
    public void reenterPortals(Group g) {
        Scene scene = pl.scenes().current();
        World world = scene == null ? null : Bukkit.getWorld(scene.worldName());
        if (world == null) return;
        for (UUID id : g.members().keySet()) {
            Player p = Bukkit.getPlayer(id);
            String name = g.portals().get(id);
            PortalSpec spec = p == null || name == null || !prepared.contains(id) ? null : scene.byName(name);
            if (spec == null) continue;
            p.teleport(new Location(world, spec.centerX() + spec.stepX() * 2, spec.standY(), spec.centerZ() + spec.stepZ() * 2, spec.yaw(), 0f));
            // Out for longer than vanilla's portal cooldown, back before a floating portal's walkway can drop them far.
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                if (!p.isOnline()) return;
                p.teleport(new Location(world, spec.centerX(), spec.standY(), spec.centerZ(), spec.yaw(), 0f));
                p.setFallDistance(0f);
                p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            }, 16L);
        }
    }

    // Visibility

    /** Turning it off hides the prepared members now; turning it on shows hidden members in small staggered groups. */
    public void setVisibility(Group g, boolean visible) {
        cancelReveals(g);
        g.visibilityInPortal(visible);
        List<Player> targets = new ArrayList<>();
        for (UUID id : g.members().keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (!visible && prepared.contains(id)) hide(p);
            if (visible && hidden.contains(id)) targets.add(p);
        }
        if (visible && !targets.isEmpty()) {
            int delay = (int) Math.max(1, Math.round(g.visibilityDelay() * 20));
            List<BukkitTask> tasks = new ArrayList<>();
            for (RevealPlan.Batch b : RevealPlan.plan(targets.size(), delay)) {
                List<Player> batch = new ArrayList<>(targets.subList(b.from(), b.to()));
                Runnable show = () -> batch.forEach(p -> {
                    if (p.isOnline() && hidden.contains(p.getUniqueId())) show(p);
                });
                if (b.tick() == 0) show.run();
                else tasks.add(Bukkit.getScheduler().runTaskLater(pl, show, b.tick()));
            }
            reveals.put(g.name().toLowerCase(Locale.ROOT), tasks);
        }
    }

    private void cancelReveals(Group g) {
        List<BukkitTask> tasks = reveals.remove(g.name().toLowerCase(Locale.ROOT));
        if (tasks != null) tasks.forEach(BukkitTask::cancel);
    }

    /** Hides a player from everyone else (used by sequences). {@link #forget} or {@link #reveal} shows them again. */
    public void conceal(Player p) {
        hide(p);
    }

    /** Shows a player who was hidden with {@link #conceal}. */
    public void reveal(Player p) {
        if (hidden.contains(p.getUniqueId())) show(p);
    }

    private void hide(Player p) {
        hidden.add(p.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer != p) viewer.hideEntity(pl, p);
        }
    }

    private void show(Player p) {
        hidden.remove(p.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer != p) viewer.showEntity(pl, p);
        }
    }

    // Letting go

    /** The player is no longer part of a sequence: visible again, and not prepared. */
    public void forget(UUID id) {
        prepared.remove(id);
        if (hidden.contains(id)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) show(p);
            else hidden.remove(id);
        }
    }

    public void forgetGroup(Group g) {
        cancelReveals(g);
        for (UUID id : g.members().keySet()) forget(id);
    }

    public void shutdown() {
        reveals.values().forEach(l -> l.forEach(BukkitTask::cancel));
        reveals.clear();
        for (UUID id : new ArrayList<>(hidden)) forget(id);
        prepared.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        forget(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (prepared.contains(e.getPlayer().getUniqueId())) forget(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        for (UUID id : hidden) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p != e.getPlayer()) e.getPlayer().hideEntity(pl, p);
        }
    }
}
