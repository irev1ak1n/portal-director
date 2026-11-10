package dev.portaldirector.sequence;

import dev.portaldirector.gen.SinglePortal;
import dev.portaldirector.group.Group;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.nether.BukkitNetherGrid;
import dev.portaldirector.nether.NetherLinks;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Msg;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * One portal in the Overworld, and the group waiting beside its Nether partner. Nobody is walked in: the creator tells the
 * players when to step into the Nether portal.
 */
final class NetherArrival extends Sequence {

    NetherArrival(SequenceManager mgr) {
        super(mgr);
    }

    @Override
    public String name() {
        return "nether-arrival";
    }

    @Override
    public String title() {
        return "Nether Arrival";
    }

    @Override
    public String summary() {
        return "Builds one portal and moves your group beside its Nether partner. Players walk in when you tell them to.";
    }

    @Override
    public List<SeqOption> options() {
        return sorted(List.of(
                SeqOption.text("group", "", "the group that is moved (empty: the only group)"),
                SeqOption.whole("spawn-distance", 5, 2, 20, "how many blocks from the Nether portal players wait")));
    }

    @Override
    public String usage() {
        return "run <x y z | here | player> <north|south|east|west> [world]";
    }

    @Override
    public String example() {
        return "/pd sequence nether-arrival run here north";
    }

    @Override
    public List<String> facts() {
        return List.of(
                "Players are teleported automatically, once, to safe places beside the Nether portal. They are not walked into it and nothing is timed.",
                "They walk into the Nether portal themselves when you say so; it sends them to the portal you built.",
                "Portal travel is involved: that one portal lets the group through even if /pd group nether-travel is false. Other portals and the group setting are untouched.");
    }

    @Override
    public List<String> runTab(CommandSender s, List<String> a) {
        return targetTab(s, a, i -> i == 0 ? List.of("east", "north", "south", "west") : i == 1 ? worlds() : List.of());
    }

    @Override
    public void run(CommandSender s, List<String> a) {
        String usage = "Where, and which way? /pd sequence nether-arrival run <x y z | here | player> <north|south|east|west>. Example: /pd sequence nether-arrival run here north";
        Target t = target(s, a, usage);
        if (t.next() >= a.size()) throw new IllegalArgumentException(usage);
        BlockFace facing = face(a.get(t.next()));
        World world = worldFor(t, a, t.next() + 1);
        if (world.getEnvironment() != World.Environment.NORMAL) throw new IllegalArgumentException("The portal goes in the Overworld, and " + world.getName() + " is not one.");
        if (NetherLinks.netherWorld(world) == null) throw new IllegalArgumentException("This server has no Nether world to link to.");
        requireIdle();
        Group g = resolveGroup();
        List<Player> players = online(g);
        if (players.isEmpty()) {
            throw new IllegalArgumentException("None of the " + g.members().size() + " members of " + g.name() + " is online" + (g.members().isEmpty() ? " (the group has no members: /pd group assign-to-group <player> " + g.name() + ")" : "") + ".");
        }
        Scene scene = pl.scenes().currentOrCreate(world);
        PortalSpec existing = scene.atAnchor(t.x(), t.y(), t.z());
        String name;
        PortalSpec probe = null;
        if (existing != null) {
            if (existing.facing() != facing) {
                throw new IllegalArgumentException("A portal (" + scene.nameOf(existing.id()) + ") already stands at " + t.x() + " " + t.y() + " " + t.z() + " facing "
                        + existing.facing().name().toLowerCase(Locale.ROOT) + ". Use that direction, remove it with /pd remove " + scene.nameOf(existing.id()) + ", or pick another spot.");
            }
            name = scene.nameOf(existing.id());
        } else {
            int n = 1;
            String candidate = "nether-arrival";
            while (scene.byName(candidate) != null) candidate = "nether-arrival-" + (++n);
            name = candidate;
            probe = SinglePortal.check(pl, world, scene, name, t.x(), t.y(), t.z(), facing);
        }
        int distance = whole("spawn-distance");
        final String portalName = name;
        SequenceManager.Active job = new SequenceManager.Active() {
            @Override
            public String sequence() {
                return "nether-arrival";
            }

            @Override
            public String describe() {
                return "nether-arrival: building the portal and its Nether partner";
            }

            @Override
            public void cancel() {
                // It finishes within a moment and leaves nothing half done.
            }
        };
        mgr.active(job);
        pl.activeJob(job);
        Msg.ok(s, "Nether Arrival: " + (existing != null ? "using the portal " + name : "building " + name) + " at " + t.x() + " " + t.y() + " " + t.z() + " facing "
                + facing.name().toLowerCase(Locale.ROOT) + ", then moving " + players.size() + " players of " + g.name() + " beside its Nether partner.");
        CompletableFuture<Void> loaded = existing != null ? CompletableFuture.completedFuture(null) : SinglePortal.load(world, probe);
        loaded.whenComplete((v, err) -> pl.getServer().getScheduler().runTask(pl, () -> {
            try {
                PortalSpec spec = existing != null ? existing : SinglePortal.build(pl, world, scene, portalName, t.x(), t.y(), t.z(), facing, false);
                pl.netherLinks().whenSettled(() -> arrive(s, world, scene, spec, portalName, g, distance));
            } catch (RuntimeException e) {
                done(job);
                Msg.err(s, "Nether Arrival could not start: " + e.getMessage());
            }
        }));
    }

    private void done(SequenceManager.Active job) {
        if (pl.activeJob() == job) pl.activeJob(null);
        mgr.active(null);
    }

    private void arrive(CommandSender s, World world, Scene scene, PortalSpec spec, String name, Group g, int distance) {
        SequenceManager.Active job = mgr.active();
        try {
            PortalSpec partner;
            try {
                partner = pl.netherLinks().ensure(world, scene, spec);
            } catch (IllegalStateException e) {
                Msg.err(s, "Nether Arrival: " + name + " is built, but nobody was moved. " + e.getMessage());
                return;
            }
            World nether = NetherLinks.netherWorld(world);
            // The waiting places can be a good way from the portal; make sure their chunks are there.
            int reach = distance + ArrivalSpots.REACH + 2;
            int px = (int) Math.floor(partner.centerX());
            int pz = (int) Math.floor(partner.centerZ());
            for (int x = (px - reach) >> 4; x <= (px + reach) >> 4; x++) {
                for (int z = (pz - reach) >> 4; z <= (pz + reach) >> 4; z++) nether.getChunkAt(x, z);
            }
            List<Player> players = online(g);
            List<PortalSpec> others = new ArrayList<>();
            for (PortalSpec o : pl.netherLinks().netherScene().portals().values()) {
                if (o.id() != partner.id()) others.add(o);
            }
            List<ArrivalSpots.Spot> spots = ArrivalSpots.find(new BukkitNetherGrid(nether), partner, distance, players.size(), others);
            List<String> left = new ArrayList<>();
            int moved = 0;
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                if (i >= spots.size()) {
                    left.add(p.getName());
                    continue;
                }
                ArrivalSpots.Spot sp = spots.get(i);
                double x = sp.x() + 0.5;
                double z = sp.z() + 0.5;
                float yaw = ArrivalSpots.yawToward(x, z, partner.centerX(), partner.centerZ());
                p.teleport(new Location(nether, x, sp.y(), z, yaw, 0f));
                p.setFallDistance(0f);
                p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                moved++;
            }
            mgr.allowTravel(scene.name(), spec.id());
            mgr.arrival(new SequenceManager.Arrival(name, moved, partner.anchorX(), partner.anchorY(), partner.anchorZ()));
            if (left.isEmpty()) {
                Msg.ok(s, "Nether Arrival ready: all " + moved + " players of " + g.name() + " wait within " + (distance + ArrivalSpots.REACH) + " blocks of the Nether portal at "
                        + partner.anchorX() + " " + partner.anchorY() + " " + partner.anchorZ() + " (it leads to " + name + ").");
            } else {
                Msg.err(s, "Nether Arrival is only partly ready: " + moved + " players were moved; there was no safe standing place for " + String.join(", ", left)
                        + ". Try a larger /pd sequence nether-arrival set spawn-distance, or another location.");
            }
            Msg.info(s, "Nothing moves by itself. Tell the players when to walk into the Nether portal. /pd sequence stop ends this scene.");
        } catch (RuntimeException e) {
            pl.getLogger().warning("nether-arrival failed: " + e);
            e.printStackTrace();
            Msg.err(s, "Nether Arrival failed (" + e.getMessage() + "). The portal stays; /pd remove " + name + " takes it down.");
        } finally {
            if (job != null) done(job);
        }
    }
}
