package dev.portaldirector.sequence;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.util.Job;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The three built-in sequences, their settings and what a sequence leaves behind: players held in portals, and portals that
 * let a group through even though the group's own Nether travel is off. Everything it holds is undone by
 * {@code /pd sequence stop}, when a player quits, and when the server shuts down.
 */
public final class SequenceManager implements Listener {

    /** Players a finished sequence left standing in portals, with where they stood before. */
    public record Staged(String sequence, Map<UUID, Location> homes, int portals, int shared) {
    }

    /** Players a finished nether-arrival left beside a Nether portal. */
    public record Arrival(String portal, int players, int x, int y, int z) {
    }

    /** A sequence that is running now. */
    public interface Active extends Job {
        String sequence();
    }

    private final PortalDirectorPlugin pl;
    private final SeqSettings settings;
    private final Map<String, Sequence> sequences = new TreeMap<>();
    private final Set<UUID> held = new HashSet<>();
    private final Set<String> travelPortals = new HashSet<>();
    private Active active;
    private Staged staged;
    private Arrival arrival;

    public SequenceManager(PortalDirectorPlugin pl) {
        this.pl = pl;
        this.settings = new SeqSettings(new File(pl.getDataFolder(), "sequences.yml"), pl.getLogger());
        settings.load();
        for (Sequence s : List.of(new NetherArrival(this), new SkyArmy(this), new TntStrike(this))) sequences.put(s.name(), s);
    }

    public PortalDirectorPlugin plugin() {
        return pl;
    }

    public SeqSettings settings() {
        return settings;
    }

    /** The sequences, A to Z. */
    public List<Sequence> all() {
        return new ArrayList<>(sequences.values());
    }

    public Sequence get(String name) {
        return sequences.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    // Holding players and portals

    public boolean isHeld(UUID id) {
        return held.contains(id);
    }

    public void hold(UUID id) {
        held.add(id);
    }

    public boolean allowsTravel(String scene, int portalId) {
        return travelPortals.contains(scene + "/" + portalId);
    }

    public void allowTravel(String scene, int portalId) {
        travelPortals.add(scene + "/" + portalId);
    }

    public int heldCount() {
        return held.size();
    }

    // The running sequence

    public Active active() {
        return active;
    }

    public void active(Active run) {
        this.active = run;
    }

    public void requireIdle() {
        if (active != null) {
            throw new IllegalArgumentException(active.sequence() + " is still running: " + active.describe() + ". /pd sequence stop cancels it.");
        }
    }

    /** A finished sequence that left players standing in portals. */
    public void staged(Staged s) {
        this.staged = s;
    }

    public void arrival(Arrival a) {
        this.arrival = a;
    }

    // Commands

    public void status(CommandSender s) {
        Msg.head(s, "Sequence status");
        boolean any = false;
        if (active != null) {
            Msg.line(s, "  " + active.sequence() + " is running: " + active.describe());
            any = true;
        }
        if (staged != null) {
            Msg.line(s, "  " + staged.sequence() + " is done: " + staged.homes().size() + " players wait in " + staged.portals() + " portals"
                    + (staged.shared() > 0 ? " (" + staged.shared() + " share a portal)" : "") + ". They cannot enter the Nether until /pd sequence stop sends them back.");
            any = true;
        }
        if (arrival != null) {
            Msg.line(s, "  nether-arrival is done: " + arrival.players() + " players wait beside the Nether partner of " + arrival.portal() + " at "
                    + arrival.x() + " " + arrival.y() + " " + arrival.z() + ". They walk in when you tell them to.");
            any = true;
        }
        if (!any) Msg.line(s, "  Nothing is running. /pd sequence list shows what you can run.");
    }

    public void stop(CommandSender s) {
        boolean did = false;
        if (active != null) {
            String name = active.sequence();
            active.cancel();
            Msg.ok(s, "Stopped " + name + ".");
            did = true;
        }
        if (staged != null) {
            int n = sendHome();
            did = true;
            Msg.ok(s, n + " staged players are back where they stood before, and can use portals again.");
        }
        if (arrival != null || !travelPortals.isEmpty()) {
            arrival = null;
            travelPortals.clear();
            Msg.ok(s, "Nether Arrival ended: the group's own Nether travel setting applies to its portal again. Players stay where they are.");
            did = true;
        }
        if (!did) Msg.info(s, "No sequence is running or waiting.");
    }

    /** Returns every staged player to where they stood, shows them to everyone and lets them use portals. */
    public int sendHome() {
        Staged st = staged;
        staged = null;
        if (st == null) return 0;
        int n = 0;
        for (Map.Entry<UUID, Location> e : st.homes().entrySet()) {
            held.remove(e.getKey());
            pl.cinematic().forget(e.getKey());
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline() || e.getValue().getWorld() == null) continue;
            p.teleport(e.getValue());
            p.setFallDistance(0f);
            p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            n++;
        }
        return n;
    }

    /** Makes a player an ordinary player again: visible, free to use portals. */
    public void release(UUID id) {
        held.remove(id);
        pl.cinematic().forget(id);
    }

    public void shutdown() {
        if (active != null) active.cancel();
        for (UUID id : new ArrayList<>(held)) release(id);
        staged = null;
        arrival = null;
        travelPortals.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        held.remove(id);
        if (staged != null) staged.homes().remove(id);
    }
}
